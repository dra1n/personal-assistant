(ns pa.state.queries-test
  (:require [clojure.test :refer [deftest is testing]]
            [pa.state.db :as db]
            [pa.state.queries :as queries]))

;; ---------------------------------------------------------------------------
;; Fixture db maps
;; ---------------------------------------------------------------------------

(def ^:private base-db db/initial-db)

(def ^:private populated-db
  {:conversation  [{:role :user :text "hello"} {:role :assistant :text "hi"}]
   :tasks         {:t1 {:status :pending} :t2 {:status :done}}
   :events/recent [{:event/type :user/message} {:event/type :scheduler/tick}]
   :ui            {:focused :input :scroll 3}})

;; ---------------------------------------------------------------------------
;; queries/conversation
;; ---------------------------------------------------------------------------

(deftest conversation-returns-empty-on-initial-db
  (testing "returns empty vector for initial state"
    (is (= [] (queries/conversation base-db)))))

(deftest conversation-returns-history
  (testing "returns the full conversation vector"
    (is (= [{:role :user :text "hello"} {:role :assistant :text "hi"}]
           (queries/conversation populated-db)))))

;; ---------------------------------------------------------------------------
;; queries/tasks
;; ---------------------------------------------------------------------------

(deftest tasks-returns-empty-on-initial-db
  (testing "returns empty map for initial state"
    (is (= {} (queries/tasks base-db)))))

(deftest tasks-returns-task-map
  (testing "returns the tasks map"
    (is (= {:t1 {:status :pending} :t2 {:status :done}}
           (queries/tasks populated-db)))))

;; ---------------------------------------------------------------------------
;; queries/recent-events
;; ---------------------------------------------------------------------------

(deftest recent-events-returns-empty-on-initial-db
  (testing "returns empty vector for initial state"
    (is (= [] (queries/recent-events base-db)))))

(deftest recent-events-returns-event-log
  (testing "returns the recent-events vector"
    (is (= [{:event/type :user/message} {:event/type :scheduler/tick}]
           (queries/recent-events populated-db)))))

;; ---------------------------------------------------------------------------
;; queries/ui-prefs
;; ---------------------------------------------------------------------------

(deftest ui-prefs-returns-empty-on-initial-db
  (testing "returns empty map for initial state"
    (is (= {} (queries/ui-prefs base-db)))))

(deftest ui-prefs-returns-ui-map
  (testing "returns the ui map"
    (is (= {:focused :input :scroll 3}
           (queries/ui-prefs populated-db)))))

;; ---------------------------------------------------------------------------
;; queries/scheduled-tasks
;; ---------------------------------------------------------------------------

(deftest scheduled-tasks-returns-empty-on-initial-db
  (testing "returns empty vector for initial state"
    (is (= [] (queries/scheduled-tasks base-db)))))

(deftest scheduled-tasks-returns-vector
  (testing "returns the :tasks/scheduled vector"
    (let [tasks [{:task/id "a" :task/fire-at 0} {:task/id "b" :task/fire-at 1}]
          db    (assoc base-db :tasks/scheduled tasks)]
      (is (= tasks (queries/scheduled-tasks db))))))

;; ---------------------------------------------------------------------------
;; queries/due-tasks
;; ---------------------------------------------------------------------------

(deftest due-tasks-returns-only-tasks-at-or-before-now
  (testing "filters to tasks whose :task/fire-at <= now-ms"
    (let [db (assoc base-db :tasks/scheduled
                    [{:task/id "past"   :task/fire-at 100}
                     {:task/id "now"    :task/fire-at 500}
                     {:task/id "future" :task/fire-at 900}])]
      (is (= #{"past" "now"}
             (set (map :task/id (queries/due-tasks db 500))))))))

(deftest due-tasks-returns-empty-when-none-due
  (testing "returns empty vector when all tasks are in the future"
    (let [db (assoc base-db :tasks/scheduled [{:task/id "later" :task/fire-at 9999}])]
      (is (= [] (queries/due-tasks db 0))))))

;; ---------------------------------------------------------------------------
;; queries/notifications
;; ---------------------------------------------------------------------------

(deftest notifications-returns-nil-on-initial-db
  (testing "returns nil when key is absent"
    (is (nil? (queries/notifications base-db)))))

(deftest notifications-returns-vector
  (testing "returns the :ui/notifications vector"
    (let [notifs [{:id "a" :type :reminder}]
          db     (assoc base-db :ui/notifications notifs)]
      (is (= notifs (queries/notifications db))))))

;; ---------------------------------------------------------------------------
;; queries/setting
;; ---------------------------------------------------------------------------

(deftest setting-returns-nil-when-unset
  (testing "returns nil on initial db and for an absent key"
    (is (nil? (queries/setting base-db :markdown)))
    (is (nil? (queries/setting (assoc base-db :settings {:markdown true}) :other)))))

(deftest setting-returns-stored-value
  (testing "returns the stored value for a set key"
    (let [db (assoc base-db :settings {:markdown true})]
      (is (true? (queries/setting db :markdown))))))

;; ---------------------------------------------------------------------------
;; Grep check: no swap!/reset! in pa.ui
;; ---------------------------------------------------------------------------

(deftest ui-namespace-has-no-direct-state-mutation
  (testing "pa.ui.* source contains no swap! or reset! on state/db"
    (let [src (str (slurp "src/pa/ui/core.clj")
                   (slurp "src/pa/ui/app.clj")
                   (slurp "src/pa/ui/subscribe.clj"))]
      (is (not (re-find #"swap!" src)))
      (is (not (re-find #"reset!" src))))))
