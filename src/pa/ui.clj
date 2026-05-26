(ns pa.ui
  (:require [integrant.core :as ig]
            [taoensso.timbre :as log]))

;; charm.clj TUI stub — renders a static hello frame on start.
;; Full TUI wiring happens in later phases.
(defmethod ig/init-key :pa.ui/terminal [_ _opts]
  (log/info "terminal UI initialized")
  (println "\n┌─────────────────────────────┐")
  (println "│  personal assistant  v0.0.0 │")
  (println "└─────────────────────────────┘\n")
  {})

(defmethod ig/halt-key! :pa.ui/terminal [_ _]
  (log/info "terminal UI stopped"))
