(ns pa.scheduler.tasks
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util UUID]))

;; Task schema:
;;   :task/id          — string UUID (or stable name for heartbeat jobs)
;;   :task/type        — keyword (:reminder | :periodic-reflection | :memory-consolidation)
;;   :task/payload     — map (task-type-specific data)
;;   :task/fire-at     — Unix epoch ms (long)
;;   :task/interval-ms — long for repeating tasks, nil for one-shots

(defn make-task [{:keys [type payload fire-at interval-ms]}]
  {:task/id          (str (UUID/randomUUID))
   :task/type        type
   :task/payload     (or payload {})
   :task/fire-at     fire-at
   :task/interval-ms interval-ms})

(defn- task-file [root id]
  (io/file root "tasks" "scheduled" (str id ".edn")))

(defn- completed-file [root id]
  (io/file root "tasks" "completed" (str id ".edn")))

(defn write-task!
  "Persist a task EDN to tasks/scheduled/<id>.edn. Returns the task."
  [root task]
  (spit (task-file root (:task/id task)) (pr-str task))
  task)

(defn delete-task!
  "Remove a task EDN file from tasks/scheduled/."
  [root id]
  (.delete (task-file root id)))

(defn load-tasks
  "Load all tasks from tasks/scheduled/. Returns a vector."
  [root]
  (let [dir (io/file root "tasks" "scheduled")]
    (if (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName %) ".edn"))
           (mapv #(edn/read-string (slurp %))))
      [])))

(defn complete-task!
  "Move a one-shot task from tasks/scheduled/ to tasks/completed/. Returns nil."
  [root task]
  (let [dest (completed-file root (:task/id task))]
    (io/make-parents dest)
    (spit dest (pr-str (assoc task :task/completed-at (System/currentTimeMillis))))
    (.delete (task-file root (:task/id task)))))

(defn advance-task!
  "Advance a repeating task's :task/fire-at by its interval and rewrite EDN. Returns updated task."
  [root task]
  (let [updated (update task :task/fire-at + (:task/interval-ms task))]
    (write-task! root updated)
    updated))
