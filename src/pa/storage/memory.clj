(ns pa.storage.memory
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------------------------
;; Markdown serialization
;;
;; Each memory record is appended to a daily file as a fenced EDN block so
;; the files remain human-readable and are git-diffable:
;;
;;   ## <title>
;;   <!-- memory:edn
;;   {:memory/id "..." :memory/type :episodic ...}
;;   -->
;;
;;   <summary prose>
;;
;;   ---
;;
;; The EDN block carries the full machine-readable record; the prose below it
;; is the canonical summary rendered for human readers and the LLM context.
;; ---------------------------------------------------------------------------

(def ^:private edn-open  "<!-- memory:edn")
(def ^:private edn-close "-->")
(def ^:private separator "---")

(defn- record->markdown [record]
  (str "## " (:memory/title record) "\n"
       edn-open "\n"
       (pr-str record) "\n"
       edn-close "\n\n"
       (:memory/summary record) "\n\n"
       separator "\n"))

(defn- parse-edn-block [text]
  (let [open-idx  (str/index-of text edn-open)
        close-idx (when open-idx (str/index-of text edn-close (+ open-idx (count edn-open))))]
    (when (and open-idx close-idx)
      (let [raw (str/trim (subs text (+ open-idx (count edn-open)) close-idx))]
        (edn/read-string raw)))))

(defn- split-records [text]
  (let [sections (str/split text (re-pattern (str "\n" separator "\n?")))]
    (->> sections
         (remove str/blank?)
         (keep parse-edn-block))))

;; ---------------------------------------------------------------------------
;; Path helpers
;; ---------------------------------------------------------------------------

(defn- daily-path [root ^LocalDate date]
  (str root "/memory/daily/"
       (.format date (DateTimeFormatter/ofPattern "yyyy-MM-dd"))
       ".md"))

(defn- today [] (LocalDate/now))

;; ---------------------------------------------------------------------------
;; write-daily
;;
;; Appends a memory record to the daily Markdown file for date (defaults to
;; today). Sets :memory/path on the record before persisting.
;; Returns the record as written (with :memory/path stamped).
;; ---------------------------------------------------------------------------

(def ^:private write-lock (Object.))

(defn write-daily
  "Append record to memory/daily/YYYY-MM-DD.md. Returns the record with :memory/path set."
  ([root record] (write-daily root record (today)))
  ([root record date]
   (let [path   (daily-path root date)
         record (assoc record :memory/path path)]
     (locking write-lock
       (io/make-parents (io/file path))
       (spit path (str (record->markdown record) "\n") :append true))
     record)))

;; ---------------------------------------------------------------------------
;; read-daily
;;
;; Parse all memory records from the daily file for date. Returns a vector.
;; Returns [] when the file is missing or empty.
;; ---------------------------------------------------------------------------

(defn read-daily
  "Parse all memory records from memory/daily/YYYY-MM-DD.md. Returns a vector."
  ([root date]
   (let [f (io/file (daily-path root date))]
     (if (.exists f)
       (vec (split-records (slurp f)))
       [])))
  ([root]
   (read-daily root (today))))

;; ---------------------------------------------------------------------------
;; read-all-daily
;;
;; Scan all daily Markdown files in memory/daily/ and return every record.
;; This is an admin/rebuild operation — not for hot-path use.
;; ---------------------------------------------------------------------------

(defn read-all-daily
  "Scan memory/daily/ and return all memory records across all daily files."
  [root]
  (let [dir (io/file root "memory" "daily")]
    (if (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName %) ".md"))
           (sort-by #(.getName %))
           (mapcat #(vec (split-records (slurp %))))
           vec)
      [])))
