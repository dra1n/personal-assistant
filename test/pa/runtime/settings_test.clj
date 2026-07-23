(ns pa.runtime.settings-test
  (:require [clojure.test :refer [deftest is testing]]
            [pa.runtime.handlers]                 ; registers the settings handler
            [pa.runtime.registry :as registry]
            [pa.state.db :as db]
            [pa.state.queries :as queries]))

(defn- handler [event-type]
  (:fn (registry/get-handler event-type)))

(deftest settings-set-handler-writes-via-db-effect
  (testing ":settings/set applies set-setting and returns it as a :db effect only"
    (let [fx ((handler :settings/set)
              {:db db/initial-db :event {:event/type :settings/set :key :markdown :value true}})]
      (is (true? (queries/setting (:db fx) :markdown))
          "the returned :db has the setting applied")
      (is (= :settings/set (get-in fx [:trace :event/type])))
      (is (not (contains? fx :event/store))
          "settings are in-session only this phase — not persisted"))))

(deftest settings-round-trip-flips-value
  (testing "a second :settings/set flips the value back, read via queries/setting"
    (let [on   ((handler :settings/set)
                {:db db/initial-db :event {:event/type :settings/set :key :markdown :value true}})
          off  ((handler :settings/set)
                {:db (:db on) :event {:event/type :settings/set :key :markdown :value false}})]
      (is (true? (queries/setting (:db on) :markdown)))
      (is (false? (queries/setting (:db off) :markdown))))))
