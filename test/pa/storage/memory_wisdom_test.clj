(ns pa.storage.memory-wisdom-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pa.storage.memory-wisdom :as wisdom]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:dynamic *root* nil)

(defn- delete-dir! [f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (delete-dir! child)))
  (.delete f))

(defn- with-tmp-dir [f]
  (let [tmp  (java.nio.file.Files/createTempDirectory
              "pa-wisdom-test-" (make-array java.nio.file.attribute.FileAttribute 0))
        root (str tmp)]
    (.mkdirs (io/file root "memory"))
    (binding [*root* root]
      (try (f) (finally (delete-dir! (io/file root)))))))

(use-fixtures :each with-tmp-dir)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private header
  "<!-- Curated permanent facts.\n     Always injected into context. -->")

(defn- seed! [content]
  (spit (io/file *root* "memory" "memory.md") content))

(defn- read-raw []
  (slurp (io/file *root* "memory" "memory.md")))

;; ---------------------------------------------------------------------------
;; merge-items!
;; ---------------------------------------------------------------------------

(deftest merge-into-empty-file
  (testing "facts are written when the file has no existing bullets"
    (seed! (str header "\n"))
    (let [result (wisdom/merge-items! *root* ["cats are great" "dogs too"])]
      (is (= ["- cats are great" "- dogs too"] result))
      (is (str/includes? (read-raw) "- cats are great"))
      (is (str/includes? (read-raw) "- dogs too")))))

(deftest merge-preserves-existing-bullets
  (testing "pre-existing bullets are retained after merge"
    (seed! (str header "\n- existing fact\n"))
    (wisdom/merge-items! *root* ["new fact"])
    (let [items (wisdom/read-items *root*)]
      (is (= 2 (count items)))
      (is (some #(= "- existing fact" %) items))
      (is (some #(= "- new fact" %) items)))))

(deftest merge-deduplicates-exact-match
  (testing "an identical fact is not added twice"
    (seed! (str header "\n- existing fact\n"))
    (wisdom/merge-items! *root* ["existing fact"])
    (is (= 1 (count (wisdom/read-items *root*))))))

(deftest merge-deduplicates-case-insensitive
  (testing "duplicates are detected regardless of case"
    (seed! (str header "\n- Cats Are Great\n"))
    (wisdom/merge-items! *root* ["cats are great" "CATS ARE GREAT"])
    (is (= 1 (count (wisdom/read-items *root*))))))

(deftest merge-accepts-bullet-prefixed-input
  (testing "input already formatted as '- ...' is handled correctly"
    (seed! (str header "\n"))
    (wisdom/merge-items! *root* ["- already a bullet"])
    (is (= ["- already a bullet"] (wisdom/read-items *root*)))))

(deftest merge-deduplicates-bullet-prefixed-against-plain
  (testing "bullet-prefixed input deduplicates against existing plain item"
    (seed! (str header "\n- same fact\n"))
    (wisdom/merge-items! *root* ["- same fact"])
    (is (= 1 (count (wisdom/read-items *root*))))))

(deftest merge-preserves-header-comment
  (testing "the HTML comment header is preserved verbatim after merge"
    (seed! (str header "\n"))
    (wisdom/merge-items! *root* ["some fact"])
    (is (str/starts-with? (read-raw) header))))

(deftest merge-creates-file-when-missing
  (testing "merge-items! creates the file if it does not exist"
    (wisdom/merge-items! *root* ["brand new"])
    (is (.exists (io/file *root* "memory" "memory.md")))
    (is (= ["- brand new"] (wisdom/read-items *root*)))))

(deftest merge-returns-full-updated-list
  (testing "return value is the complete bullet list after merge"
    (seed! (str header "\n- first\n"))
    (let [result (wisdom/merge-items! *root* ["second" "third"])]
      (is (= ["- first" "- second" "- third"] result)))))

;; ---------------------------------------------------------------------------
;; read-items
;; ---------------------------------------------------------------------------

(deftest read-items-from-populated-file
  (testing "read-items returns all bullet strings from the file"
    (seed! (str header "\n- alpha\n- beta\n- gamma\n"))
    (is (= ["- alpha" "- beta" "- gamma"] (wisdom/read-items *root*)))))

(deftest read-items-empty-file-returns-empty
  (testing "read-items returns [] when no bullets are present"
    (seed! (str header "\n"))
    (is (= [] (wisdom/read-items *root*)))))

(deftest read-items-missing-file-returns-empty
  (testing "read-items returns [] when the file does not exist"
    (is (= [] (wisdom/read-items *root*)))))
