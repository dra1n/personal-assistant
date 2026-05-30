(ns pa.system
  (:require [integrant.core :as ig]
            [pa.config :as config]
            [pa.logging]
            [pa.observability]
            [pa.runtime.dispatcher]
            [pa.storage.events]
            [pa.storage.fs]
            [pa.ui.core]))

(defonce ^:private state (atom nil))

(defn start! []
  (let [sys (ig/init (config/system-config))]
    (reset! state sys)
    sys))

(defn stop! []
  (when-let [sys @state]
    (ig/halt! sys)
    (reset! state nil)))

(defn ^:export system [] @state)
