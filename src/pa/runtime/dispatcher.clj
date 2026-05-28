(ns pa.runtime.dispatcher
  (:require [clojure.core.async :as async]
            [integrant.core :as ig]
            [pa.runtime.events :as events]
            [pa.runtime.registry :as registry]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Dispatcher Integrant component
;;
;; Owns the core.async channel and the consumer go-loop.
;; Exposes a dispatch! fn closed over the channel — callers never touch the
;; channel directly.
;;
;; process-event! is the current single-step handler invocation. In Group 5
;; this will be replaced by the interceptor chain runner with no changes to
;; dispatch! itself.
;; ---------------------------------------------------------------------------

(defn- process-event! [event]
  (let [handler (registry/get-handler (:event/type event))]
    (if handler
      (do
        (log/debug "dispatching" (:event/type event) (:event/id event))
        (handler event))
      (log/debug "no handler registered for" (:event/type event)))))

(defmethod ig/init-key :pa.runtime/dispatcher [_ _opts]
  (let [ch (async/chan 256)]
    (async/go-loop []
      (when-let [event (async/<! ch)]
        (process-event! event)
        (recur)))
    (log/info "dispatcher started")
    {:channel   ch
     :dispatch! (fn [event-map]
                  (async/put! ch (events/make-event event-map)))}))

(defmethod ig/halt-key! :pa.runtime/dispatcher [_ {:keys [channel]}]
  (async/close! channel)
  (log/info "dispatcher stopped"))
