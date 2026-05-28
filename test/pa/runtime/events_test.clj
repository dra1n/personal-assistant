(ns pa.runtime.events-test
  (:require [clojure.test :refer [deftest is testing]]
            [pa.runtime.events :as events])
  (:import [java.util UUID]
           [java.time Instant]))

(deftest make-event-stamps-required-keys
  (testing "stamps :event/id as a UUID"
    (let [e (events/make-event {:event/type :user/message :text "hi"})]
      (is (uuid? (:event/id e)))))

  (testing "stamps :event/timestamp as an Instant"
    (let [e (events/make-event {:event/type :user/message :text "hi"})]
      (is (inst? (:event/timestamp e)))))

  (testing "preserves :event/type"
    (let [e (events/make-event {:event/type :scheduler/tick})]
      (is (= :scheduler/tick (:event/type e)))))

  (testing "preserves arbitrary payload keys"
    (let [e (events/make-event {:event/type :user/message :text "hello" :source :terminal})]
      (is (= "hello" (:text e)))
      (is (= :terminal (:source e)))))

  (testing "caller-supplied :event/id is preserved"
    (let [fixed-id (UUID/randomUUID)
          e        (events/make-event {:event/type :user/message :event/id fixed-id})]
      (is (= fixed-id (:event/id e)))))

  (testing "caller-supplied :event/timestamp is preserved"
    (let [fixed-ts (Instant/parse "2026-01-01T00:00:00Z")
          e        (events/make-event {:event/type :user/message :event/timestamp fixed-ts})]
      (is (= fixed-ts (:event/timestamp e)))))

  (testing "each call produces a distinct :event/id"
    (let [e1 (events/make-event {:event/type :user/message})
          e2 (events/make-event {:event/type :user/message})]
      (is (not= (:event/id e1) (:event/id e2))))))

(deftest make-event-requires-qualified-event-type
  (testing "throws on missing :event/type"
    (is (thrown? AssertionError (events/make-event {}))))

  (testing "throws on unqualified :event/type"
    (is (thrown? AssertionError (events/make-event {:event/type :message})))))
