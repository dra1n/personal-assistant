(ns pa.runtime.dispatcher-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [integrant.core :as ig]
            [pa.runtime.dispatcher]
            [pa.runtime.registry :as registry]
            [pa.state.db :as db]))

;; ---------------------------------------------------------------------------
;; Fixture: save/restore the handler registry and db/db between tests
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [f]
    (let [before (registry/snapshot)]
      (reset! db/db db/initial-db)
      (f)
      (reset! db/db db/initial-db)
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

(deftest throwing-handler-does-not-kill-the-go-loop
  (testing "an exception in one handler is logged and dropped; the consumer
            loop keeps processing later events (so shutdown can't hang waiting
            on an event that never gets consumed)"
    (let [d        (start-dispatcher)
          received (atom nil)
          _        (registry/reg-handler :test/boom
                     (fn [_] (throw (ex-info "kaboom" {}))))
          _        (registry/reg-handler :test/after
                     (fn [coeffects] (reset! received coeffects)))]
      (try
        ((:dispatch! d) {:event/type :test/boom})
        ((:dispatch! d) {:event/type :test/after :payload 7})
        (Thread/sleep 50)
        (is (some? @received) "the event after the throwing one was still processed")
        (is (= 7 (get-in @received [:event :payload])))
        (finally (stop-dispatcher d))))))

(deftest dispatcher-channel-closed-on-halt
  (testing "channel is closed after halt"
    (let [d  (start-dispatcher)
          ch (:channel d)]
      (stop-dispatcher d)
      (is (nil? (async/poll! ch))))))

;; ---------------------------------------------------------------------------
;; Shutdown-extraction fallback (see pa.ui.app's :app/quit-requested / ctrl+c
;; for the primary, UI-driven path this backstops)
;; ---------------------------------------------------------------------------

(deftest halt-key-runs-extraction-when-not-already-quit-ready
  (testing "dispatches :extraction/run as a fallback when no ctrl+c quit ran it first"
    (let [d        (start-dispatcher)
          received (atom [])
          ;; Stand in for the real handler: record the call and still deliver
          ;; :done so halt-key!'s deref doesn't block for its full timeout.
          _        (registry/reg-handler :extraction/run
                     (fn [{:keys [event]}]
                       (swap! received conj event)
                       {:dispatch {:event/type :extraction/done :done (:done event)}}))]
      (stop-dispatcher d)
      (is (= 1 (count @received))))))

(deftest halt-key-skips-extraction-when-already-quit-ready
  (testing "does not re-dispatch :extraction/run when a prior ctrl+c quit already ran it
            (re-running would duplicate LLM calls and memory/wisdom writes)"
    (let [d        (start-dispatcher)
          received (atom [])
          _        (registry/reg-handler :extraction/run
                     (fn [{:keys [event]}]
                       (swap! received conj event)
                       {:dispatch {:event/type :extraction/done :done (:done event)}}))]
      (reset! db/db (assoc db/initial-db :app/quit-ready? true))
      (stop-dispatcher d)
      (is (empty? @received)))))
