(ns user
  (:require [integrant.repl :as ig-repl]
            [integrant.repl.state :as ig-state]
            [pa.config :as config]
            [pa.system]))

(ig-repl/set-prep! (fn [] (config/system-config)))

(def ^:export start  ig-repl/go)
(def ^:export stop   ig-repl/halt)
(def ^:export reset  ig-repl/reset)
(defn ^:export system [] ig-state/system)