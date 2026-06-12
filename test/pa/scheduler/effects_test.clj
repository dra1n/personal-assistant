(ns pa.scheduler.effects-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.llm.provider :as provider]
            [pa.runtime.executor :as executor]
            [pa.scheduler.effects :as effects]
            [pa.scheduler.tasks :as tasks]
            [pa.storage.memory :as memory])
  (:import [java.time Instant LocalDate]
           [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:dynamic *tmp-root* nil)

(defn- delete-dir! [f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (delete-dir! child)))
  (.delete f))

(defn- with-tmp-dir [f]
  (let [tmp  (java.nio.file.Files/createTempDirectory
               "pa-effects-test-" (make-array java.nio.file.attribute.FileAttribute 0))
        root (str tmp)]
    (.mkdirs (io/file root "tasks" "scheduled"))
    (.mkdirs (io/file root "tasks" "completed"))
    (binding [*tmp-root* root]
      (effects/register! {:root root})
      (try (f) (finally (delete-dir! (io/file root)))))))

(use-fixtures :each with-tmp-dir)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- stub-llm [content]
  (reify provider/LLMProvider
    (invoke [_ _ _] (provider/text-result content))
    (stream [_ _ _ _] (provider/text-result content))))

(defn- capturing-llm
  "Returns {:provider llm :captured atom}. The atom holds the messages vector from the last call."
  [content]
  (let [captured (atom nil)]
    {:provider (reify provider/LLMProvider
                 (invoke [_ msgs _ ] (reset! captured msgs) (provider/text-result content))
                 (stream [_ msgs _ _] (reset! captured msgs) (provider/text-result content)))
     :captured captured}))

;; ---------------------------------------------------------------------------
;; :task/write effect
;; ---------------------------------------------------------------------------

(deftest task-write-persists-to-disk
  (testing ":task/write writes the task EDN so load-tasks can read it back"
    (let [task (tasks/make-task {:type :reminder/due :fire-at 1000 :payload {:text "hi"}})]
      (executor/execute-effect :task/write task {})
      (let [loaded (tasks/load-tasks *tmp-root*)]
        (is (= 1 (count loaded)))
        (is (= task (first loaded)))))))

(deftest task-write-returns-the-task
  (testing ":task/write returns the task unchanged"
    (let [task (tasks/make-task {:type :reminder/due :fire-at 0})]
      (is (= task (executor/execute-effect :task/write task {}))))))

(deftest task-write-multiple-tasks-are-independent
  (testing ":task/write for two tasks creates two separate files"
    (let [t1 (tasks/make-task {:type :reminder/due :fire-at 100})
          t2 (tasks/make-task {:type :reminder/due :fire-at 200})]
      (executor/execute-effect :task/write t1 {})
      (executor/execute-effect :task/write t2 {})
      (is (= 2 (count (tasks/load-tasks *tmp-root*)))))))

;; ---------------------------------------------------------------------------
;; :task/delete effect
;; ---------------------------------------------------------------------------

(deftest task-delete-removes-the-file
  (testing ":task/delete removes the task EDN from disk"
    (let [task (tasks/make-task {:type :reminder/due :fire-at 0})]
      (tasks/write-task! *tmp-root* task)
      (executor/execute-effect :task/delete (:task/id task) {})
      (is (= [] (tasks/load-tasks *tmp-root*))))))

(deftest task-delete-leaves-other-tasks-intact
  (testing ":task/delete only removes the targeted task"
    (let [t1 (tasks/make-task {:type :reminder/due :fire-at 1})
          t2 (tasks/make-task {:type :reminder/due :fire-at 2})]
      (tasks/write-task! *tmp-root* t1)
      (tasks/write-task! *tmp-root* t2)
      (executor/execute-effect :task/delete (:task/id t1) {})
      (let [remaining (tasks/load-tasks *tmp-root*)]
        (is (= 1 (count remaining)))
        (is (= (:task/id t2) (:task/id (first remaining))))))))

(deftest task-delete-nonexistent-returns-false
  (testing ":task/delete on a missing task returns false and does not throw"
    (is (false? (executor/execute-effect :task/delete "no-such-id" {})))))

;; ---------------------------------------------------------------------------
;; :reflection/run effect
;; ---------------------------------------------------------------------------

(deftest reflection-run-returns-a-future
  (testing ":reflection/run immediately returns a future"
    (let [fut (executor/execute-effect :reflection/run {} {:llm-provider (stub-llm "")})]
      (is (future? fut))
      @fut)))

(deftest reflection-run-is-noop-when-no-daily-dir
  (testing ":reflection/run completes without error when memory/daily does not exist"
    (let [fut (executor/execute-effect :reflection/run {} {:llm-provider (stub-llm "anything")})]
      (is (nil? @fut)))))

(deftest reflection-run-writes-reflection-file
  (testing ":reflection/run writes a dated reflection file from daily memory records"
    (.mkdirs (io/file *tmp-root* "memory" "daily"))
    (memory/write-daily *tmp-root*
                        {:memory/id         "m1"
                         :memory/title      "Test note"
                         :memory/summary    "I did some work"
                         :memory/type       :episodic
                         :memory/created-at (Instant/now)}
                        (LocalDate/now))
    @(executor/execute-effect :reflection/run {} {:llm-provider (stub-llm "- key insight")})
    (let [date-str (.format (LocalDate/now) (DateTimeFormatter/ofPattern "yyyy-MM-dd"))
          path     (io/file *tmp-root* "cognition" "reflections" (str date-str ".md"))]
      (is (.exists path))
      (is (= "- key insight" (slurp path))))))

(deftest reflection-run-skips-write-when-llm-returns-empty
  (testing ":reflection/run does not write a file when the LLM returns empty content"
    (.mkdirs (io/file *tmp-root* "memory" "daily"))
    (memory/write-daily *tmp-root*
                        {:memory/id         "m2"
                         :memory/title      "Note"
                         :memory/summary    "stuff"
                         :memory/type       :episodic
                         :memory/created-at (Instant/now)}
                        (LocalDate/now))
    @(executor/execute-effect :reflection/run {} {:llm-provider (stub-llm "")})
    (is (not (.exists (io/file *tmp-root* "cognition" "reflections"))))))

(deftest reflection-sends-record-title-and-summary-to-llm
  (testing ":reflection/run formats each record as '## Title\\n\\nSummary' in the LLM user message"
    (.mkdirs (io/file *tmp-root* "memory" "daily"))
    (memory/write-daily *tmp-root*
                        {:memory/id         "m1"
                         :memory/title      "Sprint planning"
                         :memory/summary    "Decided to focus on auth"
                         :memory/type       :episodic
                         :memory/created-at (Instant/now)}
                        (LocalDate/now))
    (let [{:keys [provider captured]} (capturing-llm "ok")]
      @(executor/execute-effect :reflection/run {} {:llm-provider provider})
      (let [user-content (:content (second @captured))]
        (is (str/includes? user-content "## Sprint planning"))
        (is (str/includes? user-content "Decided to focus on auth"))))))

(deftest reflection-gathers-records-from-multiple-days
  (testing ":reflection/run includes records from all days within the window"
    (.mkdirs (io/file *tmp-root* "memory" "daily"))
    (let [today     (LocalDate/now)
          yesterday (.minusDays today 1)]
      (memory/write-daily *tmp-root*
                          {:memory/id         "m1"
                           :memory/title      "Yesterday note"
                           :memory/summary    "Yesterday's work"
                           :memory/type       :episodic
                           :memory/created-at (Instant/now)}
                          yesterday)
      (memory/write-daily *tmp-root*
                          {:memory/id         "m2"
                           :memory/title      "Today note"
                           :memory/summary    "Today's work"
                           :memory/type       :episodic
                           :memory/created-at (Instant/now)}
                          today)
      (let [{:keys [provider captured]} (capturing-llm "ok")]
        @(executor/execute-effect :reflection/run {} {:llm-provider provider})
        (let [user-content (:content (second @captured))]
          (is (str/includes? user-content "Yesterday note"))
          (is (str/includes? user-content "Today note")))))))

(deftest reflection-window-excludes-oldest-file
  (testing ":reflection/run sends only the 7 most recent daily files; the 8th oldest is excluded"
    (.mkdirs (io/file *tmp-root* "memory" "daily"))
    (let [base (LocalDate/of 2020 1 1)]
      (doseq [n (range 8)]
        (memory/write-daily *tmp-root*
                            {:memory/id         (str "m" n)
                             :memory/title      (str "Note " n)
                             :memory/summary    (str "Summary " n)
                             :memory/type       :episodic
                             :memory/created-at (Instant/now)}
                            (.plusDays base n))))
    (let [{:keys [provider captured]} (capturing-llm "ok")]
      @(executor/execute-effect :reflection/run {} {:llm-provider provider})
      (let [user-content (:content (second @captured))]
        (is (not (str/includes? user-content "Note 0")))
        (doseq [n (range 1 8)]
          (is (str/includes? user-content (str "Note " n))))))))
