(ns pa.runtime
  (:require [clojure.core.async :as async]
            [integrant.core :as ig]
            [taoensso.timbre :as log]))

(defmethod ig/init-key :pa.runtime/event-bus [_ _opts]
  (let [ch (async/chan 256)]
    (async/go-loop []
      (when-let [event (async/<! ch)]
        (log/debug "event-bus received" event)
        (recur)))
    {:channel ch}))

(defmethod ig/halt-key! :pa.runtime/event-bus [_ {:keys [channel]}]
  (async/close! channel))

(defn ^:export publish! [bus event]
  (async/put! (:channel bus) event))
