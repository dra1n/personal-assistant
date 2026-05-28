(ns pa.runtime.coeffects-test
  (:require [clojure.test :refer [deftest is testing]]
            [pa.runtime.coeffects :as coeffects]
            [pa.runtime.events :as events])
  (:import [java.time Instant]))

(def ^:private test-dispatch! (fn [_] nil))

(def ^:private test-system-context
  {:config  {:env :test}
   :runtime {:dispatch! test-dispatch!}})

(deftest inject-coeffects-contains-all-keys
  (testing "returns a map with all five required keys"
    (let [event  (events/make-event {:event/type :test/ping})
          result (coeffects/inject-coeffects event test-system-context)]
      (is (contains? result :db))
      (is (contains? result :now))
      (is (contains? result :config))
      (is (contains? result :runtime))
      (is (contains? result :event)))))

(deftest inject-coeffects-event-key
  (testing ":event is the triggering event"
    (let [event  (events/make-event {:event/type :test/ping :text "hello"})
          result (coeffects/inject-coeffects event test-system-context)]
      (is (= event (:event result))))))

(deftest inject-coeffects-now-key
  (testing ":now is a java.time.Instant"
    (let [before (Instant/now)
          result (coeffects/inject-coeffects
                   (events/make-event {:event/type :test/ping})
                   test-system-context)
          after  (Instant/now)]
      (is (inst? (:now result)))
      (is (not (.isAfter before (:now result))))
      (is (not (.isAfter (:now result) after))))))

(deftest inject-coeffects-config-key
  (testing ":config matches the config from system-context"
    (let [result (coeffects/inject-coeffects
                   (events/make-event {:event/type :test/ping})
                   test-system-context)]
      (is (= {:env :test} (:config result))))))

(deftest inject-coeffects-runtime-key
  (testing ":runtime contains :dispatch! fn"
    (let [result (coeffects/inject-coeffects
                   (events/make-event {:event/type :test/ping})
                   test-system-context)]
      (is (fn? (get-in result [:runtime :dispatch!]))))))

(deftest inject-coeffects-db-key
  (testing ":db is a map (current runtime state snapshot)"
    (let [result (coeffects/inject-coeffects
                   (events/make-event {:event/type :test/ping})
                   test-system-context)]
      (is (map? (:db result))))))
