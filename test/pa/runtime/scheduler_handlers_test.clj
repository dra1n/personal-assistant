(ns pa.runtime.scheduler-handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [pa.runtime.handlers]
            [pa.runtime.registry :as registry]
            [pa.state.db :as db]))

(defn- handler [event-type]
  (:fn (registry/get-handler event-type)))

(defn- base-db [] db/initial-db)

;; ---------------------------------------------------------------------------
;; :notifications/clear — pa.runtime.handlers
;; ---------------------------------------------------------------------------

(deftest notifications-clear-empties-list
  (testing ":notifications/clear removes all notifications"
    (let [db-with-notifs (-> (base-db)
                             (assoc :ui/notifications [{:id "a" :type :reminder :payload {}}
                                                       {:id "b" :type :reminder :payload {}}]))
          fx ((handler :notifications/clear) {:db db-with-notifs})]
      (is (= [] (get-in fx [:db :ui/notifications]))))))

(deftest notifications-clear-on-empty-is-safe
  (testing ":notifications/clear on an empty list does not throw"
    (let [fx ((handler :notifications/clear) {:db (base-db)})]
      (is (= [] (get-in fx [:db :ui/notifications]))))))

;; ---------------------------------------------------------------------------
;; :notification/dismiss — pa.runtime.handlers
;; ---------------------------------------------------------------------------

(deftest notification-dismiss-removes-matching
  (testing ":notification/dismiss removes only the notification with the given id"
    (let [db (assoc (base-db) :ui/notifications
                    [{:id "keep" :type :reminder :payload {}}
                     {:id "gone" :type :reminder :payload {}}])
          event {:event/type      :notification/dismiss
                 :notification/id "gone"}
          fx    ((handler :notification/dismiss) {:db db :event event})]
      (is (= 1 (count (get-in fx [:db :ui/notifications]))))
      (is (= "keep" (get-in fx [:db :ui/notifications 0 :id]))))))

(deftest notification-dismiss-unknown-id-is-safe
  (testing ":notification/dismiss with an unknown id leaves the list unchanged"
    (let [notifs [{:id "a" :type :reminder :payload {}}]
          db     (assoc (base-db) :ui/notifications notifs)
          event  {:event/type :notification/dismiss :notification/id "missing"}
          fx     ((handler :notification/dismiss) {:db db :event event})]
      (is (= notifs (get-in fx [:db :ui/notifications]))))))
