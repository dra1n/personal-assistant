(ns pa.storage.fs
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]))

(def ^:private identity-template-files
  ["identity.md" "user.md" "agents.md"])

(def ^:private memory-template-files
  ["memory.md"])

(def ^:private system-template-files
  ["tools.md" "heartbeat.md"])

(defn- create-dirs! [root]
  (doseq [path ["identity"
                "memory/daily"
                "cognition/reflections"
                "tasks/scheduled"
                "tasks/completed"
                "events"
                "history"
                "system"
                "workspace"
                "sqlite"
                "logs"]]
    (.mkdirs (io/file root path))))

(defn- create-templates!
  "Copy each template file from templates/<subdir>/ into root/<subdir>/, leaving
  any file that already exists untouched (idempotent)."
  [root subdir filenames]
  (doseq [filename filenames]
    (let [dest (io/file root subdir filename)]
      (when-not (.exists dest)
        (let [resource (io/resource (str "templates/" subdir "/" filename))]
          (io/copy (io/input-stream resource) dest))))))

(defn- create-identity-templates! [root]
  (create-templates! root "identity" identity-template-files))

(defn- create-memory-templates! [root]
  (create-templates! root "memory" memory-template-files))

(defn- create-system-templates! [root]
  (create-templates! root "system" system-template-files))

(defn- create-config-template!
  "Copy the user-settings template to <root>/config.edn if absent (idempotent).
  Read back by pa.config's #setting aero tag."
  [root]
  (let [dest (io/file root "config.edn")]
    (when-not (.exists dest)
      (io/copy (io/input-stream (io/resource "templates/config.edn")) dest))))

(defn- create-event-log! [root]
  (let [f (io/file root "events" "events.edn")]
    (when-not (.exists f)
      (spit f ""))))

(defn- create-history-log! [root]
  (let [f (io/file root "history" "history.edn")]
    (when-not (.exists f)
      (spit f ""))))

(defn pa-home []
  (or (System/getenv "PA_HOME")
      (str (System/getProperty "user.home") "/.config/personal-assistant")))

(defn bootstrap! [root]
  (create-dirs! root)
  (create-identity-templates! root)
  (create-memory-templates! root)
  (create-system-templates! root)
  (create-config-template! root)
  (create-event-log! root)
  (create-history-log! root))

(defmethod ig/init-key :storage/fs [_ _]
  (let [root (pa-home)]
    (bootstrap! root)
    {:root root}))

(defmethod ig/halt-key! :storage/fs [_ _])
