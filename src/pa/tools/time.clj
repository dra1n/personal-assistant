(ns pa.tools.time
  (:require [pa.tools.registry :as registry])
  (:import [java.time Instant]))

(registry/reg-tool :time/now
  {:fn          (fn [_ _]
                  (let [now (Instant/now)]
                    {:epoch-ms (.toEpochMilli now)
                     :iso      (str now)}))
   :description "Return the current wall-clock time as a Unix epoch millisecond timestamp and an ISO 8601 string. Call this before scheduling reminders or any other time-sensitive operation."
   :schema      {:type "object" :properties {} :required []}})
