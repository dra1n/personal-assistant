(ns pa.db.memory
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.time Instant]))

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
   :created_at (.toEpochMilli ^Instant (:memory/created-at record))})

(defn- row->record [row]
  {:memory/id         (:id row)
   :memory/path       (:path row)
   :memory/type       (keyword (:type row))
   :memory/title      (:title row)
   :memory/summary    (:summary row)
   :memory/tags       (edn/read-string (:tags row))
   :memory/created-at (Instant/ofEpochMilli (:created_at row))})

(def ^:private opts {:builder-fn rs/as-unqualified-lower-maps})

;; ---------------------------------------------------------------------------
;; index!
;;
;; Upsert a memory record's metadata into SQLite. Called by pa.memory.indexer
;; in response to :memory/stored events — not directly by the writer.
;; ---------------------------------------------------------------------------

(defn index!
  "Insert or replace a memory record's metadata in memories and memories_fts."
  [ds record]
  (let [row (record->row record)]
    (jdbc/execute! ds
      ["INSERT OR REPLACE INTO memories (id, path, type, title, summary, tags, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)"
       (:id row) (:path row) (:type row) (:title row)
       (:summary row) (:tags row) (:created_at row)])
    (jdbc/execute! ds ["DELETE FROM memories_fts WHERE id = ?" (:id row)])
    (jdbc/execute! ds
      ["INSERT INTO memories_fts (id, title, summary, tags) VALUES (?, ?, ?, ?)"
       (:id row) (:title row) (:summary row) (:tags row)])))

;; ---------------------------------------------------------------------------
;; Retrieval query spec
;; ---------------------------------------------------------------------------

(s/def :query/text  string?)
(s/def :query/types (s/coll-of keyword? :kind set?))
(s/def :query/limit pos-int?)
(s/def ::query (s/keys :req [:query/text :query/limit] :opt [:query/types]))

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

(defn- fts5-query
  "Extract alphanumeric words from text and join with OR for safe FTS5 MATCH.
  FTS5 treats ':' as a column specifier, so raw user text (e.g. URLs) must be
  sanitized before passing to MATCH. Returns nil when no valid words remain."
  [text]
  (let [words (->> (str/split text #"\W+") (remove str/blank?))]
    (when (seq words)
      (str/join " OR " words))))

(defn by-keyword
  "FTS5 full-text search over title, summary, tags. Returns up to n records."
  [ds text n]
  (if-let [q (fts5-query text)]
    (mapv row->record
          (jdbc/execute! ds
            ["SELECT m.* FROM memories_fts f
              JOIN memories m ON m.id = f.id
              WHERE memories_fts MATCH ?
              ORDER BY f.rank
              LIMIT ?"
             q n]
            opts))
    []))

;; ---------------------------------------------------------------------------
;; Retrieval with relevance decay scoring
;;
;; score = match_score * exp(-λ * age_days)
;;   match_score  1.0 for FTS keyword hits, 0.5 for recency-only records
;;   λ            ln(2)/30 — 30-day half-life
;;
;; Combined retrieval: union of keyword + recency result sets, deduplication
;; by id (keyword wins with the higher score), sort by score, take top-N.
;; ---------------------------------------------------------------------------

(def ^:private decay-lambda
  "λ for relevance decay: ln(2)/30 gives a 30-day half-life."
  (/ (Math/log 2) 30.0))

(defn- age-days ^double [^Instant created-at]
  (/ (- (.toEpochMilli (Instant/now)) (.toEpochMilli created-at))
     86400000.0))

(defn- score ^double [match-score ^Instant created-at]
  (* (double match-score) (Math/exp (* (- decay-lambda) (age-days created-at)))))

(defn retrieve
  "Combined recency + keyword retrieval with relevance decay scoring.
  Query: {:query/text <string> :query/limit <int> :query/types <keyword-set, optional>}"
  [ds {:query/keys [text types limit] :or {limit 10}}]
  (let [headroom (* limit 3)
        kw-recs  (if (seq text) (by-keyword ds text headroom) [])
        kw-ids   (set (map :memory/id kw-recs))
        rc-recs  (recent ds headroom)
        scored   (concat
                   (map #(assoc % ::score (score 1.0 (:memory/created-at %))) kw-recs)
                   (map #(assoc % ::score (score 0.5 (:memory/created-at %)))
                        (remove #(kw-ids (:memory/id %)) rc-recs)))
        filtered (if (seq types)
                   (filter #(types (:memory/type %)) scored)
                   scored)]
    (->> filtered
         (sort-by ::score >)
         (take limit)
         (mapv #(dissoc % ::score)))))
