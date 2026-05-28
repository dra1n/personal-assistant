(ns pa.runtime.executor
  (:require [clojure.core.async :as async]
            [pa.runtime.state :as state]
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
;; MUTATION RULE: :db is the only permitted site for swap!/reset! on state/db.
;; ---------------------------------------------------------------------------

(defmulti execute-effect
  (fn [effect-type _params _ctx] effect-type))

;; --- :db ---------------------------------------------------------------

(defmethod execute-effect :db [_ new-db _ctx]
  (reset! state/db new-db))

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
    (swap! state/trace-log conj entry)))

;; --- :tap --------------------------------------------------------------

(defmethod execute-effect :tap [_ value _ctx]
  (tap> value))

;; --- :event/store (stub) -----------------------------------------------

(defmethod execute-effect :event/store [_ _params _ctx]
  (log/warn ":event/store is not yet implemented — Phase 2"))

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
