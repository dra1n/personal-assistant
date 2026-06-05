(ns pa.memory.indexer
  (:require [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [pa.db.memory :as db-memory]
            [pa.db.schema :as schema]
            [pa.storage.memory :as storage-memory]))

;; ---------------------------------------------------------------------------
;; rebuild-memory-index!
;;
;; Admin/recovery operation: drops and recreates the memories table, then
;; re-indexes every record found in memory/daily/ Markdown files.
;; ---------------------------------------------------------------------------

(defn rebuild-memory-index!
  "Drop and recreate memories and memories_fts, then reindex from Markdown files."
  [ds root]
  (jdbc/execute! ds ["DROP TABLE IF EXISTS memories"])
  (jdbc/execute! ds ["DROP TABLE IF EXISTS memories_fts"])
  (schema/init! ds)
  (doseq [record (storage-memory/read-all-daily root)]
    (db-memory/index! ds record)))

;; ---------------------------------------------------------------------------
;; Integrant component
;;
;; Provides :index-memory! fn to the executor context so the :memory/index
;; effect can call it without coupling the handler to the datasource directly.
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :memory/indexer [_ {:keys [db fs]}]
  (let [ds   (:datasource db)
        root (:root fs)]
    {:index-memory! (partial db-memory/index! ds)
     :rebuild!      (fn [] (rebuild-memory-index! ds root))
     :root          root
     :datasource    ds}))

(defmethod ig/halt-key! :memory/indexer [_ _])
