(ns pa.scheduler.tasks-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.scheduler.tasks :as tasks]))

(def ^:dynamic *tmp-root* nil)

(defn- delete-dir! [f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (delete-dir! child)))
  (.delete f))

(defn- with-tmp-dir [f]
  (let [tmp (java.nio.file.Files/createTempDirectory
             "pa-tasks-test-" (make-array java.nio.file.attribute.FileAttribute 0))
        root (str tmp)]
    (.mkdirs (io/file root "tasks" "scheduled"))
    (.mkdirs (io/file root "tasks" "completed"))
    (binding [*tmp-root* root]
      (try (f) (finally (delete-dir! (io/file root)))))))

(use-fixtures :each with-tmp-dir)

;; ---------------------------------------------------------------------------
;; make-task
;; ---------------------------------------------------------------------------

(deftest make-task-generates-uuid-id
  (testing "make-task always generates a unique :task/id"
    (let [t1 (tasks/make-task {:type :reminder/due :fire-at 1000})
          t2 (tasks/make-task {:type :reminder/due :fire-at 1000})]
      (is (string? (:task/id t1)))
      (is (not= (:task/id t1) (:task/id t2))))))

(deftest make-task-sets-required-fields
  (testing "make-task populates all task fields from spec"
    (let [t (tasks/make-task {:type :reminder/due :payload {:text "hi"} :fire-at 5000 :interval-ms 60000})]
      (is (= :reminder/due (:task/type t)))
      (is (= {:text "hi"} (:task/payload t)))
      (is (= 5000 (:task/fire-at t)))
      (is (= 60000 (:task/interval-ms t))))))

(deftest make-task-defaults-payload-to-empty-map
  (testing "make-task defaults :task/payload to {} when not provided"
    (is (= {} (:task/payload (tasks/make-task {:type :reminder/due :fire-at 0}))))))

;; ---------------------------------------------------------------------------
;; write-task! / load-tasks round-trip
;; ---------------------------------------------------------------------------

(deftest write-and-load-round-trips
  (testing "a written task can be loaded back with identical data"
    (let [task (tasks/make-task {:type :reminder/due :payload {:text "test"} :fire-at 9999})]
      (tasks/write-task! *tmp-root* task)
      (let [loaded (tasks/load-tasks *tmp-root*)]
        (is (= 1 (count loaded)))
        (is (= task (first loaded)))))))

(deftest load-tasks-returns-empty-when-no-files
  (testing "load-tasks returns [] when tasks/scheduled is empty"
    (is (= [] (tasks/load-tasks *tmp-root*)))))

(deftest load-tasks-returns-all-written
  (testing "load-tasks returns all written tasks"
    (tasks/write-task! *tmp-root* (tasks/make-task {:type :reminder/due :fire-at 1}))
    (tasks/write-task! *tmp-root* (tasks/make-task {:type :reminder/due :fire-at 2}))
    (is (= 2 (count (tasks/load-tasks *tmp-root*))))))

;; ---------------------------------------------------------------------------
;; delete-task!
;; ---------------------------------------------------------------------------

(deftest delete-removes-file
  (testing "delete-task! removes the task EDN file"
    (let [task (tasks/make-task {:type :reminder/due :fire-at 0})]
      (tasks/write-task! *tmp-root* task)
      (is (= 1 (count (tasks/load-tasks *tmp-root*))))
      (tasks/delete-task! *tmp-root* (:task/id task))
      (is (= [] (tasks/load-tasks *tmp-root*))))))

;; ---------------------------------------------------------------------------
;; complete-task!
;; ---------------------------------------------------------------------------

(deftest complete-task-moves-to-completed
  (testing "complete-task! moves file from scheduled to completed"
    (let [task (tasks/make-task {:type :reminder/due :fire-at 0})]
      (tasks/write-task! *tmp-root* task)
      (tasks/complete-task! *tmp-root* task)
      (is (= [] (tasks/load-tasks *tmp-root*)))
      (let [done-file (io/file *tmp-root* "tasks" "completed" (str (:task/id task) ".edn"))]
        (is (.exists done-file))))))

(deftest complete-task-stamps-completed-at
  (testing "complete-task! adds :task/completed-at to the archived file"
    (let [task (tasks/make-task {:type :reminder/due :fire-at 0})
          before (System/currentTimeMillis)]
      (tasks/write-task! *tmp-root* task)
      (tasks/complete-task! *tmp-root* task)
      (let [done-file (io/file *tmp-root* "tasks" "completed" (str (:task/id task) ".edn"))
            archived  (clojure.edn/read-string (slurp done-file))]
        (is (>= (:task/completed-at archived) before))))))

;; ---------------------------------------------------------------------------
;; advance-task!
;; ---------------------------------------------------------------------------

(deftest advance-task-increments-fire-at
  (testing "advance-task! adds :task/interval-ms to :task/fire-at"
    (let [task    (tasks/make-task {:type :scheduler/periodic-reflection :fire-at 1000 :interval-ms 86400000})
          updated (tasks/advance-task! *tmp-root* task)]
      (is (= (+ 1000 86400000) (:task/fire-at updated))))))

(deftest advance-task-persists-on-disk
  (testing "advance-task! rewrites the EDN file with updated fire-at"
    (let [task (tasks/make-task {:type :scheduler/periodic-reflection :fire-at 1000 :interval-ms 3600000})]
      (tasks/write-task! *tmp-root* task)
      (tasks/advance-task! *tmp-root* task)
      (let [reloaded (first (tasks/load-tasks *tmp-root*))]
        (is (= (+ 1000 3600000) (:task/fire-at reloaded)))))))
