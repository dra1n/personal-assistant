(ns pa.storage.history-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [pa.storage.history :as history]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:dynamic *history-path* nil)

(defn- with-tmp-history-file [f]
  (let [tmp  (java.nio.file.Files/createTempFile
               "pa-history-test-" ".edn"
               (make-array java.nio.file.attribute.FileAttribute 0))
        path (str tmp)]
    (binding [*history-path* path]
      (try
        (f)
        (finally (.delete (io/file path)))))))

(use-fixtures :each with-tmp-history-file)

;; ---------------------------------------------------------------------------
;; make-entry
;; ---------------------------------------------------------------------------

(deftest make-entry-has-required-fields
  (testing "make-entry returns a map with :history/id, :history/text, :history/timestamp"
    (let [entry (history/make-entry "hello")]
      (is (uuid?   (:history/id entry)))
      (is (= "hello" (:history/text entry)))
      (is (inst?   (:history/timestamp entry))))))

;; ---------------------------------------------------------------------------
;; append-entry! / load-history round-trip
;; ---------------------------------------------------------------------------

(deftest append-and-load-single-entry
  (testing "a single appended entry is returned by load-history"
    (let [entry (history/make-entry "ls -la")]
      (history/append-entry! *history-path* entry)
      (let [loaded (history/load-history *history-path*)]
        (is (= 1 (count loaded)))
        (is (= "ls -la" (:history/text (first loaded))))))))

(deftest load-history-returns-empty-for-blank-file
  (testing "empty file yields an empty vector"
    (is (= [] (history/load-history *history-path*)))))

(deftest load-history-returns-empty-for-missing-file
  (testing "non-existent path yields an empty vector"
    (is (= [] (history/load-history "/tmp/pa-no-such-history-test.edn")))))

(deftest round-trip-preserves-all-fields
  (testing "all entry fields survive the EDN serialization round-trip"
    (let [id    #uuid "cafebabe-dead-beef-cafe-babe00000001"
          entry {:history/id        id
                 :history/text      "git status"
                 :history/timestamp (java.time.Instant/parse "2026-01-15T10:30:00Z")}]
      (history/append-entry! *history-path* entry)
      (let [loaded (first (history/load-history *history-path*))]
        (is (= id          (:history/id loaded)))
        (is (= "git status" (:history/text loaded)))
        (is (inst?         (:history/timestamp loaded)))))))

;; ---------------------------------------------------------------------------
;; load-history trims to last 50 entries
;; ---------------------------------------------------------------------------

(deftest load-history-returns-last-50-when-more-exist
  (testing "only the last 50 entries are returned when the file has more than 50"
    (let [n 60]
      (doseq [i (range n)]
        (history/append-entry! *history-path*
                               {:history/id        (random-uuid)
                                :history/text      (str "cmd-" i)
                                :history/timestamp (java.time.Instant/now)}))
      (let [loaded (history/load-history *history-path*)]
        (is (= history/history-limit (count loaded)))
        ;; must be the LAST 50 — cmd-10 through cmd-59
        (is (= "cmd-10" (:history/text (first loaded))))
        (is (= "cmd-59" (:history/text (last loaded))))))))

(deftest load-history-returns-all-when-fewer-than-50
  (testing "when fewer than 50 entries exist all are returned"
    (doseq [i (range 5)]
      (history/append-entry! *history-path*
                             {:history/id        (random-uuid)
                              :history/text      (str "cmd-" i)
                              :history/timestamp (java.time.Instant/now)}))
    (is (= 5 (count (history/load-history *history-path*))))))
