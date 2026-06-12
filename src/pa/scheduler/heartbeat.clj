(ns pa.scheduler.heartbeat
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pa.scheduler.tasks :as tasks]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))

;; Parses HEARTBEAT.md checklist items of the form:
;;   - [ ] <type>: interval=<ms>, description="..."
;; Returns job descriptors:
;;   {:job/type kw, :job/interval-ms long}

(defn- parse-job-line [line]
  (when-let [[_ type kv-str] (re-matches #"- \[ \] ([^:]+): (.*)" (str/trim line))]
    (let [type-kw  (keyword (str/trim type))
          interval (some-> (re-find #"interval=(\d+)" kv-str) second Long/parseLong)]
      (when interval
        (if (qualified-keyword? type-kw)
          {:job/type type-kw :job/interval-ms interval}
          (log/warn "heartbeat: skipping job with unqualified type — use namespace/name format" {:type type-kw}))))))

(defn load-jobs
  "Parse enabled job descriptors from system/heartbeat.md."
  [root]
  (let [f (io/file root "system" "heartbeat.md")]
    (if (.exists f)
      (->> (str/split-lines (slurp f))
           (keep parse-job-line)
           vec)
      [])))

(defn- kw->id [kw]
  (if-let [ns (namespace kw)]
    (str ns "/" (name kw))
    (name kw)))

(defn- job->task [job]
  {:task/id          (kw->id (:job/type job))
   :task/type        (:job/type job)
   :task/payload     {}
   :task/fire-at     (.toEpochMilli (Instant/now))
   :task/interval-ms (:job/interval-ms job)})

(defn register-if-missing!
  "Ensure each enabled heartbeat job has a corresponding task EDN.
  Uses the job type name as :task/id so re-registration on restart is idempotent."
  [root existing-tasks]
  (let [existing-ids (set (map :task/id existing-tasks))]
    (doseq [job  (load-jobs root)
            :let [id (kw->id (:job/type job))]
            :when (not (existing-ids id))]
      (tasks/write-task! root (job->task job)))))
