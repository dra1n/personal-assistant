(ns pa.logging
  (:require [integrant.core :as ig]
            [taoensso.timbre :as log]))

(defmethod ig/init-key :pa.logging/timbre [_ _opts]
  (log/merge-config! {:min-level :debug
                      :appenders {:println (log/println-appender {:stream :auto})}})
  (log/info "logging initialized")
  {})

(defmethod ig/halt-key! :pa.logging/timbre [_ _]
  (log/info "logging shutting down"))
