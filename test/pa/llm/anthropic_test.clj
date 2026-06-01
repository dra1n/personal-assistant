(ns pa.llm.anthropic-test
  (:require [clojure.test :refer [deftest is testing]]
            [pa.llm.anthropic :as anthropic]
            [pa.llm.provider :as provider]))

(deftest stub-conforms-but-not-implemented-test
  (testing "the stub satisfies the protocol yet signals it is unimplemented"
    (let [prov (anthropic/make-provider {:api-key "x"})]
      (is (satisfies? provider/LLMProvider prov))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not implemented"
                            (provider/stream prov [] {} (fn [_]))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not implemented"
                            (provider/invoke prov [] {}))))))
