(ns pa.core
  (:require [pa.logging :as logging]
            [pa.system :as system]))

(defn -main [& _args]
  ;; App mode runs the TUI on stdout — silence console logging before any
  ;; component initialises so startup logs never scribble over the frame.
  (logging/set-console! false)
  (let [sys (system/start!)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(system/stop!)))
    (when-let [result (get-in sys [:pa.ui/terminal :result])]
      @result
      (System/exit 0))))
