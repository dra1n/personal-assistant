(ns pa.llm.prompt-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pa.llm.prompt :as prompt]))

(deftest assemble-minimal-exact-test
  (testing "exact messages vector for a minimal identity + one turn"
    (is (= [{:role :system :content "# Assistant identity\nname: Aria"}
            {:role :user :content "hi"}]
           (prompt/assemble
            {:identity        {:soul {:front-matter {:name "Aria"} :prose ""}}
             :conversation    [{:role :user :content "hi"}]
             :memory-snippets []})))))

(deftest assemble-renders-front-matter-and-prose-test
  (testing "front-matter fields and cleaned prose both appear; empties omitted"
    (let [sys (-> (prompt/assemble
                   {:identity {:soul {:front-matter {:name "Aria"
                                                     :traits ["curious" "helpful"]
                                                     :communication-style ""
                                                     :values []}
                                      :prose "<!-- describe -->\nWarm and direct."}
                               :user {:front-matter {:name "Alice"
                                                     :preferences {:tone "terse"}}
                                      :prose ""}}
                    :conversation [] :memory-snippets []})
                  first :content)]
      (is (str/includes? sys "name: Aria"))
      (is (str/includes? sys "traits: curious, helpful") "sequence rendered comma-joined")
      (is (str/includes? sys "tone: terse") "nested map rendered")
      (is (str/includes? sys "Warm and direct.") "prose included")
      (is (not (str/includes? sys "<!--")) "HTML comments stripped")
      (is (not (str/includes? sys "communication-style")) "blank string field omitted")
      (is (not (str/includes? sys "values")) "empty seq field omitted"))))

(deftest assemble-injects-memory-snippets-test
  (testing "memory snippets are surfaced in the system message (injected, not fetched)"
    (let [msgs (prompt/assemble
                {:identity        {}
                 :conversation    []
                 :memory-snippets [#:memory{:title "Trip"  :summary "to Japan in May"}
                                   #:memory{:title "Cat"   :summary "named Mochi"}
                                   #:memory{:title "Note"  :summary ""}]})
          sys  (-> msgs first :content)]
      (is (= :system (:role (first msgs))))
      (is (str/includes? sys "# Relevant context from memory"))
      (is (str/includes? sys "- Trip: to Japan in May"))
      (is (str/includes? sys "- Cat: named Mochi"))
      (is (str/includes? sys "- Note") "blank summary renders title only")
      (is (not (str/includes? sys "Note: ")) "no trailing colon for blank summary"))))

(deftest assemble-maps-conversation-and-strips-metadata-test
  (testing "conversation entries become messages with only :role/:content"
    (is (= [{:role :user :content "q"}
            {:role :assistant :content "a"}]
           (prompt/assemble
            {:identity        {}
             :conversation    [{:role :user :content "q" :timestamp 123}
                               {:role :assistant :content "a" :id 7}]
             :memory-snippets []})))))

(deftest assemble-empty-yields-no-messages-test
  (testing "no identity, conversation, or memory => empty vector (no system msg)"
    (is (= [] (prompt/assemble {:identity {} :conversation [] :memory-snippets []})))))
