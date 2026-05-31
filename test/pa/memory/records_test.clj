(ns pa.memory.records-test
  (:require [clojure.test :refer [deftest is testing]]
            [pa.memory.records :as records]))

(deftest make-stamps-id-and-timestamp
  (testing "make generates :memory/id and :memory/created-at"
    (let [r (records/make {:memory/type    :episodic
                           :memory/title   "Test event"
                           :memory/summary "Something happened."})]
      (is (string? (:memory/id r)))
      (is (inst? (:memory/created-at r))))))

(deftest make-preserves-supplied-fields
  (testing "make keeps all caller-supplied fields"
    (let [r (records/make {:memory/type    :semantic
                           :memory/title   "A fact"
                           :memory/summary "Water is wet."
                           :memory/tags    ["science"]})]
      (is (= :semantic      (:memory/type r)))
      (is (= "A fact"       (:memory/title r)))
      (is (= "Water is wet." (:memory/summary r)))
      (is (= ["science"]    (:memory/tags r))))))

(deftest make-ids-are-unique
  (testing "each call to make produces a distinct :memory/id"
    (let [base {:memory/type :fact :memory/title "x" :memory/summary "y"}
          ids  (repeatedly 5 #(:memory/id (records/make base)))]
      (is (= 5 (count (set ids)))))))

(deftest make-requires-valid-type
  (testing "make throws on an unrecognised :memory/type"
    (is (thrown? clojure.lang.ExceptionInfo
          (records/make {:memory/type    :unknown
                         :memory/title   "x"
                         :memory/summary "y"})))))
