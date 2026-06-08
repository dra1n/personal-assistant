(ns pa.system
  (:require [integrant.core :as ig]
            [pa.config :as config]
            [pa.logging]
            [pa.observability]
            [pa.runtime.dispatcher]
            [pa.runtime.handlers]
            [pa.storage.events]
            [pa.storage.fs]
            [pa.storage.history]
            [pa.storage.identity]
            [pa.storage.memory-store]
            [pa.db.sqlite]
            [pa.memory.indexer]
            [pa.llm.component]
            [pa.scheduler.core]
            [pa.tools.fs.policy]
            [pa.tools.fs]
            [pa.tools.network.search]
            [pa.tools.network.fetch]
            [pa.tools.network.youtube]
            [pa.tools.os]
            [pa.tools.reminder]
            [pa.tools.time]
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
