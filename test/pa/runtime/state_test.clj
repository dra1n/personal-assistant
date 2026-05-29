(ns pa.runtime.state-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [pa.runtime.dispatcher]
            [pa.runtime.executor :as executor]
            [pa.runtime.registry :as registry]
            [pa.runtime.state :as state]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [f]
    (reset! state/db state/initial-db)
    (reset! state/trace-log [])
    (let [before (registry/snapshot)]
      (f)
      (registry/restore! before))))

;; ---------------------------------------------------------------------------
;; Initial state shape
;; ---------------------------------------------------------------------------

(deftest initial-db-shape
  (testing "initial-db contains all required keys with correct empty values"
    (is (= [] (:conversation state/initial-db)))
    (is (= {} (:tasks state/initial-db)))
    (is (= [] (:events/recent state/initial-db)))
    (is (= {} (:ui state/initial-db)))))

(deftest db-atom-starts-at-initial
  (testing "db atom starts equal to initial-db after fixture reset"
    (is (= state/initial-db @state/db))))

;; ---------------------------------------------------------------------------
;; State only changes via :db effect
;; ---------------------------------------------------------------------------

(deftest db-effect-is-the-transition-mechanism
  (testing ":db effect correctly updates runtime state"
    (let [new-db (assoc state/initial-db :conversation [{:role :user :text "hi"}])]
      (executor/execute-effect :db new-db {})
      (is (= new-db @state/db)))))

;; ---------------------------------------------------------------------------
;; :events/recent accumulation
;; ---------------------------------------------------------------------------

(deftest events-recent-grows-per-dispatch
  (testing ":events/recent grows by one entry per dispatched event"
    (let [d (ig/init-key :pa.runtime/dispatcher {:config {:env :test}})]
      (try
        (registry/reg-handler :test/ping (fn [_] {}))
        ((:dispatch! d) {:event/type :test/ping})
        (Thread/sleep 50)
        (is (= 1 (count (:events/recent @state/db))))
        ((:dispatch! d) {:event/type :test/ping})
        (Thread/sleep 50)
        (is (= 2 (count (:events/recent @state/db))))
        (finally (ig/halt-key! :pa.runtime/dispatcher d))))))

(deftest events-recent-contains-stamped-events
  (testing "accumulated events in :events/recent have :event/id and :event/timestamp"
    (let [d (ig/init-key :pa.runtime/dispatcher {:config {:env :test}})]
      (try
        (registry/reg-handler :test/stamp (fn [_] {}))
        ((:dispatch! d) {:event/type :test/stamp})
        (Thread/sleep 50)
        (let [event (first (:events/recent @state/db))]
          (is (= :test/stamp (:event/type event)))
          (is (uuid? (:event/id event)))
          (is (inst? (:event/timestamp event))))
        (finally (ig/halt-key! :pa.runtime/dispatcher d))))))

(deftest events-recent-accumulates-across-types
  (testing ":events/recent accumulates events regardless of handler existence"
    (let [d (ig/init-key :pa.runtime/dispatcher {:config {:env :test}})]
      (try
        ((:dispatch! d) {:event/type :test/no-handler-here})
        (Thread/sleep 50)
        (is (= 1 (count (:events/recent @state/db))))
        (finally (ig/halt-key! :pa.runtime/dispatcher d))))))
