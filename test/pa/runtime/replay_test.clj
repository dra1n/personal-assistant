(ns pa.runtime.replay-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.runtime.events :as events]
            [pa.runtime.registry :as registry]
            [pa.runtime.replay :as replay]
            [pa.state.db :as db]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [f]
    (reset! db/db db/initial-db)
    (reset! db/trace-log [])
    (let [before (registry/snapshot)]
      (f)
      (registry/restore! before))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-event [type payload]
  (events/make-event (merge {:event/type type} payload)))

;; ---------------------------------------------------------------------------
;; Basic replay
;; ---------------------------------------------------------------------------

(deftest replay-reconstructs-state-from-event-sequence
  (testing "replay applies :db effects in order and returns final state"
    (registry/reg-handler :test/add-message
      (fn [{:keys [db event]}]
        {:db (update db :conversation conj {:text (:text event)})}))
    (let [evts   [(make-event :test/add-message {:text "hello"})
                  (make-event :test/add-message {:text "world"})]
          result (replay/replay db/initial-db evts)]
      (is (= [{:text "hello"} {:text "world"}]
             (:conversation result))))))

(deftest replay-does-not-mutate-live-state
  (testing "replay leaves db/db untouched"
    (registry/reg-handler :test/set-flag
      (fn [{:keys [db]}]
        {:db (assoc db :replayed true)}))
    (let [evts [(make-event :test/set-flag {})]]
      (replay/replay db/initial-db evts)
      (is (nil? (:replayed @db/db))))))

;; ---------------------------------------------------------------------------
;; Determinism
;; ---------------------------------------------------------------------------

(deftest replay-is-deterministic
  (testing "same event sequence replayed twice produces identical results"
    (registry/reg-handler :test/counter
      (fn [{:keys [db]}]
        {:db (update db :ui assoc :count (inc (get-in db [:ui :count] 0)))}))
    (let [evts   [(make-event :test/counter {})
                  (make-event :test/counter {})
                  (make-event :test/counter {})]
          first  (replay/replay db/initial-db evts)
          second (replay/replay db/initial-db evts)]
      (is (= first second))
      (is (= 3 (get-in first [:ui :count]))))))

;; ---------------------------------------------------------------------------
;; Non-deterministic effects are skipped
;; ---------------------------------------------------------------------------

(deftest replay-skips-dispatch-effects
  (testing ":dispatch effects do not fire during replay"
    (let [dispatched (atom 0)]
      (registry/reg-handler :test/with-dispatch
        (fn [{:keys [db]}]
          {:db       (assoc db :processed true)
           :dispatch {:event/type :test/should-not-run}}))
      (registry/reg-handler :test/should-not-run
        (fn [{:keys [db]}]
          (swap! dispatched inc)
          {:db db}))
      (let [evts [(make-event :test/with-dispatch {})]]
        (replay/replay db/initial-db evts)
        (is (= 0 @dispatched))))))

(deftest replay-skips-log-and-tap-effects
  (testing ":log/info and :tap effects do not fire during replay"
    (let [tapped (atom [])]
      (add-tap (fn [v] (swap! tapped conj v)))
      (registry/reg-handler :test/with-side-effects
        (fn [{:keys [db]}]
          {:db       (assoc db :done true)
           :log/info {:message "should not log in replay"}
           :tap      :should-not-tap}))
      (let [evts [(make-event :test/with-side-effects {})]]
        (replay/replay db/initial-db evts)
        (Thread/sleep 50)
        (remove-tap (fn [_]))
        (is (empty? @tapped))))))

;; ---------------------------------------------------------------------------
;; Replay respects per-event :db coeffect
;; ---------------------------------------------------------------------------

(deftest replay-threads-state-across-events
  (testing "each event in replay sees the state produced by the previous event"
    (registry/reg-handler :test/append
      (fn [{:keys [db event]}]
        {:db (update db :tasks assoc (:key event) true)}))
    (let [evts   [(make-event :test/append {:key :a})
                  (make-event :test/append {:key :b})
                  (make-event :test/append {:key :c})]
          result (replay/replay db/initial-db evts)]
      (is (= {:a true :b true :c true} (:tasks result))))))

;; ---------------------------------------------------------------------------
;; Custom initial state
;; ---------------------------------------------------------------------------

(deftest replay-accepts-custom-initial-state
  (testing "replay starts from the provided initial-db, not db/initial-db"
    (registry/reg-handler :test/noop (fn [{:keys [db]}] {:db db}))
    (let [custom-db (assoc db/initial-db :conversation [{:text "seed"}])
          evts      [(make-event :test/noop {})]
          result    (replay/replay custom-db evts)]
      (is (= [{:text "seed"}] (:conversation result))))))
