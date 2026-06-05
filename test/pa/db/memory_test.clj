(ns pa.db.memory-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [pa.db.memory :as db-memory]
            [pa.db.schema :as schema]
            [pa.memory.records :as records]
            [pa.memory.indexer :as indexer]
            [pa.storage.memory :as storage-memory])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant LocalDate]))

;; ---------------------------------------------------------------------------
;; Fixtures — fresh file-based SQLite db per test (in a temp dir)
;; ---------------------------------------------------------------------------

(def ^:dynamic *ds* nil)
(def ^:dynamic *tmp-root* nil)

(defn- delete-dir! [f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (delete-dir! child)))
  (.delete f))

(defn- with-tmp-dir [f]
  (let [tmp  (Files/createTempDirectory
               "pa-db-test-" (make-array FileAttribute 0))
        root (str tmp)]
    (.mkdirs (io/file root "memory" "daily"))
    (binding [*tmp-root* root]
      (try (f) (finally (delete-dir! (.toFile tmp)))))))

(defn- with-db [f]
  ;; File-based db in the temp dir — SQLite :memory: creates a new DB per connection.
  (.mkdirs (io/file *tmp-root* "sqlite"))
  (let [ds (jdbc/get-datasource (str "jdbc:sqlite:" *tmp-root* "/sqlite/test.db"))]
    (schema/init! ds)
    (binding [*ds* ds]
      (f))))

(use-fixtures :each with-tmp-dir with-db)

(def ^:private opts {:builder-fn rs/as-unqualified-lower-maps})

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-record [overrides]
  (records/make (merge {:memory/type    :episodic
                        :memory/title   "Test"
                        :memory/summary "Something happened."}
                       overrides)))

(defn- write-and-index! [record]
  (let [persisted (storage-memory/write-daily *tmp-root* record (LocalDate/of 2026 5 31))]
    (db-memory/index! *ds* persisted)
    persisted))

;; ---------------------------------------------------------------------------
;; index! / round-trip
;; ---------------------------------------------------------------------------

(deftest index-and-retrieve-single-record
  (testing "indexed record is returned by recent"
    (let [r       (write-and-index! (make-record {}))
          results (db-memory/recent *ds* 10)]
      (is (= 1 (count results)))
      (is (= (:memory/id r)      (:memory/id (first results))))
      (is (= (:memory/type r)    (:memory/type (first results))))
      (is (= (:memory/title r)   (:memory/title (first results))))
      (is (= (:memory/summary r) (:memory/summary (first results)))))))

(deftest index-preserves-tags
  (testing ":memory/tags round-trip through SQLite"
    (write-and-index! (make-record {:memory/tags ["work" "important"]}))
    (is (= ["work" "important"] (:memory/tags (first (db-memory/recent *ds* 1)))))))

(deftest index-upserts-on-same-id
  (testing "indexing the same id twice results in one row"
    (let [r (write-and-index! (make-record {}))]
      (db-memory/index! *ds* (assoc r :memory/title "Updated"))
      (is (= 1 (count (db-memory/recent *ds* 10))))
      (is (= "Updated" (:memory/title (first (db-memory/recent *ds* 1))))))))

;; ---------------------------------------------------------------------------
;; recent
;; ---------------------------------------------------------------------------

(deftest recent-returns-at-most-n
  (testing "recent respects the n limit"
    (dotimes [_ 5] (write-and-index! (make-record {})))
    (is (= 3 (count (db-memory/recent *ds* 3))))))

;; ---------------------------------------------------------------------------
;; by-type
;; ---------------------------------------------------------------------------

(deftest by-type-filters-correctly
  (testing "by-type returns only records of the requested type"
    (write-and-index! (make-record {:memory/type :episodic}))
    (write-and-index! (make-record {:memory/type :semantic}))
    (write-and-index! (make-record {:memory/type :fact}))
    (is (= 1 (count (db-memory/by-type *ds* :episodic))))
    (is (= 1 (count (db-memory/by-type *ds* :semantic))))
    (is (= 1 (count (db-memory/by-type *ds* :fact))))))

(deftest by-type-returns-correct-keyword
  (testing "by-type preserves :memory/type as a keyword"
    (write-and-index! (make-record {:memory/type :semantic}))
    (is (= :semantic (:memory/type (first (db-memory/by-type *ds* :semantic)))))))

;; ---------------------------------------------------------------------------
;; by-tags
;; ---------------------------------------------------------------------------

(deftest by-tags-intersection
  (testing "by-tags returns only records containing ALL specified tags"
    (write-and-index! (make-record {:memory/tags ["work" "important"]}))
    (write-and-index! (make-record {:memory/tags ["work"]}))
    (write-and-index! (make-record {:memory/tags ["personal"]}))
    (is (= 1 (count (db-memory/by-tags *ds* ["work" "important"]))))
    (is (= 2 (count (db-memory/by-tags *ds* ["work"]))))
    (is (= 0 (count (db-memory/by-tags *ds* ["missing"]))))))

;; ---------------------------------------------------------------------------
;; rebuild-memory-index!
;; ---------------------------------------------------------------------------

(deftest rebuild-restores-all-records
  (testing "rebuild-memory-index! re-indexes all records from Markdown files"
    (let [d (LocalDate/of 2026 5 31)]
      (storage-memory/write-daily *tmp-root* (make-record {:memory/title "A"}) d)
      (storage-memory/write-daily *tmp-root* (make-record {:memory/title "B"}) d)
      (storage-memory/write-daily *tmp-root* (make-record {:memory/title "C"}) d))
    (indexer/rebuild-memory-index! *ds* *tmp-root*)
    (is (= 3 (count (db-memory/recent *ds* 10))))))

(deftest rebuild-clears-stale-records
  (testing "rebuild-memory-index! removes records not present in Markdown files"
    ;; Index directly without writing Markdown so the record has no on-disk backing.
    (db-memory/index! *ds* (assoc (make-record {:memory/title "Stale"})
                                  :memory/id   "stale-id"
                                  :memory/path "/fake/path.md"))
    (is (= 1 (count (db-memory/recent *ds* 10))))
    (indexer/rebuild-memory-index! *ds* *tmp-root*)
    (is (= 0 (count (db-memory/recent *ds* 10))))))

;; ---------------------------------------------------------------------------
;; Schema migration — created_at INTEGER, FTS5 table
;; ---------------------------------------------------------------------------

(defn- table-exists? [ds tname]
  (seq (jdbc/execute! ds
         ["SELECT 1 FROM sqlite_master WHERE type='table' AND name=?" tname]
         opts)))

(deftest init-creates-both-tables
  (testing "init! creates memories and memories_fts"
    (is (table-exists? *ds* "memories"))
    (is (table-exists? *ds* "memories_fts"))))

(deftest created-at-round-trips-as-instant
  (testing "created_at is stored as epoch ms and returned as Instant"
    (let [r         (write-and-index! (make-record {}))
          retrieved (first (db-memory/recent *ds* 1))]
      (is (instance? Instant (:memory/created-at retrieved)))
      (is (= (.toEpochMilli ^Instant (:memory/created-at r))
             (.toEpochMilli ^Instant (:memory/created-at retrieved)))))))

(deftest index-writes-to-fts-table
  (testing "index! upserts into memories_fts alongside memories"
    (write-and-index! (make-record {:memory/title "Clojure runtime"}))
    (let [rows (jdbc/execute! *ds*
                 ["SELECT id FROM memories_fts WHERE memories_fts MATCH ?"
                  "Clojure"]
                 opts)]
      (is (= 1 (count rows))))))

(deftest index-fts-upserts-on-same-id
  (testing "indexing the same id twice produces one FTS row, not two"
    (let [r (write-and-index! (make-record {:memory/title "Original"}))]
      (db-memory/index! *ds* (assoc r :memory/title "Updated"))
      (let [rows (jdbc/execute! *ds* ["SELECT id FROM memories_fts"] opts)]
        (is (= 1 (count rows)))))))

;; ---------------------------------------------------------------------------
;; by-keyword (FTS5)
;; ---------------------------------------------------------------------------

(deftest by-keyword-matches-title
  (testing "by-keyword finds records by a word in the title"
    (write-and-index! (make-record {:memory/title "Clojure runtime" :memory/summary "event-driven"}))
    (write-and-index! (make-record {:memory/title "Unrelated topic"  :memory/summary "something else"}))
    (let [results (db-memory/by-keyword *ds* "Clojure" 10)]
      (is (= 1 (count results)))
      (is (= "Clojure runtime" (:memory/title (first results)))))))

(deftest by-keyword-matches-summary
  (testing "by-keyword finds records by a word in the summary"
    (write-and-index! (make-record {:memory/title "Note" :memory/summary "persistent assistant in Clojure"}))
    (is (= 1 (count (db-memory/by-keyword *ds* "persistent" 10))))))

(deftest by-keyword-excludes-non-matching
  (testing "by-keyword returns only records whose FTS columns match"
    (write-and-index! (make-record {:memory/title "Alpha" :memory/summary "first"}))
    (write-and-index! (make-record {:memory/title "Beta"  :memory/summary "second"}))
    (is (= 0 (count (db-memory/by-keyword *ds* "gamma" 10))))))

(deftest by-keyword-respects-limit
  (testing "by-keyword respects the n limit"
    (dotimes [_ 5]
      (write-and-index! (make-record {:memory/title "matching" :memory/summary "same"})))
    (is (= 3 (count (db-memory/by-keyword *ds* "matching" 3))))))

;; ---------------------------------------------------------------------------
;; retrieve — combined retrieval + decay scoring
;; ---------------------------------------------------------------------------

(defn- index-with-age!
  "Index a record directly (no Markdown write) with a synthetic created-at age."
  [record days-ago]
  (let [ms-ago    (* days-ago 86400000)
        old-at    (Instant/ofEpochMilli (- (.toEpochMilli (Instant/now)) ms-ago))
        r         (assoc record :memory/created-at old-at :memory/path "/fake/path.md")]
    (db-memory/index! *ds* r)
    r))

(deftest retrieve-decay-orders-by-recency
  (testing "60-day-old record scores lower than 1-day-old record with same keyword match"
    (index-with-age! (make-record {:memory/title "Clojure old" :memory/summary "old"}) 60)
    (index-with-age! (make-record {:memory/title "Clojure new" :memory/summary "new"}) 1)
    (let [results (db-memory/retrieve *ds* {:query/text "Clojure" :query/limit 10})]
      (is (= 2 (count results)))
      (is (= "Clojure new" (:memory/title (first results))) "newer record ranks first"))))

(deftest retrieve-keyword-match-beats-recency-only
  (testing "keyword-matching record outscores a more-recent recency-only record"
    (index-with-age! (make-record {:memory/title "Matching term" :memory/summary "relevant"}) 10)
    (index-with-age! (make-record {:memory/title "Unrelated"     :memory/summary "other"})    1)
    (let [results (db-memory/retrieve *ds* {:query/text "term" :query/limit 10})]
      (is (= 2 (count results)))
      (is (= "Matching term" (:memory/title (first results)))
          "10-day-old keyword match outscores 1-day-old recency-only"))))

(deftest retrieve-deduplicates-by-id
  (testing "a record in both keyword and recency results appears only once"
    (index-with-age! (make-record {:memory/title "Shared" :memory/summary "both"}) 1)
    (let [results (db-memory/retrieve *ds* {:query/text "Shared" :query/limit 10})]
      (is (= 1 (count results))))))

(deftest retrieve-respects-limit
  (testing "retrieve returns at most :query/limit records"
    (dotimes [i 8]
      (index-with-age! (make-record {:memory/title (str "item-" i) :memory/summary "content"}) i))
    (is (= 3 (count (db-memory/retrieve *ds* {:query/text "" :query/limit 3}))))))

(deftest retrieve-filters-by-type
  (testing ":query/types filters the result set"
    (index-with-age! (make-record {:memory/type :episodic :memory/title "ep"   :memory/summary "x"}) 1)
    (index-with-age! (make-record {:memory/type :semantic :memory/title "sem"  :memory/summary "x"}) 1)
    (let [results (db-memory/retrieve *ds* {:query/text "" :query/limit 10 :query/types #{:semantic}})]
      (is (= 1 (count results)))
      (is (= :semantic (:memory/type (first results)))))))

(deftest rebuild-recreates-fts-table
  (testing "rebuild-memory-index! drops and recreates memories_fts"
    (let [d (LocalDate/of 2026 5 31)]
      (storage-memory/write-daily *tmp-root* (make-record {:memory/title "Alpha"}) d)
      (storage-memory/write-daily *tmp-root* (make-record {:memory/title "Beta"}) d))
    (indexer/rebuild-memory-index! *ds* *tmp-root*)
    (is (table-exists? *ds* "memories_fts"))
    (is (= 2 (count (jdbc/execute! *ds* ["SELECT id FROM memories_fts"] opts))))))
