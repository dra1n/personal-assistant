(ns pa.scheduler.core
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig]
            [pa.llm.provider :as provider]
            [pa.scheduler.heartbeat :as heartbeat]
            [pa.scheduler.tasks :as tasks]
            [pa.storage.memory :as memory]
            [taoensso.timbre :as log])
  (:import [java.time Instant LocalDate]
           [java.time.format DateTimeFormatter]))

(def ^:private tick-ms 60000)

(defn- now-ms [] (.toEpochMilli (Instant/now)))

;; ---------------------------------------------------------------------------
;; Job implementations — run in futures so the ticker go-loop never blocks
;; ---------------------------------------------------------------------------

(defn- parse-date [f]
  (try (LocalDate/parse (str/replace (.getName f) ".md" ""))
       (catch Exception _ nil)))

(defn- record->text [r]
  (str "## " (:memory/title r) "\n\n" (:memory/summary r)))

(defn- run-reflection! [root llm-provider]
  (let [dir (io/file root "memory" "daily")]
    (when (.isDirectory dir)
      (let [records (->> (.listFiles dir)
                         (filter #(str/ends-with? (.getName %) ".md"))
                         (sort-by #(.getName %))
                         (take-last 7)
                         (keep parse-date)
                         (mapcat #(memory/read-daily root %)))]
        (when (seq records)
          (let [content  (str/join "\n\n---\n\n" (map record->text records))
                messages [{:role    :system
                           :content "Summarize the key themes, decisions, and insights from these memory notes in 3–5 bullet points."}
                          {:role :user :content content}]
                {:keys [content]} (provider/stream llm-provider messages {} (fn [_]))]
            (when (seq content)
              (let [date (.format (LocalDate/now) (DateTimeFormatter/ofPattern "yyyy-MM-dd"))
                    path (str root "/cognition/reflections/" date ".md")]
                (io/make-parents (io/file path))
                (spit path content)
                (log/info "scheduler: reflection written" {:path path})))))))))

;; ---------------------------------------------------------------------------
;; Task dispatch — runs on the ticker go-loop thread; long jobs go to futures
;; ---------------------------------------------------------------------------

(defn- fire-task! [{:keys [root llm dispatch!]} task]
  (case (:task/type task)
    :reminder
    (dispatch! {:event/type   :reminder/due
                :task/id      (:task/id task)
                :task/payload (:task/payload task)})

    :periodic-reflection
    (future
      (try (run-reflection! root llm)
           (catch Throwable e (log/error e "periodic reflection failed"))))

    (log/warn "scheduler: unknown task type" {:task/type (:task/type task)})))

(defn- process-task!
  "Fire task, advance or complete it on disk, return updated task for repeating tasks or nil."
  [ctx task]
  (log/info "scheduler: firing" {:task/id (:task/id task) :task/type (:task/type task)})
  (fire-task! ctx task)
  (if (:task/interval-ms task)
    (tasks/advance-task! (:root ctx) task)
    (do (tasks/complete-task! (:root ctx) task) nil)))

;; ---------------------------------------------------------------------------
;; Ticker
;; ---------------------------------------------------------------------------

(defn- run-due! [ctx tasks-atom]
  (let [now (now-ms)
        due (filterv #(<= (:task/fire-at %) now) @tasks-atom)]
    (doseq [task due]
      (let [updated (process-task! ctx task)]
        (swap! tasks-atom
               (fn [ts]
                 (if updated
                   (mapv #(if (= (:task/id %) (:task/id task)) updated %) ts)
                   (filterv #(not= (:task/id %) (:task/id task)) ts))))))))

(defn- emit-state! [tasks-atom]
  (tap> {:scheduler/tick {:tasks     (mapv #(select-keys % [:task/id :task/type :task/fire-at])
                                           @tasks-atom)
                          :ticked-at (str (Instant/now))}}))

(defn- start-ticker! [ctx tasks-atom control-ch]
  (async/go-loop []
    (let [[_ ch] (async/alts! [control-ch (async/timeout tick-ms)])]
      (when (not= ch control-ch)
        (run-due! ctx tasks-atom)
        (emit-state! tasks-atom)
        (recur)))))

;; ---------------------------------------------------------------------------
;; Integrant component
;;
;; ig/init-key loads tasks and registers heartbeat jobs, but does not start
;; the ticker. The dispatcher calls :start! after wiring its own dispatch!.
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :pa/scheduler [_ {:keys [fs llm]}]
  (let [root       (:root fs)
        loaded     (tasks/load-tasks root)
        _          (heartbeat/register-if-missing! root loaded)
        all-tasks  (tasks/load-tasks root)
        tasks-atom (atom all-tasks)
        control-ch (async/chan 1)]
    (log/info "scheduler: loaded" {:task-count (count all-tasks)})
    {:root       root
     :control-ch control-ch
     :tasks-atom tasks-atom
     :schedule!  (fn [spec]
                   (let [task (tasks/write-task! root (tasks/make-task spec))]
                     (swap! tasks-atom conj task)
                     task))
     :cancel!    (fn [id]
                   (tasks/delete-task! root id)
                   (swap! tasks-atom filterv #(not= (:task/id %) id)))
     :start!     (fn [dispatch!]
                   (let [ctx {:root root :llm llm :dispatch! dispatch!}]
                     (run-due! ctx tasks-atom)
                     (emit-state! tasks-atom)
                     (start-ticker! ctx tasks-atom control-ch)))}))

(defmethod ig/halt-key! :pa/scheduler [_ {:keys [control-ch]}]
  (async/close! control-ch)
  (log/info "scheduler: stopped"))
