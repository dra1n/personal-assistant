(ns pa.runtime.dispatcher
  (:require [clojure.core.async :as async]
            [integrant.core :as ig]
            [pa.runtime.events :as events]
            [pa.runtime.interceptors :as interceptors]
            [pa.runtime.state :as state]
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
  (swap! state/db update :events/recent conj event)
  (interceptors/run-standard-chain event system-context))

(defmethod ig/init-key :pa.runtime/dispatcher [_ {:keys [config events]}]
  (let [ch (async/chan 256)
        dispatch! (fn [event-map]
                    (async/put! ch (events/make-event event-map)))
        system-context {:config  config
                        :runtime {:dispatch!     dispatch!
                                  :store-event!  (:append-event! events)}}]
    (async/go-loop []
      (when-let [event (async/<! ch)]
        (process-event! event system-context)
        (recur)))
    (log/info "dispatcher started")
    {:channel   ch
     :dispatch! dispatch!}))

(defmethod ig/halt-key! :pa.runtime/dispatcher [_ {:keys [channel]}]
  (async/close! channel)
  (log/info "dispatcher stopped"))
