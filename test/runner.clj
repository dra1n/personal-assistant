(ns runner
  (:require [clojure.test :as test]
            pa.system-test
            pa.runtime.events-test
            pa.runtime.dispatcher-test
            pa.runtime.coeffects-test
            pa.runtime.executor-test
            pa.runtime.interceptors-test
            pa.runtime.replay-test
            pa.runtime.replay-from-disk-test
            pa.state.queries-test
            pa.state.db-test
            pa.storage.events-test
            pa.storage.identity-test
            pa.memory.records-test
            pa.storage.memory-test
            pa.db.memory-test
            pa.llm.openai-test
            pa.llm.anthropic-test
            pa.llm.prompt-test))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-all-tests #"pa\..*-test")]
    (System/exit (if (zero? (+ fail error)) 0 1))))
