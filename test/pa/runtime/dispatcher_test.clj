(ns pa.runtime.dispatcher-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [integrant.core :as ig]
            [pa.runtime.dispatcher]
            [pa.runtime.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Fixture: save and restore handler registry between tests
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [f]
    (let [before (registry/snapshot)]
      (f)
      (registry/restore! before))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- start-dispatcher []
  (ig/init-key :pa.runtime/dispatcher {:config {:env :test}}))

(defn- stop-dispatcher [component]
  (ig/halt-key! :pa.runtime/dispatcher component))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest dispatcher-starts-and-stops
  (testing "init-key returns a map with :channel and :dispatch!"
    (let [d (start-dispatcher)]
      (try
        (is (map? d))
        (is (some? (:channel d)))
        (is (fn? (:dispatch! d)))
        (finally (stop-dispatcher d))))))

(deftest dispatch-routes-to-registered-handler
  (testing "handler receives coeffect map; event payload accessible via :event key"
    (let [d        (start-dispatcher)
          received (atom nil)
          _        (registry/reg-handler :test/ping
                     (fn [coeffects] (reset! received coeffects)))]
      (try
        ((:dispatch! d) {:event/type :test/ping :payload 42})
        (Thread/sleep 50)
        (is (some? @received))
        (is (= :test/ping (get-in @received [:event :event/type])))
        (is (= 42 (get-in @received [:event :payload])))
        (finally (stop-dispatcher d))))))

(deftest dispatch-stamps-id-and-timestamp
  (testing "dispatched event has :event/id and :event/timestamp stamped"
    (let [d        (start-dispatcher)
          received (atom nil)
          _        (registry/reg-handler :test/stamp
                     (fn [coeffects] (reset! received coeffects)))]
      (try
        ((:dispatch! d) {:event/type :test/stamp})
        (Thread/sleep 50)
        (is (uuid? (get-in @received [:event :event/id])))
        (is (inst? (get-in @received [:event :event/timestamp])))
        (finally (stop-dispatcher d))))))

(deftest dispatch-unknown-event-type-no-ops
  (testing "dispatching an event with no registered handler does not throw"
    (let [d (start-dispatcher)]
      (try
        (is ((:dispatch! d) {:event/type :test/unknown-event-type}))
        (finally (stop-dispatcher d))))))

(deftest dispatcher-channel-closed-on-halt
  (testing "channel is closed after halt"
    (let [d  (start-dispatcher)
          ch (:channel d)]
      (stop-dispatcher d)
      (is (nil? (async/poll! ch))))))
