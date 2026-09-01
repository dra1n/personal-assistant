(ns pa.core
  (:require [pa.logging :as logging]
            [pa.system :as system]))

(defn -main [& _args]
  ;; App mode runs the TUI on stdout. Silence the console before ig/init:
  ;; :pa.logging/timbre initialises first and installs :println according to
  ;; this flag, and the UI that would otherwise flip it comes near the end of
  ;; the graph — so every component in between would scribble over the frame.
  (logging/set-console! false)
  (let [sys (system/start!)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(system/stop!)))
    (when-let [result (get-in sys [:pa.ui/terminal :result])]
      @result
      (System/exit 0))))
