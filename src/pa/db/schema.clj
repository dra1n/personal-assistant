(ns pa.db.schema
  (:require [next.jdbc :as jdbc]))

(defn init!
  "Create the memories and memories_fts tables if they do not already exist."
  [ds]
  (jdbc/execute! ds
    ["CREATE TABLE IF NOT EXISTS memories (
        id         TEXT PRIMARY KEY,
        path       TEXT NOT NULL,
        type       TEXT NOT NULL,
        title      TEXT NOT NULL,
        summary    TEXT NOT NULL,
        tags       TEXT NOT NULL DEFAULT '[]',
        created_at INTEGER NOT NULL
      )"])
  (jdbc/execute! ds
    ["CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts
      USING fts5(id UNINDEXED, title, summary, tags)"]))
