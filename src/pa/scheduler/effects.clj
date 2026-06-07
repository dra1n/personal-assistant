(ns pa.scheduler.effects
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pa.llm.provider :as provider]
            [pa.runtime.executor :as executor]
            [pa.scheduler.tasks :as tasks]
            [pa.storage.memory :as memory]
            [taoensso.timbre :as log])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------------------------
;; Runtime capabilities — set once at component init via register!
;; ---------------------------------------------------------------------------

(defonce ^:private caps (atom {}))

(defn register! [{:keys [root]}]
  (reset! caps {:root root}))

;; ---------------------------------------------------------------------------
;; Reflection logic (lives here because :reflection/run effect owns execution)
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
;; Effect implementations
;; ---------------------------------------------------------------------------

(defmethod executor/execute-effect :task/write [_ task _ctx]
  (let [{:keys [root]} @caps]
    (tasks/write-task! root task)))

(defmethod executor/execute-effect :task/delete [_ task-id _ctx]
  (let [{:keys [root]} @caps]
    (tasks/delete-task! root task-id)))

(defmethod executor/execute-effect :reflection/run [_ _ ctx]
  (let [{:keys [root]} @caps
        llm (:llm-provider ctx)]
    (future
      (try (run-reflection! root llm)
           (catch Throwable e
             (log/error e "periodic reflection failed"))))))
