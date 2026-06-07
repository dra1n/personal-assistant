(ns pa.tools.reminder
  (:require [pa.tools.registry :as registry]))

(registry/reg-tool :reminder/set
  {:fn          (fn [{:keys [text fire-at]} {:keys [dispatch!]}]
                  (dispatch! {:event/type :reminder/create :text text :fire-at fire-at})
                  {:status :scheduled :text text :fire-at fire-at})
   :description "Schedule a reminder. fire-at must be a Unix epoch millisecond timestamp already resolved from the user's natural-language time expression."
   :schema      {:type       "object"
                 :properties {:text    {:type "string"  :description "Reminder text to display when the reminder fires."}
                              :fire-at {:type "integer" :description "Unix epoch milliseconds at which the reminder should fire."}}
                 :required   [:text :fire-at]}})
