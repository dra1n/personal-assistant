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
            pa.storage.history-test
            pa.storage.identity-test
            pa.memory.records-test
            pa.storage.memory-test
            pa.db.memory-test
            pa.llm.openai-test
            pa.llm.anthropic-test
            pa.llm.prompt-test
            pa.runtime.conversation-test
            pa.runtime.tool-call-test
            pa.runtime.tool-effect-test
            pa.tools.registry-test
            pa.tools.schema-test
            pa.tools.fs.policy-test
            pa.tools.fs-test
            pa.tools.network.search-test
            pa.tools.network.ssrf-test
            pa.tools.network.fetch-test
            pa.tools.network.youtube-test
            pa.tools.os-test
            pa.ui.app-test
            pa.ui.view-test))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-all-tests #"pa\..*-test")]
    (System/exit (if (zero? (+ fail error)) 0 1))))
