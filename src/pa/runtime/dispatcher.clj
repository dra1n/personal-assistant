(ns pa.runtime.dispatcher
  (:require [clojure.core.async :as async]
            [integrant.core :as ig]
            [pa.runtime.coeffects :as coeffects]
            [pa.runtime.events :as events]
            [pa.runtime.executor :as executor]
            [pa.runtime.registry :as registry]
            [pa.runtime.state :as state]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Dispatcher Integrant component
;;
;; Owns the core.async channel and the consumer go-loop.
;; Builds system-context once at start time and closes it over process-event!.
;;
;; process-event! injects coeffects then invokes the handler with the
;; enriched context map. In Group 5 this will be replaced by the interceptor
;; chain runner with no changes to dispatch! itself.
;; ---------------------------------------------------------------------------

(defn- process-event! [event system-context]
  ;; Permitted mutation site 2: accumulate event into :events/recent before handler runs.
  (swap! state/db update :events/recent conj event)
  (let [handler (registry/get-handler (:event/type event))]
    (if handler
      (do
        (log/debug "dispatching" (:event/type event) (:event/id event))
        (let [coeffects (coeffects/inject-coeffects event system-context)
              effects   (handler coeffects)]
          (when (map? effects)
            (executor/execute-effects! effects (:runtime system-context)))))
      (log/debug "no handler registered for" (:event/type event)))))

(defmethod ig/init-key :pa.runtime/dispatcher [_ {:keys [config]}]
  (let [ch (async/chan 256)
        dispatch! (fn [event-map]
                    (async/put! ch (events/make-event event-map)))
        system-context {:config  config
                        :runtime {:dispatch! dispatch!}}]
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
