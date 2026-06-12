(ns pa.scheduler.core-test
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [integrant.core :as ig]
            [pa.scheduler.core]
            [pa.scheduler.tasks :as tasks]
            [pa.state.db :as db]
            [pa.state.transitions :as tr]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:dynamic *root* nil)

(defn- delete-dir! [f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (delete-dir! child)))
  (.delete f))

(defn- with-tmp-dir [f]
  (let [tmp  (java.nio.file.Files/createTempDirectory
              "pa-scheduler-core-test-" (make-array java.nio.file.attribute.FileAttribute 0))
        root (str tmp)]
    (doseq [d ["tasks/scheduled" "tasks/completed" "memory/daily" "system"]]
      (.mkdirs (io/file root d)))
    (binding [*root* root]
      (try (f) (finally (delete-dir! (io/file root)))))))

(use-fixtures :each
  with-tmp-dir
  (fn [f] (reset! db/db db/initial-db) (f)))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- past-task [type payload]
  (tasks/make-task {:type    type
                    :fire-at (- (System/currentTimeMillis) 5000)
                    :payload payload}))

(defn- future-task [type payload ms-ahead]
  (tasks/make-task {:type    type
                    :fire-at (+ (System/currentTimeMillis) ms-ahead)
                    :payload payload}))

(defn- make-dispatcher
  "Mock dispatcher that collects dispatched events. Also processes :tasks/loaded
  so that db/db is populated for the ticker's due-tasks query."
  []
  (let [dispatched (atom [])]
    {:dispatch! (fn [event]
                  (swap! dispatched conj event)
                  (when (= :tasks/loaded (:event/type event))
                    (swap! db/db tr/load-scheduled-tasks (:tasks event))))
     :dispatched dispatched}))

(defn- start! [root dispatched & {:keys [tick-ch-fn]}]
  (ig/init-key :pa/scheduler
               (cond-> {:fs         {:root root}
                        :dispatcher {:dispatch! (:dispatch! dispatched)}}
                 tick-ch-fn (assoc :tick-ch-fn tick-ch-fn))))

;; ---------------------------------------------------------------------------
;; Catch-up on init
;; ---------------------------------------------------------------------------

(deftest catch-up-fires-overdue-task-on-init
  (let [task (past-task :reminder/due {:text "overdue"})
        _    (tasks/write-task! *root* task)
        d    (make-dispatcher)
        s    (start! *root* d :tick-ch-fn (constantly (async/chan)))]
    (ig/halt-key! :pa/scheduler s)
    (is (some #(= :reminder/due (:event/type %)) @(:dispatched d))
        ":reminder/due dispatched via catch-up")))

(deftest catch-up-does-not-fire-future-task
  (let [task (future-task :reminder/due {:text "not yet"} 999999)
        _    (tasks/write-task! *root* task)
        d    (make-dispatcher)
        s    (start! *root* d :tick-ch-fn (constantly (async/chan)))]
    (ig/halt-key! :pa/scheduler s)
    (is (not (some #(= :reminder/due (:event/type %)) @(:dispatched d)))
        ":reminder/due not dispatched for future task")))

;; ---------------------------------------------------------------------------
;; Ticker fires due tasks
;; ---------------------------------------------------------------------------

(deftest ticker-fires-due-task-on-tick
  (let [task    (future-task :reminder/due {:text "ticker"} 50)
        _       (tasks/write-task! *root* task)
        tick-ch (async/chan 1)
        d       (make-dispatcher)
        s       (start! *root* d :tick-ch-fn (constantly tick-ch))]
    ;; wait for fire-at to pass, then trigger a tick
    (Thread/sleep 80)
    (async/put! tick-ch :tick)
    (Thread/sleep 50) ; let go-loop process
    (ig/halt-key! :pa/scheduler s)
    (is (some #(= :reminder/due (:event/type %)) @(:dispatched d))
        ":reminder/due dispatched after tick when task becomes due")))

(deftest ticker-does-not-fire-task-before-due
  (let [task    (future-task :reminder/due {:text "not yet"} 999999)
        _       (tasks/write-task! *root* task)
        tick-ch (async/chan 1)
        d       (make-dispatcher)
        s       (start! *root* d :tick-ch-fn (constantly tick-ch))]
    (async/put! tick-ch :tick)
    (Thread/sleep 50)
    (ig/halt-key! :pa/scheduler s)
    (is (not (some #(= :reminder/due (:event/type %)) @(:dispatched d)))
        ":reminder/due not dispatched when task is not yet due")))

;; ---------------------------------------------------------------------------
;; Reminder task end-to-end: EDN on disk → dispatch + file lifecycle
;; ---------------------------------------------------------------------------

(deftest reminder-task-dispatched-and-moved-to-completed
  (let [task (past-task :reminder/due {:text "e2e reminder"})
        _    (tasks/write-task! *root* task)
        d    (make-dispatcher)
        s    (start! *root* d :tick-ch-fn (constantly (async/chan)))]
    (ig/halt-key! :pa/scheduler s)
    (is (some #(= :reminder/due (:event/type %)) @(:dispatched d))
        ":reminder/due dispatched")
    (is (not (.exists (io/file *root* "tasks/scheduled" (str (:task/id task) ".edn"))))
        "task removed from tasks/scheduled/")
    (is (.exists (io/file *root* "tasks/completed" (str (:task/id task) ".edn")))
        "task present in tasks/completed/")))
