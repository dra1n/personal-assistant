(ns pa.runtime.scheduler-handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [pa.runtime.handlers]
            [pa.scheduler.handlers]
            [pa.runtime.registry :as registry]
            [pa.state.db :as db]))

(defn- handler [event-type]
  (:fn (registry/get-handler event-type)))

(defn- base-db [] db/initial-db)

;; ---------------------------------------------------------------------------
;; :reminder/due
;; ---------------------------------------------------------------------------

(deftest reminder-due-adds-notification
  (testing "first reminder adds a notification to :ui/notifications"
    (let [event {:event/type   :reminder/due
                 :task/id      "task-1"
                 :task/payload {:text "Buy milk"}}
          fx    ((handler :reminder/due) {:db (base-db) :event event})]
      (is (= 1 (count (get-in fx [:db :ui/notifications]))))
      (let [n (first (get-in fx [:db :ui/notifications]))]
        (is (= "task-1" (:id n)))
        (is (= :reminder (:type n)))
        (is (= {:text "Buy milk"} (:payload n)))))))

(deftest reminder-due-accumulates-notifications
  (testing "multiple reminders accumulate rather than overwrite"
    (let [fire! (fn [db task-id text]
                  (let [event {:event/type   :reminder/due
                               :task/id      task-id
                               :task/payload {:text text}}]
                    (:db ((handler :reminder/due) {:db db :event event}))))
          db1   (fire! (base-db) "task-1" "Buy milk")
          db2   (fire! db1       "task-2" "Call dentist")]
      (is (= 2 (count (:ui/notifications db2))))
      (is (= #{"task-1" "task-2"} (set (map :id (:ui/notifications db2))))))))

(deftest reminder-due-emits-tap
  (testing ":reminder/due emits a :tap effect with the notification"
    (let [event {:event/type   :reminder/due
                 :task/id      "task-1"
                 :task/payload {:text "Stand up"}}
          fx    ((handler :reminder/due) {:db (base-db) :event event})]
      (is (contains? fx :tap))
      (is (= "task-1" (get-in fx [:tap :reminder/due :id]))))))

;; ---------------------------------------------------------------------------
;; :notifications/clear
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
;; :notification/dismiss
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

;; ---------------------------------------------------------------------------
;; Task lifecycle handlers
;; ---------------------------------------------------------------------------

(defn- task [id] {:task/id id :task/type :reminder/due :task/payload {} :task/fire-at 0})

(deftest tasks-loaded-sets-scheduled
  (testing ":tasks/loaded replaces :tasks/scheduled with the given list"
    (let [tasks [(task "a") (task "b")]
          fx    ((handler :tasks/loaded) {:db (base-db) :event {:tasks tasks}})]
      (is (= tasks (get-in fx [:db :tasks/scheduled]))))))

(deftest task-advanced-updates-fire-at
  (testing ":task/advanced replaces the task in-place with the updated version"
    (let [original (assoc (task "r") :task/fire-at 1000)
          advanced (assoc original :task/fire-at 2000)
          db       (assoc (base-db) :tasks/scheduled [original])
          fx       ((handler :task/advanced) {:db db :event {:task advanced}})]
      (is (= 1 (count (get-in fx [:db :tasks/scheduled]))))
      (is (= 2000 (get-in fx [:db :tasks/scheduled 0 :task/fire-at]))))))

(deftest task-completed-removes-from-scheduled
  (testing ":task/completed removes the task just like cancel"
    (let [db (assoc (base-db) :tasks/scheduled [(task "done") (task "pending")])
          fx ((handler :task/completed) {:db db :event {:task/id "done"}})]
      (is (= 1 (count (get-in fx [:db :tasks/scheduled]))))
      (is (= "pending" (:task/id (first (get-in fx [:db :tasks/scheduled]))))))))

;; ---------------------------------------------------------------------------
;; Scheduling command handlers
;; ---------------------------------------------------------------------------

(deftest task-schedule-returns-write-effect-and-updates-db
  (testing ":task/schedule returns :task/write effect and registers task in db"
    (let [spec {:type :reminder/due :fire-at 9999 :payload {:text "hi"}}
          fx   ((handler :task/schedule) {:db (base-db) :event {:spec spec}})]
      (is (map? (:task/write fx)))
      (is (= :reminder/due (get-in fx [:task/write :task/type])))
      (is (= 9999 (get-in fx [:task/write :task/fire-at])))
      (is (= 1 (count (get-in fx [:db :tasks/scheduled])))))))

(deftest task-schedule-does-not-touch-disk
  (testing ":task/schedule is pure — no disk I/O, only effect descriptors"
    (let [spec {:type :reminder/due :fire-at 0 :payload {}}
          fx   ((handler :task/schedule) {:db (base-db) :event {:spec spec}})]
      (is (contains? fx :task/write))
      (is (contains? fx :db))
      (is (= 2 (count fx))))))

(deftest task-cancel-returns-delete-effect-and-updates-db
  (testing ":task/cancel returns :task/delete effect and removes task from db"
    (let [db (assoc (base-db) :tasks/scheduled [(task "gone") (task "keep")])
          fx ((handler :task/cancel) {:db db :event {:task/id "gone"}})]
      (is (= "gone" (:task/delete fx)))
      (is (= 1 (count (get-in fx [:db :tasks/scheduled]))))
      (is (= "keep" (:task/id (first (get-in fx [:db :tasks/scheduled]))))))))

(deftest periodic-reflection-returns-run-effect
  (testing ":scheduler/periodic-reflection returns only a :reflection/run effect"
    (let [fx ((handler :scheduler/periodic-reflection) {:db (base-db)})]
      (is (= {:reflection/run {}} fx)))))
