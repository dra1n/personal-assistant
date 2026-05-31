(ns pa.runtime.executor
  (:require [clojure.core.async :as async]
            [pa.state.db :as db]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Effect executor
;;
;; execute-effect is a multimethod dispatched on effect-type keyword.
;; Handlers return an effects map; execute-effects! iterates it and calls
;; execute-effect for each entry.
;;
;; ctx is a map passed from the dispatcher containing runtime capabilities:
;;   {:dispatch! fn}
;;
;; Effect vocabulary (Phase 1):
;;
;;   Runtime effects (pure/internal — safe to replay):
;;     :db             — reset runtime state to the new value
;;     :dispatch       — enqueue a new event immediately
;;     :dispatch-later — schedule a delayed event dispatch
;;
;;   Observability effects:
;;     :log/info  — structured log entry via Timbre
;;     :trace     — append an entry to the runtime trace log
;;     :tap       — emit a value via tap> for Portal inspection
;;
;;   Persistence effects (stub — implemented in Phase 2):
;;     :event/store — no-op; defined so Phase 2 is a drop-in
;;
;; MUTATION RULE: :db is the only permitted site for swap!/reset! on db/db.
;; ---------------------------------------------------------------------------

(defmulti execute-effect
  (fn [effect-type _params _ctx] effect-type))

;; --- :db ---------------------------------------------------------------

(defmethod execute-effect :db [_ new-db _ctx]
  (reset! db/db new-db))

;; --- :dispatch ---------------------------------------------------------

(defmethod execute-effect :dispatch [_ event-map {:keys [dispatch!]}]
  (dispatch! event-map))

;; --- :dispatch-later ---------------------------------------------------
;;
;; params: {:event event-map :delay-ms ms}

(defmethod execute-effect :dispatch-later [_ {:keys [event delay-ms]} {:keys [dispatch!]}]
  (async/go
    (async/<! (async/timeout delay-ms))
    (dispatch! event)))

;; --- :log/info ---------------------------------------------------------
;;
;; params: {:message str, ...extra keys passed as context}

(defmethod execute-effect :log/info [_ {:keys [message] :as params} _ctx]
  (log/info (dissoc params :message) message))

;; --- :trace ------------------------------------------------------------
;;
;; params: any map; :timestamp is stamped automatically if absent

(defmethod execute-effect :trace [_ params _ctx]
  (let [entry (merge {:timestamp (java.time.Instant/now)} params)]
    (swap! db/trace-log conj entry)))

;; --- :tap --------------------------------------------------------------

(defmethod execute-effect :tap [_ value _ctx]
  (tap> value))

;; --- :event/store -------------------------------------------------------
;;
;; params: the event map to persist.
;; ctx must contain :store-event! fn supplied by :storage/events component.

(defmethod execute-effect :event/store [_ event {:keys [store-event!]}]
  (if store-event!
    (store-event! event)
    (log/warn ":event/store called but no :store-event! in ctx — is :storage/events wired?")))

;; --- :memory/write ------------------------------------------------------
;;
;; params: a memory record map (pa.memory.records/make output).
;; ctx must contain :write-memory! fn supplied by :memory/store component.
;; On success the handler receives a :memory/stored dispatch carrying the
;; persisted record (with :memory/path stamped by the writer).

(defmethod execute-effect :memory/write [_ record {:keys [write-memory! dispatch!]}]
  (if write-memory!
    (let [persisted (write-memory! record)]
      (dispatch! {:event/type :memory/stored :record persisted}))
    (log/warn ":memory/write called but no :write-memory! in ctx — is :memory/store wired?")))

;; --- :memory/index ------------------------------------------------------
;;
;; params: a persisted memory record map (with :memory/path stamped).
;; ctx must contain :index-memory! fn supplied by :memory/indexer component.

(defmethod execute-effect :memory/index [_ record {:keys [index-memory!]}]
  (if index-memory!
    (index-memory! record)
    (log/warn ":memory/index called but no :index-memory! in ctx — is :memory/indexer wired?")))

;; --- default -----------------------------------------------------------

(defmethod execute-effect :default [effect-type _params _ctx]
  (log/warn "unknown effect type — ignoring" {:effect/type effect-type}))

;; ---------------------------------------------------------------------------
;; execute-effects!
;;
;; Iterates the effects map returned by a handler and executes each entry.
;; ctx is the runtime capabilities map {dispatch!}.
;; ---------------------------------------------------------------------------

(defn execute-effects! [effects ctx]
  (doseq [[effect-type params] effects]
    (execute-effect effect-type params ctx)))
