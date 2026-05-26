(ns pa.core
  (:require [pa.system :as system]))

(defn -main [& _args]
  (let [sys (system/start!)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(system/stop!)))
    (when-let [result (get-in sys [:pa.ui/terminal :result])]
      @result
      (System/exit 0))))
