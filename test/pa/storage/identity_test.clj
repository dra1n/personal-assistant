(ns pa.storage.identity-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [pa.storage.identity :as identity]))

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
              "pa-identity-test-" (make-array java.nio.file.attribute.FileAttribute 0))
        root (str tmp)]
    (.mkdirs (io/file root "identity"))
    (binding [*tmp-root* root]
      (try (f) (finally (delete-dir! (io/file root)))))))

(use-fixtures :each with-tmp-dir)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- write-identity! [filename content]
  (spit (io/file *tmp-root* "identity" filename) content))

(defn- identity-path [filename]
  (str *tmp-root* "/identity/" filename))

;; ---------------------------------------------------------------------------
;; load-identity-file
;; ---------------------------------------------------------------------------

(deftest load-identity-file-parses-front-matter
  (testing "scalar fields are parsed from YAML front-matter"
    (write-identity! "identity.md"
      "---\nname: Aria\ncommunication-style: concise\n---\n\nProse here.")
    (let [result (identity/load-identity-file (identity-path "identity.md"))]
      (is (= "Aria"    (get-in result [:front-matter :name])))
      (is (= "concise" (get-in result [:front-matter :communication-style]))))))

(deftest load-identity-file-parses-sequence-fields
  (testing "YAML sequence fields become vectors"
    (write-identity! "identity.md"
      "---\ntraits:\n  - curious\n  - helpful\nvalues:\n  - honesty\n---")
    (let [fm (:front-matter (identity/load-identity-file (identity-path "identity.md")))]
      (is (= ["curious" "helpful"] (:traits fm)))
      (is (= ["honesty"] (:values fm))))))

(deftest load-identity-file-keywordizes-keys
  (testing "all front-matter keys are keywords"
    (write-identity! "identity.md"
      "---\nversion: 1\nrole: assistant\npurpose: help\n---")
    (let [fm (:front-matter (identity/load-identity-file (identity-path "identity.md")))]
      (is (every? keyword? (keys fm))))))

(deftest load-identity-file-extracts-prose
  (testing "prose below the closing --- is returned separately"
    (write-identity! "user.md"
      "---\nname: Alice\n---\n\nSome prose about the user.")
    (let [result (identity/load-identity-file (identity-path "user.md"))]
      (is (= "Some prose about the user." (:prose result))))))

(deftest load-identity-file-returns-source-filename
  (testing ":source is the filename of the file"
    (write-identity! "agents.md" "---\nagents: []\n---")
    (let [result (identity/load-identity-file (identity-path "agents.md"))]
      (is (= "agents.md" (:source result))))))

(deftest load-identity-file-empty-front-matter
  (testing "file with no front-matter returns empty map"
    (write-identity! "identity.md" "Just prose, no front-matter.")
    (let [result (identity/load-identity-file (identity-path "identity.md"))]
      (is (= {} (:front-matter result))))))

(deftest load-identity-file-missing-file-returns-empty
  (testing "non-existent file returns empty front-matter and prose"
    (let [result (identity/load-identity-file (str *tmp-root* "/identity/nonexistent.md"))]
      (is (= {} (:front-matter result)))
      (is (= "" (:prose result))))))

;; ---------------------------------------------------------------------------
;; load-all
;; ---------------------------------------------------------------------------

(deftest load-all-returns-all-keys
  (testing "load-all returns a map with :identity :user :agents keys"
    (doseq [[f content]
            [["identity.md" "---\nversion: 1\nname: Aria\nrole: assistant\n---"]
             ["user.md"     "---\nname: Alice\ntimezone: UTC\n---"]
             ["agents.md"   "---\nagents: []\n---"]]]
      (write-identity! f content))
    (let [ctx (identity/load-all *tmp-root*)]
      (is (contains? ctx :identity))
      (is (contains? ctx :user))
      (is (contains? ctx :agents))
      (is (not (contains? ctx :soul))))))

(deftest load-all-merges-front-matter-under-named-keys
  (testing "each key holds the parsed front-matter (and prose) for that file"
    (write-identity! "identity.md" "---\nname: Aria\nrole: assistant\n---\n\nIdentity prose.")
    (write-identity! "user.md"     "---\nname: Alice\n---")
    (write-identity! "agents.md"   "---\nagents: []\n---")
    (let [ctx (identity/load-all *tmp-root*)]
      (is (= "Aria"            (get-in ctx [:identity :front-matter :name])))
      (is (= "assistant"       (get-in ctx [:identity :front-matter :role])))
      (is (= "Identity prose." (get-in ctx [:identity :prose])))
      (is (= "Alice"           (get-in ctx [:user :front-matter :name])))
      (is (= []                (get-in ctx [:agents :front-matter :agents]))))))

(deftest load-all-tolerates-missing-files
  (testing "missing files produce empty front-matter rather than throwing"
    (let [ctx (identity/load-all *tmp-root*)]
      (is (= {} (get-in ctx [:identity :front-matter])))
      (is (= "" (get-in ctx [:identity :prose])))
      (is (= {} (get-in ctx [:user :front-matter]))))))
