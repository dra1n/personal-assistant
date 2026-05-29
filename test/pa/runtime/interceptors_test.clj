(ns pa.runtime.interceptors-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [pa.runtime.dispatcher]
            [pa.runtime.interceptors :as interceptors]
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
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-system-context []
  {:config  {:env :test}
   :runtime {:dispatch! (fn [_])}})

(defn- fixture-interceptor [log-atom tag]
  {:before (fn [ctx] (swap! log-atom conj [:before tag]) ctx)
   :after  (fn [ctx] (swap! log-atom conj [:after tag]) ctx)})

;; ---------------------------------------------------------------------------
;; Chain runner — ordering
;; ---------------------------------------------------------------------------

(deftest chain-runner-applies-before-in-order
  (testing ":before fns are applied left-to-right"
    (let [log  (atom [])
          a    (fixture-interceptor log :a)
          b    (fixture-interceptor log :b)
          c    (fixture-interceptor log :c)
          ctx  {:x 0}
          _    (interceptors/run-chain [a b c] ctx)]
      (is (= [[:before :a] [:before :b] [:before :c]
              [:after :c]  [:after :b]  [:after :a]]
             @log)))))

(deftest chain-runner-applies-after-in-reverse
  (testing ":after fns are applied right-to-left"
    (let [order (atom [])
          a {:before nil :after (fn [ctx] (swap! order conj :a) ctx)}
          b {:before nil :after (fn [ctx] (swap! order conj :b) ctx)}
          c {:before nil :after (fn [ctx] (swap! order conj :c) ctx)}]
      (interceptors/run-chain [a b c] {})
      (is (= [:c :b :a] @order)))))

(deftest chain-runner-threads-context
  (testing "each interceptor receives and returns the context map"
    (let [chain [{:before (fn [ctx] (assoc ctx :step1 true)) :after nil}
                 {:before (fn [ctx] (assoc ctx :step2 true)) :after nil}]
          result (interceptors/run-chain chain {})]
      (is (true? (:step1 result)))
      (is (true? (:step2 result))))))

(deftest chain-runner-skips-nil-hooks
  (testing "nil :before or :after does not cause an error"
    (let [chain [{:before nil :after nil}
                 {:before (fn [ctx] (assoc ctx :ran true)) :after nil}]
          result (interceptors/run-chain chain {})]
      (is (true? (:ran result))))))

;; ---------------------------------------------------------------------------
;; Throwing interceptor short-circuits without corrupting state
;; ---------------------------------------------------------------------------

(deftest throwing-before-does-not-corrupt-state
  (testing "an exception in a :before interceptor propagates without mutating state"
    (let [chain [{:before (fn [_] (throw (ex-info "boom" {}))) :after nil}
                 {:before (fn [ctx] (assoc ctx :should-not-run true)) :after nil}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (interceptors/run-chain chain {})))
      (is (= state/initial-db @state/db)))))

;; ---------------------------------------------------------------------------
;; Standard chain — end-to-end integration
;; ---------------------------------------------------------------------------

(deftest standard-chain-updates-state
  (testing "dispatching through the standard chain updates runtime state"
    (let [sc     (make-system-context)
          event  {:event/type :test/greet :event/id (random-uuid) :event/timestamp (java.time.Instant/now)}
          _      (registry/reg-handler :test/greet
                   (fn [{:keys [db]}]
                     {:db (assoc db :greeted true)}))]
      (interceptors/run-standard-chain event sc)
      (is (true? (:greeted @state/db))))))

(deftest standard-chain-injects-coeffects
  (testing "handler receives all five coeffect keys"
    (let [sc       (make-system-context)
          received (atom nil)
          event    {:event/type :test/inspect :event/id (random-uuid) :event/timestamp (java.time.Instant/now)}
          _        (registry/reg-handler :test/inspect
                     (fn [coeffects]
                       (reset! received coeffects)
                       nil))]
      (interceptors/run-standard-chain event sc)
      (is (some? @received))
      (is (map? (:db @received)))
      (is (inst? (:now @received)))
      (is (map? (:config @received)))
      (is (map? (:runtime @received)))
      (is (= event (:event @received))))))

(deftest standard-chain-traces-event
  (testing "tracing interceptor appends an entry to state/trace-log"
    (let [sc    (make-system-context)
          event {:event/type :test/traced :event/id (random-uuid) :event/timestamp (java.time.Instant/now)}
          _     (registry/reg-handler :test/traced (fn [_] nil))]
      (interceptors/run-standard-chain event sc)
      (let [entries (filter #(= :test/traced (:trace/event-type %)) @state/trace-log)]
        (is (seq entries))
        (is (inst? (:trace/entered-at (first entries))))
        (is (inst? (:trace/exited-at (first entries))))
        (is (number? (:trace/elapsed-ms (first entries))))))))

(deftest standard-chain-no-handler-does-not-throw
  (testing "event with no registered handler runs chain without error"
    (let [sc    (make-system-context)
          event {:event/type :test/no-handler :event/id (random-uuid) :event/timestamp (java.time.Instant/now)}]
      (is (map? (interceptors/run-standard-chain event sc))))))

;; ---------------------------------------------------------------------------
;; Per-handler interceptors
;; ---------------------------------------------------------------------------

(deftest per-handler-interceptor-preserves-base-coeffects
  (testing "base coeffects (:db :now :config :runtime :event) are still present when a per-handler interceptor is attached"
    (let [sc       (make-system-context)
          received (atom nil)
          event    {:event/type :test/preserve-base :event/id (random-uuid) :event/timestamp (java.time.Instant/now)}
          extra-ic {:before (fn [ctx] (update ctx :coeffects assoc :extra/value 42))
                    :after  nil}
          _        (registry/reg-handler :test/preserve-base
                     [extra-ic]
                     (fn [coeffects]
                       (reset! received coeffects)
                       nil))]
      (interceptors/run-standard-chain event sc)
      (is (map?  (:db @received)))
      (is (inst? (:now @received)))
      (is (map?  (:config @received)))
      (is (map?  (:runtime @received)))
      (is (= event (:event @received)))
      (is (= 42   (:extra/value @received))))))

(deftest per-handler-interceptor-injects-extra-coeffect
  (testing "interceptors passed at reg-handler time can add keys to :coeffects"
    (let [sc       (make-system-context)
          received (atom nil)
          event    {:event/type :test/with-extra :event/id (random-uuid) :event/timestamp (java.time.Instant/now)}
          extra-ic {:before (fn [ctx] (update ctx :coeffects assoc :extra/value 42))
                    :after  nil}
          _        (registry/reg-handler :test/with-extra
                     [extra-ic]
                     (fn [coeffects]
                       (reset! received coeffects)
                       nil))]
      (interceptors/run-standard-chain event sc)
      (is (= 42 (:extra/value @received))))))

(deftest per-handler-interceptor-does-not-affect-other-handlers
  (testing "extra coeffects from one handler's interceptors are not visible in another"
    (let [sc        (make-system-context)
          received  (atom nil)
          event-a   {:event/type :test/with-extra-a :event/id (random-uuid) :event/timestamp (java.time.Instant/now)}
          event-b   {:event/type :test/without-extra :event/id (random-uuid) :event/timestamp (java.time.Instant/now)}
          extra-ic  {:before (fn [ctx] (update ctx :coeffects assoc :extra/value 99))
                     :after  nil}
          _         (registry/reg-handler :test/with-extra-a [extra-ic] (fn [_] nil))
          _         (registry/reg-handler :test/without-extra
                      (fn [coeffects]
                        (reset! received coeffects)
                        nil))]
      (interceptors/run-standard-chain event-a sc)
      (interceptors/run-standard-chain event-b sc)
      (is (nil? (:extra/value @received))))))

(deftest per-handler-interceptor-after-fn-runs-in-reverse
  (testing "per-handler :after fns run right-to-left within the sub-chain"
    (let [sc    (make-system-context)
          order (atom [])
          event {:event/type :test/order-check :event/id (random-uuid) :event/timestamp (java.time.Instant/now)}
          ic-a  {:before nil :after (fn [ctx] (swap! order conj :a) ctx)}
          ic-b  {:before nil :after (fn [ctx] (swap! order conj :b) ctx)}
          _     (registry/reg-handler :test/order-check [ic-a ic-b] (fn [_] nil))]
      (interceptors/run-standard-chain event sc)
      (is (= [:b :a] @order)))))

;; ---------------------------------------------------------------------------
;; Integration via the dispatcher
;; ---------------------------------------------------------------------------

(defn- start-dispatcher []
  (ig/init-key :pa.runtime/dispatcher {:config {:env :test}}))

(defn- stop-dispatcher [d]
  (ig/halt-key! :pa.runtime/dispatcher d))

(deftest dispatcher-uses-interceptor-chain
  (testing "dispatching an event end-to-end updates state and appears in :events/recent"
    (let [d   (start-dispatcher)
          _   (registry/reg-handler :test/chain-smoke
                (fn [{:keys [db]}]
                  {:db (assoc db :smoke-passed true)}))]
      (try
        ((:dispatch! d) {:event/type :test/chain-smoke})
        (Thread/sleep 50)
        (is (true? (:smoke-passed @state/db)))
        (is (= 1 (count (:events/recent @state/db))))
        (finally (stop-dispatcher d))))))
