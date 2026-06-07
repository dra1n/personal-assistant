(ns pa.runtime.dispatcher
  (:require [clojure.core.async :as async]
            [integrant.core :as ig]
            [pa.http :as http]
            [pa.runtime.events :as events]
            [pa.runtime.interceptors :as interceptors]
            [pa.state.db :as db]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Dispatcher Integrant component
;;
;; Owns the core.async channel and the consumer go-loop.
;; Builds system-context once at start time and closes it over process-event!.
;;
;; process-event! accumulates the event into :events/recent then delegates
;; to the interceptor chain runner (Group 5). The chain handles coeffect
;; injection, handler invocation, effect validation, tracing, and execution.
;; ---------------------------------------------------------------------------

(defn- process-event! [event system-context]
  ;; Permitted mutation site 2: accumulate event into :events/recent before handler runs.
  (swap! db/db update :events/recent conj event)
  (interceptors/run-standard-chain event system-context))

(defmethod ig/init-key :pa.runtime/dispatcher [_ {:keys [config events identity history memory indexer llm policy scheduler deltas]}]
  (let [ch (async/chan 256)
        dispatch! (fn [event-map]
                    (async/put! ch (events/make-event event-map)))
        ;; Non-blocking push of an LLM delta onto the UI side-channel. Deltas
        ;; are best-effort live display; the authoritative full text is
        ;; accumulated by the :llm/invoke effect, so dropping is harmless.
        emit-delta! (fn [delta] (when deltas (async/offer! deltas delta)))
        system-context {:config  config
                        :runtime {:dispatch!          dispatch!
                                  :store-event!       (:append-event! events)
                                  :append-history!    (:append-entry! history)
                                  :write-memory!      (:write-memory! memory)
                                  :index-memory!      (:index-memory! indexer)
                                  :retrieve-memories! (:retrieve-memories! indexer)
                                  :llm-provider       llm
                                  :tool.fs/policy     policy
                                  :http               (http/hato-client)
                                  :emit-delta!        emit-delta!
                                  :schedule-task!     (:schedule! scheduler)
                                  :cancel-task!       (:cancel! scheduler)}}]
    (async/go-loop []
      (when-let [event (async/<! ch)]
        (process-event! event system-context)
        (recur)))
    (when scheduler
      ((:start! scheduler) dispatch!))
    (when (seq (:history history))
      (dispatch! {:event/type :history/loaded
                  :entries    (:history history)}))
    (when identity
      (dispatch! {:event/type :system/identity-loaded
                  :identity   (:identity identity)}))
    (log/info "dispatcher started")
    {:channel   ch
     :dispatch! dispatch!}))

(defmethod ig/halt-key! :pa.runtime/dispatcher [_ {:keys [channel]}]
  (async/close! channel)
  (log/info "dispatcher stopped"))
