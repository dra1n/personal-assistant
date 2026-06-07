(ns pa.scheduler.core
  (:require [clojure.core.async :as async]
            [integrant.core :as ig]
            [pa.scheduler.effects :as effects]
            [pa.scheduler.handlers]
            [pa.scheduler.heartbeat :as heartbeat]
            [pa.scheduler.tasks :as tasks]
            [pa.state.db :as db]
            [pa.state.queries :as queries]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))

(def ^:private tick-ms 60000)

(defn- now-ms [] (.toEpochMilli (Instant/now)))

;; ---------------------------------------------------------------------------
;; Task dispatch — task type IS the event type (must be a qualified keyword)
;; ---------------------------------------------------------------------------

(defn- process-task!
  "Fire task via event system; advance or complete on disk, update db via events."
  [root dispatch! task]
  (log/info "scheduler: firing" {:task/id (:task/id task) :task/type (:task/type task)})
  (dispatch! (merge {:event/type (:task/type task)} task))
  (if (:task/interval-ms task)
    (dispatch! {:event/type :task/advanced :task (tasks/advance-task! root task)})
    (do
      (tasks/complete-task! root task)
      (dispatch! {:event/type :task/completed :task/id (:task/id task)}))))

;; ---------------------------------------------------------------------------
;; Ticker — reads task state from db/db
;; ---------------------------------------------------------------------------

(defn- run-due! [root dispatch!]
  (doseq [task (queries/due-tasks (db/current-db) (now-ms))]
    (process-task! root dispatch! task)))

(defn- emit-state! []
  (tap> {:scheduler/tick
         {:tasks     (mapv #(select-keys % [:task/id :task/type :task/fire-at])
                           (queries/scheduled-tasks (db/current-db)))
          :ticked-at (str (Instant/now))}}))

(defn- start-ticker! [root dispatch! control-ch]
  (async/go-loop []
    (let [[_ ch] (async/alts! [control-ch (async/timeout tick-ms)])]
      (when (not= ch control-ch)
        (run-due! root dispatch!)
        (emit-state!)
        (recur)))))

;; ---------------------------------------------------------------------------
;; Integrant component
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :pa/scheduler [_ {:keys [fs dispatcher]}]
  (let [root       (:root fs)
        dispatch!  (:dispatch! dispatcher)
        _          (heartbeat/register-if-missing! root (tasks/load-tasks root))
        loaded     (tasks/load-tasks root)
        control-ch (async/chan 1)
        now        (now-ms)]

    (effects/register! {:root root})

    (dispatch! {:event/type :tasks/loaded :tasks loaded})
    ;; Catch-up: fire overdue tasks immediately using the freshly-loaded list
    ;; (db not yet populated — :tasks/loaded is still queued). process-task! is
    ;; safe here: it dispatches :task/advanced/:task/completed events which are
    ;; enqueued after :tasks/loaded and process once db is populated.
    (doseq [task (filterv #(<= (:task/fire-at %) now) loaded)]
      (process-task! root dispatch! task))
    (emit-state!)
    (start-ticker! root dispatch! control-ch)

    (log/info "scheduler: initialized")
    {:control-ch control-ch}))

(defmethod ig/halt-key! :pa/scheduler [_ {:keys [control-ch]}]
  (async/close! control-ch)
  (log/info "scheduler: stopped"))
