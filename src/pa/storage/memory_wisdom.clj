(ns pa.storage.memory-wisdom
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Wisdom writer
;;
;; Manages memory/memory.md — a curated list of permanent facts that is always
;; injected into the system prompt. Unlike the daily append-only writer, this
;; file is a read-modify-write store: new facts are merged in as bullet items
;; and exact duplicates (case-insensitive, whitespace-normalized) are dropped.
;;
;; File format expected / produced:
;;   <!-- header comment ... -->
;;   - fact one
;;   - fact two
;; ---------------------------------------------------------------------------

(def ^:private write-lock (Object.))

(defn- wisdom-file [root]
  (io/file root "memory" "memory.md"))

(defn- read-raw [root]
  (let [f (wisdom-file root)]
    (if (.exists f) (slurp f) "")))

(defn- split-header
  "Split text into [header rest] at the end of the HTML comment block.
  Returns [\"\", text] when no comment is present."
  [text]
  (if-let [idx (str/index-of text "-->")]
    [(subs text 0 (+ idx 3)) (subs text (+ idx 3))]
    ["" text]))

(defn- parse-bullets [text]
  (->> (str/split-lines text)
       (keep #(let [t (str/trim %)]
                (when (str/starts-with? t "- ") t)))))

(defn- normalize [bullet]
  (-> bullet str/trim (str/replace #"^-\s+" "") str/lower-case))

(defn- ensure-bullet [s]
  (let [t (str/trim s)]
    (if (str/starts-with? t "- ") t (str "- " t))))

(defn merge-items!
  "Merge new-facts into memory/memory.md. Each fact may be a plain string or
  already formatted as a bullet ('- ...'). Deduplicates case-insensitively
  against existing bullets. Returns the full updated bullet list."
  [root new-facts]
  (locking write-lock
    (let [raw              (read-raw root)
          [header rest]    (split-header raw)
          existing         (vec (parse-bullets rest))
          seen             (set (map normalize existing))
          fresh            (->> new-facts
                                (map ensure-bullet)
                                (remove #(seen (normalize %))))
          all              (into existing fresh)
          content          (str header "\n"
                                (str/join "\n" all)
                                (when (seq all) "\n"))]
      (io/make-parents (wisdom-file root))
      (spit (wisdom-file root) content)
      all)))

(defn read-items
  "Return all bullet items from memory/memory.md as strings (each starts with '- ')."
  [root]
  (let [[_ rest] (split-header (read-raw root))]
    (vec (parse-bullets rest))))
