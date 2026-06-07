(ns pa.scheduler.core
  (:require [clojure.core.async :as async]
            [integrant.core :as ig]
            [pa.scheduler.effects :as effects]
            [pa.scheduler.handlers]
            [pa.scheduler.heartbeat :as heartbeat]
            [pa.scheduler.tasks :as tasks]
            [pa.state.db :as db]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))

(def ^:private tick-ms 60000)

(defn- now-ms [] (.toEpochMilli (Instant/now)))

;; ---------------------------------------------------------------------------
;; Task dispatch — task type IS the event type (must be a qualified keyword)
;; ---------------------------------------------------------------------------

(defn- fire-task! [dispatch! task]
  (dispatch! (merge {:event/type (:task/type task)} task)))

(defn- process-task!
  "Fire task via event system; advance or complete on disk, update db via events."
  [root dispatch! task]
  (log/info "scheduler: firing" {:task/id (:task/id task) :task/type (:task/type task)})
  (fire-task! dispatch! task)
  (if (:task/interval-ms task)
    (dispatch! {:event/type :task/advanced :task (tasks/advance-task! root task)})
    (do
      (tasks/complete-task! root task)
      (dispatch! {:event/type :task/completed :task/id (:task/id task)}))))

;; ---------------------------------------------------------------------------
;; Ticker — reads task state from db/db
;; ---------------------------------------------------------------------------

(defn- run-due! [root dispatch!]
  (let [now (now-ms)
        due (filterv #(<= (:task/fire-at %) now)
                     (:tasks/scheduled @db/db))]
    (doseq [task due]
      (process-task! root dispatch! task))))

(defn- emit-state! []
  (tap> {:scheduler/tick
         {:tasks     (mapv #(select-keys % [:task/id :task/type :task/fire-at])
                           (:tasks/scheduled @db/db))
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
        loaded     (tasks/load-tasks root)
        _          (heartbeat/register-if-missing! root loaded)
        control-ch (async/chan 1)
        now        (now-ms)]

    (effects/register! {:root root})

    (dispatch! {:event/type :tasks/loaded :tasks loaded})
    ;; Catch-up: fire overdue tasks immediately. :tasks/loaded is still queued,
    ;; so we use the freshly-loaded list rather than db state.
    (doseq [task (filterv #(<= (:task/fire-at %) now) loaded)]
      (fire-task! dispatch! task))
    (emit-state!)
    (start-ticker! root dispatch! control-ch)

    (log/info "scheduler: initialized")
    {:control-ch control-ch}))

(defmethod ig/halt-key! :pa/scheduler [_ {:keys [control-ch]}]
  (async/close! control-ch)
  (log/info "scheduler: stopped"))
