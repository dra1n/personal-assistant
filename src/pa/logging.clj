(ns pa.logging
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [pa.storage.fs :as fs]
            [taoensso.timbre :as log]))

(defn- log-file
  "Absolute path to the durable log file under PA_HOME/logs, parents created."
  []
  (let [f (io/file (fs/pa-home) "logs" "pa.log")]
    (io/make-parents f)
    (str f)))

;; The println appender is the default REPL-time sink. It writes to stdout,
;; which collides with the charm TUI, so pa.ui.core disables it while the
;; terminal UI is running and restores it on halt. The file appender is the
;; durable record and stays on in both modes.
(defmethod ig/init-key :pa.logging/timbre [_ _opts]
  (log/merge-config!
   {:min-level :debug
    :appenders {:println (log/println-appender {:stream :auto})
                :file    (log/spit-appender {:fname (log-file)})}})
  (log/info "logging initialized")
  {})

(defmethod ig/halt-key! :pa.logging/timbre [_ _]
  (log/info "logging shutting down"))
