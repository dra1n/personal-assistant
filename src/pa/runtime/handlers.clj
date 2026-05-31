(ns pa.runtime.handlers
  (:require [pa.runtime.registry :as registry]
            [pa.state.transitions :as tr]))

;; ---------------------------------------------------------------------------
;; System lifecycle handlers
;; ---------------------------------------------------------------------------

(registry/reg-handler :system/identity-loaded
  (fn [{:keys [db event]}]
    {:db (tr/set-identity db (:identity event))}))

;; ---------------------------------------------------------------------------
;; Memory handlers
;; ---------------------------------------------------------------------------

(registry/reg-handler :memory/stored
  (fn [{:keys [db event]}]
    {:db           (tr/add-memory db (:record event))
     :memory/index (:record event)
     :trace        {:event/type :memory/stored :id (get-in event [:record :memory/id])}}))
