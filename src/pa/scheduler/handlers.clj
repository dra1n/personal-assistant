(ns pa.scheduler.handlers
  (:require [pa.runtime.registry :as registry]
            [pa.scheduler.tasks :as tasks]
            [pa.state.transitions :as tr]))

;; ---------------------------------------------------------------------------
;; Reminder handlers
;; ---------------------------------------------------------------------------

(registry/reg-handler :reminder/create
                      (fn [{:keys [event now]}]
                        (let [{:keys [text fire-at]} event
                              now-ms (.toEpochMilli now)]
                          (if (or (nil? fire-at) (<= fire-at now-ms))
                            {:tap {:reminder/rejected {:reason  "fire-at is missing or in the past"
                                                       :fire-at fire-at}}}
                            {:task/schedule {:type    :reminder/due
                                             :payload {:text text}
                                             :fire-at fire-at}
                             :tap           {:reminder/created {:text text :fire-at fire-at}}
                             :trace         {:event/type :reminder/create :fire-at fire-at}}))))

(registry/reg-handler :reminder/due
                      (fn [{:keys [db event]}]
                        (let [notification {:id      (:task/id event)
                                            :type    :reminder
                                            :payload (:task/payload event)}]
                          {:db    (tr/add-notification db notification)
                           :tap   {:reminder/due notification}
                           :trace {:event/type :reminder/due :task/id (:task/id event)}})))

;; ---------------------------------------------------------------------------
;; Task commands
;; ---------------------------------------------------------------------------

(registry/reg-handler :task/schedule
                      (fn [{:keys [db event]}]
                        (let [task (tasks/make-task (:spec event))]
                          {:task/write task
                           :db         (tr/register-scheduled-task db task)})))

(registry/reg-handler :task/cancel
                      (fn [{:keys [db event]}]
                        {:task/delete (:task/id event)
                         :db          (tr/remove-scheduled-task db (:task/id event))}))

(registry/reg-handler :scheduler/periodic-reflection
                      (fn [_] {:reflection/run {}}))

;; ---------------------------------------------------------------------------
;; Task lifecycle — state transitions driven by the ticker
;; ---------------------------------------------------------------------------

(registry/reg-handler :tasks/loaded
                      (fn [{:keys [db event]}]
                        {:db (tr/load-scheduled-tasks db (:tasks event))}))

(registry/reg-handler :task/advanced
                      (fn [{:keys [db event]}]
                        {:db (tr/replace-scheduled-task db (:task event))}))

(registry/reg-handler :task/completed
                      (fn [{:keys [db event]}]
                        {:db (tr/remove-scheduled-task db (:task/id event))}))
