(ns pa.storage.events
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig]))

;; java.time.Instant has no default EDN print method — emit #inst tagged literal
;; so round-trips via edn/read-string produce inst?-satisfying java.util.Date values.
(defmethod print-method java.time.Instant [^java.time.Instant instant ^java.io.Writer w]
  (.write w "#inst \"")
  (.write w (.toString instant))
  (.write w "\""))

(def ^:private write-lock (Object.))

(defn append-event!
  "Append event to the newline-delimited EDN event log at path."
  [path event]
  (locking write-lock
    (spit path (str (pr-str event) "\n") :append true)))

(defn load-events
  "Parse all events from the newline-delimited EDN log at path.
  Returns a vector of event maps. Returns [] if the file is missing or empty."
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (->> (slurp f)
           str/split-lines
           (remove str/blank?)
           (mapv edn/read-string))
      [])))

(defmethod ig/init-key :storage/events [_ {:keys [fs]}]
  (let [path (str (:root fs) "/events/events.edn")]
    {:path          path
     :append-event! (partial append-event! path)}))

(defmethod ig/halt-key! :storage/events [_ _])
