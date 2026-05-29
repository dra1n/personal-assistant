(ns pa.runtime.queries-test
  (:require [clojure.test :refer [deftest is testing]]
            [pa.runtime.queries :as queries]
            [pa.runtime.state :as state]))

;; ---------------------------------------------------------------------------
;; Fixture db maps
;; ---------------------------------------------------------------------------

(def ^:private base-db state/initial-db)

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
;; Grep check: no swap!/reset! in pa.ui
;; ---------------------------------------------------------------------------

(deftest ui-namespace-has-no-direct-state-mutation
  (testing "pa.ui.* source contains no swap! or reset! on state/db"
    (let [src (str (slurp "src/pa/ui/core.clj")
                   (slurp "src/pa/ui/app.clj")
                   (slurp "src/pa/ui/subscribe.clj"))]
      (is (not (re-find #"swap!" src)))
      (is (not (re-find #"reset!" src))))))
