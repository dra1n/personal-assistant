(ns user
  (:require [integrant.repl :as ig-repl]
            [integrant.repl.state :as ig-state]
            [pa.config :as config]
            [pa.logging :as logging]
            [pa.system]))

;; configure! before every prep for the same reason pa.system/start! does it:
;; the logging component initialises last, so without this a REPL start drops
;; the startup logging of everything ahead of it.
(ig-repl/set-prep! (fn [] (logging/configure!) (config/system-config)))

(def ^:export start  ig-repl/go)
(def ^:export stop   ig-repl/halt)
(def ^:export reset  ig-repl/reset)
(defn ^:export system [] ig-state/system)