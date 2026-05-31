(ns pa.storage.memory-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [pa.memory.records :as records]
            [pa.storage.memory :as memory])
  (:import [java.time LocalDate]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:dynamic *tmp-root* nil)

(defn- delete-dir! [f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (delete-dir! child)))
  (.delete f))

(defn- with-tmp-dir [f]
  (let [tmp  (java.nio.file.Files/createTempDirectory
              "pa-memory-test-" (make-array java.nio.file.attribute.FileAttribute 0))
        root (str tmp)]
    (.mkdirs (io/file root "memory" "daily"))
    (binding [*tmp-root* root]
      (try (f) (finally (delete-dir! (io/file root)))))))

(use-fixtures :each with-tmp-dir)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-record [overrides]
  (records/make (merge {:memory/type    :episodic
                        :memory/title   "Test memory"
                        :memory/summary "Something happened."}
                       overrides)))

(def ^:private test-date (LocalDate/of 2026 5 31))

;; ---------------------------------------------------------------------------
;; write-daily / read-daily round-trip
;; ---------------------------------------------------------------------------

(deftest write-and-read-single-record
  (testing "a written record can be read back with equivalent fields"
    (let [original  (make-record {})
          persisted (memory/write-daily *tmp-root* original test-date)
          results   (memory/read-daily *tmp-root* test-date)]
      (is (= 1 (count results)))
      (let [r (first results)]
        (is (= (:memory/id original)      (:memory/id r)))
        (is (= (:memory/type original)    (:memory/type r)))
        (is (= (:memory/title original)   (:memory/title r)))
        (is (= (:memory/summary original) (:memory/summary r)))
        (is (= (:memory/path persisted)   (:memory/path r)))))))

(deftest write-daily-stamps-path
  (testing "write-daily sets :memory/path on the returned record"
    (let [r (memory/write-daily *tmp-root* (make-record {}) test-date)]
      (is (string? (:memory/path r)))
      (is (.contains (:memory/path r) "2026-05-31")))))

(deftest write-daily-appends-multiple-records
  (testing "multiple writes to the same day accumulate as separate records"
    (memory/write-daily *tmp-root* (make-record {:memory/title "First"})  test-date)
    (memory/write-daily *tmp-root* (make-record {:memory/title "Second"}) test-date)
    (memory/write-daily *tmp-root* (make-record {:memory/title "Third"})  test-date)
    (let [results (memory/read-daily *tmp-root* test-date)]
      (is (= 3 (count results)))
      (is (= ["First" "Second" "Third"] (mapv :memory/title results))))))

(deftest read-daily-missing-file-returns-empty
  (testing "read-daily returns [] when no file exists for that date"
    (is (= [] (memory/read-daily *tmp-root* (LocalDate/of 2000 1 1))))))

(deftest write-and-read-all-record-types
  (testing "all three memory types round-trip correctly"
    (doseq [t [:episodic :semantic :fact]]
      (memory/write-daily *tmp-root* (make-record {:memory/type t :memory/title (name t)}) test-date))
    (let [results (memory/read-daily *tmp-root* test-date)
          types   (set (map :memory/type results))]
      (is (= #{:episodic :semantic :fact} types)))))

(deftest write-preserves-tags
  (testing ":memory/tags round-trip through Markdown serialization"
    (memory/write-daily *tmp-root* (make-record {:memory/tags ["work" "important"]}) test-date)
    (let [r (first (memory/read-daily *tmp-root* test-date))]
      (is (= ["work" "important"] (:memory/tags r))))))

;; ---------------------------------------------------------------------------
;; read-all-daily
;; ---------------------------------------------------------------------------

(deftest read-all-daily-spans-multiple-files
  (testing "read-all-daily returns records from every daily file"
    (let [d1 (LocalDate/of 2026 5 29)
          d2 (LocalDate/of 2026 5 30)
          d3 (LocalDate/of 2026 5 31)]
      (memory/write-daily *tmp-root* (make-record {:memory/title "Day 1a"}) d1)
      (memory/write-daily *tmp-root* (make-record {:memory/title "Day 1b"}) d1)
      (memory/write-daily *tmp-root* (make-record {:memory/title "Day 2"})  d2)
      (memory/write-daily *tmp-root* (make-record {:memory/title "Day 3"})  d3)
      (let [all (memory/read-all-daily *tmp-root*)]
        (is (= 4 (count all)))
        (is (= ["Day 1a" "Day 1b" "Day 2" "Day 3"] (mapv :memory/title all)))))))

(deftest read-all-daily-empty-dir-returns-empty
  (testing "read-all-daily returns [] when no daily files exist"
    (is (= [] (memory/read-all-daily *tmp-root*)))))
