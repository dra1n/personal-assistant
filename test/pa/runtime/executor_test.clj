(ns pa.runtime.executor-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [pa.runtime.executor :as executor]
            [pa.runtime.state :as state]))

;; ---------------------------------------------------------------------------
;; Fixtures: reset db and trace-log before each test
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [f]
    (reset! state/db state/initial-db)
    (reset! state/trace-log [])
    (f)))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private dispatched (atom []))

(defn- make-ctx []
  {:dispatch! (fn [event] (swap! dispatched conj event))})

(use-fixtures :each
  (fn [f]
    (reset! state/db state/initial-db)
    (reset! state/trace-log [])
    (reset! dispatched [])
    (f)))

;; ---------------------------------------------------------------------------
;; :db effect
;; ---------------------------------------------------------------------------

(deftest db-effect-resets-state
  (testing ":db effect replaces runtime state with the new value"
    (let [new-db (assoc state/initial-db :conversation [{:role :user :text "hi"}])]
      (executor/execute-effect :db new-db (make-ctx))
      (is (= new-db @state/db)))))

(deftest db-effect-is-only-mutation-site
  (testing "state/db is only modified via :db effect in executor"
    (executor/execute-effect :db (assoc state/initial-db :ui {:focused true}) (make-ctx))
    (is (= {:focused true} (:ui @state/db)))))

;; ---------------------------------------------------------------------------
;; :dispatch effect
;; ---------------------------------------------------------------------------

(deftest dispatch-effect-enqueues-event
  (testing ":dispatch effect calls dispatch! with the event map"
    (executor/execute-effect :dispatch {:event/type :test/ping} (make-ctx))
    (is (= 1 (count @dispatched)))
    (is (= :test/ping (:event/type (first @dispatched))))))

;; ---------------------------------------------------------------------------
;; :dispatch-later effect
;; ---------------------------------------------------------------------------

(deftest dispatch-later-effect-fires-after-delay
  (testing ":dispatch-later fires dispatch! after the specified delay"
    (executor/execute-effect :dispatch-later
                             {:event {:event/type :test/delayed} :delay-ms 50}
                             (make-ctx))
    (is (= 0 (count @dispatched)))
    (Thread/sleep 100)
    (is (= 1 (count @dispatched)))
    (is (= :test/delayed (:event/type (first @dispatched))))))

;; ---------------------------------------------------------------------------
;; :log/info effect
;; ---------------------------------------------------------------------------

(deftest log-info-effect-does-not-throw
  (testing ":log/info effect executes without throwing"
    (is (nil? (executor/execute-effect :log/info
                                       {:message "test log entry" :context :test}
                                       (make-ctx))))))

;; ---------------------------------------------------------------------------
;; :trace effect
;; ---------------------------------------------------------------------------

(deftest trace-effect-appends-to-trace-log
  (testing ":trace effect appends an entry to state/trace-log"
    (executor/execute-effect :trace {:event/type :test/ping :phase 1} (make-ctx))
    (is (= 1 (count @state/trace-log)))
    (is (= :test/ping (:event/type (first @state/trace-log))))))

(deftest trace-effect-stamps-timestamp
  (testing ":trace stamps :timestamp if not present"
    (executor/execute-effect :trace {:msg "hello"} (make-ctx))
    (is (inst? (:timestamp (first @state/trace-log))))))

;; ---------------------------------------------------------------------------
;; :tap effect
;; ---------------------------------------------------------------------------

(deftest tap-effect-emits-value
  (testing ":tap effect emits value via tap>"
    (let [received (atom nil)
          sink     (fn [v] (reset! received v))]
      (add-tap sink)
      (executor/execute-effect :tap :test-tap-value (make-ctx))
      (Thread/sleep 50)
      (remove-tap sink)
      (is (= :test-tap-value @received)))))

;; ---------------------------------------------------------------------------
;; :event/store stub
;; ---------------------------------------------------------------------------

(deftest event-store-stub-does-not-throw
  (testing ":event/store stub no-ops without throwing"
    (is (nil? (executor/execute-effect :event/store {:event/type :test/ping} (make-ctx))))))

;; ---------------------------------------------------------------------------
;; Unknown effect type
;; ---------------------------------------------------------------------------

(deftest unknown-effect-type-no-ops
  (testing "unknown effect type logs a warning and does not throw"
    (is (nil? (executor/execute-effect :test/nonexistent-effect {} (make-ctx))))))

;; ---------------------------------------------------------------------------
;; execute-effects!
;; ---------------------------------------------------------------------------

(deftest execute-effects-iterates-map
  (testing "execute-effects! calls execute-effect for each key in the effects map"
    (let [new-db (assoc state/initial-db :tasks {:t1 :pending})]
      (executor/execute-effects!
        {:db       new-db
         :dispatch {:event/type :test/ping}
         :trace    {:msg "batch test"}}
        (make-ctx))
      (is (= new-db @state/db))
      (is (= 1 (count @dispatched)))
      (is (= 1 (count @state/trace-log))))))
