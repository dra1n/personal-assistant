(ns pa.db.schema
  (:require [next.jdbc :as jdbc]))

(defn init!
  "Create the memories table if it does not already exist."
  [ds]
  (jdbc/execute! ds
    ["CREATE TABLE IF NOT EXISTS memories (
        id         TEXT PRIMARY KEY,
        path       TEXT NOT NULL,
        type       TEXT NOT NULL,
        title      TEXT NOT NULL,
        summary    TEXT NOT NULL,
        tags       TEXT NOT NULL DEFAULT '[]',
        created_at TEXT NOT NULL
      )"]))
