(ns pa.storage.history
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig])
  (:import [java.util UUID]))

;; java.time.Instant has no default EDN print method — same fix as storage.events.
(defmethod print-method java.time.Instant [^java.time.Instant instant ^java.io.Writer w]
  (.write w "#inst \"")
  (.write w (.toString instant))
  (.write w "\""))

(def ^:private write-lock (Object.))

(def history-limit 50)

(defn make-entry
  "Build a new history entry map for the given command text."
  [text]
  {:history/id        (UUID/randomUUID)
   :history/text      text
   :history/timestamp (java.time.Instant/now)})

(defn append-entry!
  "Append one history entry to the newline-delimited EDN log at path."
  [path entry]
  (locking write-lock
    (spit path (str (pr-str entry) "\n") :append true)))

(defn load-history
  "Parse all entries from the newline-delimited EDN log at path.
  Returns the last history-limit entries as a vector (oldest first).
  Returns [] if the file is missing or empty."
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (->> (slurp f)
           str/split-lines
           (remove str/blank?)
           (mapv edn/read-string)
           (take-last history-limit)
           vec)
      [])))

(defmethod ig/init-key :storage/history [_ {:keys [fs]}]
  (let [path (str (:root fs) "/history/history.edn")]
    {:path          path
     :history       (load-history path)
     :append-entry! (partial append-entry! path)}))

(defmethod ig/halt-key! :storage/history [_ _])
