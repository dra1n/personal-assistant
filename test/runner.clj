(ns runner
  (:require [clojure.test :as test]
            pa.system-test
            pa.runtime.events-test
            pa.runtime.dispatcher-test
            pa.runtime.coeffects-test
            pa.runtime.executor-test
            pa.runtime.interceptors-test
            pa.runtime.replay-test
            pa.runtime.queries-test
            pa.runtime.state-test))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-all-tests #"pa\..*-test")]
    (System/exit (if (zero? (+ fail error)) 0 1))))
