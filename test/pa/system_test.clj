(ns pa.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [pa.config :as config]
            [pa.logging]
            [pa.observability]
            [pa.runtime.dispatcher]
            [pa.runtime.handlers]
            [pa.storage.events]
            [pa.storage.fs]
            [pa.storage.history]
            [pa.storage.identity]
            [pa.storage.memory-store]
            [pa.db.sqlite]
            [pa.memory.indexer]
            [pa.llm.component]
            [pa.tools.fs.policy]
            [pa.ui.core]))

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
      (is (contains? sys :pa.runtime/dispatcher))
      (ig/halt! sys))))

(deftest system-halts
  (testing "all components halt cleanly"
    (let [sys (start-test-system)]
      (is (nil? (ig/halt! sys))))))

(deftest event-bus-lifecycle
  (testing "dispatcher channel is open after init"
    (let [sys (start-test-system)
          ch  (get-in sys [:pa.runtime/dispatcher :channel])]
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
