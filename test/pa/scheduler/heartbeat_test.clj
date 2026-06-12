(ns pa.scheduler.heartbeat-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.scheduler.heartbeat :as heartbeat]
            [pa.scheduler.tasks :as tasks]))

(def ^:dynamic *tmp-root* nil)

(defn- delete-dir! [f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (delete-dir! child)))
  (.delete f))

(defn- with-tmp-dir [f]
  (let [tmp (java.nio.file.Files/createTempDirectory
             "pa-hb-test-" (make-array java.nio.file.attribute.FileAttribute 0))
        root (str tmp)]
    (.mkdirs (io/file root "system"))
    (.mkdirs (io/file root "tasks" "scheduled"))
    (binding [*tmp-root* root]
      (try (f) (finally (delete-dir! (io/file root)))))))

(use-fixtures :each with-tmp-dir)

(defn- write-heartbeat! [content]
  (spit (io/file *tmp-root* "system" "heartbeat.md") content))

;; ---------------------------------------------------------------------------
;; load-jobs — parsing
;; ---------------------------------------------------------------------------

(deftest load-jobs-parses-enabled-jobs
  (testing "load-jobs returns a descriptor for each unchecked job line"
    (write-heartbeat! "- [ ] scheduler/periodic-reflection: interval=86400000, description=\"Daily\"\n")
    (let [jobs (heartbeat/load-jobs *tmp-root*)]
      (is (= 1 (count jobs)))
      (is (= :scheduler/periodic-reflection (:job/type (first jobs))))
      (is (= 86400000 (:job/interval-ms (first jobs)))))))

(deftest load-jobs-skips-checked-jobs
  (testing "load-jobs ignores lines marked with [x]"
    (write-heartbeat! "- [x] memory-consolidation: interval=604800000, description=\"Weekly\"\n")
    (is (= [] (heartbeat/load-jobs *tmp-root*)))))

(deftest load-jobs-skips-lines-without-interval
  (testing "load-jobs ignores lines missing the interval key"
    (write-heartbeat! "- [ ] bad-job: description=\"No interval here\"\n")
    (is (= [] (heartbeat/load-jobs *tmp-root*)))))

(deftest load-jobs-returns-empty-when-file-missing
  (testing "load-jobs returns [] when heartbeat.md does not exist"
    (is (= [] (heartbeat/load-jobs *tmp-root*)))))

(deftest load-jobs-parses-multiple-jobs
  (testing "load-jobs returns all enabled jobs in order"
    (write-heartbeat!
     (str "- [ ] ns/job-a: interval=1000, description=\"A\"\n"
          "- [x] ns/job-b: interval=2000, description=\"B\"\n"
          "- [ ] ns/job-c: interval=3000, description=\"C\"\n"))
    (let [jobs (heartbeat/load-jobs *tmp-root*)]
      (is (= 2 (count jobs)))
      (is (= [:ns/job-a :ns/job-c] (mapv :job/type jobs))))))

;; ---------------------------------------------------------------------------
;; register-if-missing! — idempotency
;; ---------------------------------------------------------------------------

(deftest register-if-missing-creates-task-for-new-job
  (testing "register-if-missing! writes a task EDN for a job not yet registered"
    (write-heartbeat! "- [ ] scheduler/periodic-reflection: interval=86400000, description=\"Daily\"\n")
    (heartbeat/register-if-missing! *tmp-root* [])
    (let [loaded (tasks/load-tasks *tmp-root*)]
      (is (= 1 (count loaded)))
      (is (= :scheduler/periodic-reflection (:task/type (first loaded))))
      (is (= "scheduler/periodic-reflection" (:task/id (first loaded)))))))

(deftest register-if-missing-is-idempotent
  (testing "calling register-if-missing! twice does not create duplicate tasks"
    (write-heartbeat! "- [ ] scheduler/periodic-reflection: interval=86400000, description=\"Daily\"\n")
    (heartbeat/register-if-missing! *tmp-root* [])
    (let [after-first (tasks/load-tasks *tmp-root*)]
      (heartbeat/register-if-missing! *tmp-root* after-first)
      (is (= 1 (count (tasks/load-tasks *tmp-root*)))))))

(deftest register-if-missing-skips-existing-tasks
  (testing "register-if-missing! does not overwrite tasks already on disk"
    (write-heartbeat! "- [ ] scheduler/periodic-reflection: interval=86400000, description=\"Daily\"\n")
    (let [existing {:task/id "scheduler/periodic-reflection"
                    :task/type :scheduler/periodic-reflection
                    :task/payload {} :task/fire-at 12345 :task/interval-ms 86400000}]
      (tasks/write-task! *tmp-root* existing)
      (heartbeat/register-if-missing! *tmp-root* [existing])
      (let [loaded (tasks/load-tasks *tmp-root*)]
        (is (= 1 (count loaded)))
        (is (= 12345 (:task/fire-at (first loaded))))))))
