(ns pa.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [pa.config :as config]
            [pa.logging]
            [pa.observability]
            [pa.runtime]
            [pa.ui]))

(defn- start-test-system []
  (let [cfg (-> (config/system-config)
                (dissoc :pa.observability/portal)  ; skip Portal in CI
                (dissoc :pa.ui/terminal))]          ; skip TTY output in CI
    (ig/init cfg)))

(deftest system-starts
  (testing "all Integrant components initialize without throwing"
    (let [sys (start-test-system)]
      (is (map? sys))
      (is (contains? sys :pa.logging/timbre))
      (is (contains? sys :pa.runtime/event-bus))
      (ig/halt! sys))))

(deftest system-halts
  (testing "all components halt cleanly"
    (let [sys (start-test-system)]
      (is (nil? (ig/halt! sys))))))

(deftest event-bus-lifecycle
  (testing "event bus channel is open after init and closed after halt"
    (let [sys   (start-test-system)
          bus   (:pa.runtime/event-bus sys)
          ch    (:channel bus)]
      (is (some? ch))
      (ig/halt! sys))))

(deftest tap-sink
  (testing "tap> values are received by a registered sink"
    (let [received (atom [])
          sink     (fn [v] (swap! received conj v))]
      (add-tap sink)
      (tap> :hello-phase-0)
      (Thread/sleep 50)
      (remove-tap sink)
      (is (= [:hello-phase-0] @received)))))
