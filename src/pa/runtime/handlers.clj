(ns pa.runtime.handlers
  (:require [pa.runtime.registry :as registry]
            [pa.state.transitions :as tr]))

;; ---------------------------------------------------------------------------
;; System lifecycle handlers
;; ---------------------------------------------------------------------------

(registry/reg-handler :system/identity-loaded
  (fn [{:keys [db event]}]
    {:db (tr/set-identity db (:identity event))}))
