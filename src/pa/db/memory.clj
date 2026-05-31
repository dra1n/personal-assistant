(ns pa.db.memory
  (:require [clojure.edn :as edn]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; ---------------------------------------------------------------------------
;; Row <-> record conversion
;;
;; Tags are stored as EDN-printed vectors in a TEXT column so the schema
;; stays minimal (no join table) while round-tripping cleanly.
;; ---------------------------------------------------------------------------

(defn- record->row [record]
  {:id         (:memory/id record)
   :path       (:memory/path record)
   :type       (name (:memory/type record))
   :title      (:memory/title record)
   :summary    (:memory/summary record)
   :tags       (pr-str (or (:memory/tags record) []))
   :created_at (str (:memory/created-at record))})

(defn- row->record [row]
  {:memory/id         (:id row)
   :memory/path       (:path row)
   :memory/type       (keyword (:type row))
   :memory/title      (:title row)
   :memory/summary    (:summary row)
   :memory/tags       (edn/read-string (:tags row))
   :memory/created-at (:created_at row)})

(def ^:private opts {:builder-fn rs/as-unqualified-lower-maps})

;; ---------------------------------------------------------------------------
;; index!
;;
;; Upsert a memory record's metadata into SQLite. Called by pa.memory.indexer
;; in response to :memory/stored events — not directly by the writer.
;; ---------------------------------------------------------------------------

(defn index!
  "Insert or replace a memory record's metadata in the memories table."
  [ds record]
  (let [row (record->row record)]
    (jdbc/execute! ds
      ["INSERT OR REPLACE INTO memories (id, path, type, title, summary, tags, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)"
       (:id row) (:path row) (:type row) (:title row)
       (:summary row) (:tags row) (:created_at row)])))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defn recent
  "Return the N most recent memory records ordered by created_at descending."
  [ds n]
  (mapv row->record
        (jdbc/execute! ds
          ["SELECT * FROM memories ORDER BY created_at DESC LIMIT ?" n]
          opts)))

(defn by-type
  "Return all memory records of the given type keyword."
  [ds type-kw]
  (mapv row->record
        (jdbc/execute! ds
          ["SELECT * FROM memories WHERE type = ? ORDER BY created_at DESC" (name type-kw)]
          opts)))

(defn by-tags
  "Return records that contain ALL of the given tags (intersection)."
  [ds tags]
  (->> (jdbc/execute! ds ["SELECT * FROM memories"] opts)
       (map row->record)
       (filter (fn [r] (every? (set (:memory/tags r)) tags)))
       vec))
