(ns pa.core
  (:require [pa.system :as system]))

(defn -main [& _args]
  (system/start!)
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. #(system/stop!))))
