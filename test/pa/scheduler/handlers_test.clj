(ns pa.scheduler.handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [pa.runtime.registry :as registry]
            [pa.scheduler.handlers]
            [pa.state.db :as db]))

(defn- handler [event-type]
  (:fn (registry/get-handler event-type)))

(defn- base-db [] db/initial-db)

(defn- task [id] {:task/id id :task/type :reminder/due :task/payload {} :task/fire-at 0})

;; ---------------------------------------------------------------------------
;; Task lifecycle handlers
;; ---------------------------------------------------------------------------

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
;; Reminder creation handler
;; ---------------------------------------------------------------------------

(deftest reminder-create-emits-task-schedule
  (testing ":reminder/create emits :task/schedule with correct type, payload, and fire-at"
    (let [fire-at (+ (System/currentTimeMillis) 60000)
          fx      ((handler :reminder/create)
                   {:db (base-db) :event {:text "buy milk" :fire-at fire-at}})]
      (is (= :reminder/due (get-in fx [:task/schedule :type])))
      (is (= {:text "buy milk"} (get-in fx [:task/schedule :payload])))
      (is (= fire-at (get-in fx [:task/schedule :fire-at]))))))

(deftest reminder-create-rejects-past-fire-at
  (testing ":reminder/create does not emit :task/schedule when fire-at is in the past"
    (let [fx ((handler :reminder/create)
              {:db (base-db) :event {:text "old" :fire-at 1}})]
      (is (not (contains? fx :task/schedule))))))

(deftest reminder-create-rejects-missing-fire-at
  (testing ":reminder/create does not emit :task/schedule when fire-at is absent"
    (let [fx ((handler :reminder/create)
              {:db (base-db) :event {:text "no time"}})]
      (is (not (contains? fx :task/schedule))))))

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
