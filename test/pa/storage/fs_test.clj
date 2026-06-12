(ns pa.storage.fs-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [pa.storage.fs :as fs]))

(def ^:dynamic *tmp-root* nil)

(defn- delete-dir! [f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (delete-dir! child)))
  (.delete f))

(defn- with-tmp-dir [f]
  (let [tmp (java.nio.file.Files/createTempDirectory
             "pa-test-" (make-array java.nio.file.attribute.FileAttribute 0))
        root (str tmp)]
    (binding [*tmp-root* root]
      (try (f) (finally (delete-dir! (io/file root)))))))

(use-fixtures :each with-tmp-dir)

(deftest bootstrap-creates-directory-structure
  (testing "all required directories exist after bootstrap"
    (fs/bootstrap! *tmp-root*)
    (doseq [path ["identity"
                  "memory/daily"
                  "cognition/reflections"
                  "tasks/scheduled"
                  "tasks/completed"
                  "events"
                  "system"
                  "workspace"
                  "sqlite"]]
      (is (.isDirectory (io/file *tmp-root* path)) (str path " should be a directory")))))

(deftest bootstrap-creates-identity-templates
  (testing "identity template files are created with content"
    (fs/bootstrap! *tmp-root*)
    (doseq [filename ["identity.md" "user.md" "agents.md"]]
      (let [f (io/file *tmp-root* "identity" filename)]
        (is (.exists f) (str filename " should exist"))
        (is (pos? (.length f)) (str filename " should not be empty"))))))

(deftest bootstrap-creates-system-templates
  (testing "system/tools.md is created with the default allowlist content"
    (fs/bootstrap! *tmp-root*)
    (let [f (io/file *tmp-root* "system" "tools.md")]
      (is (.exists f) "system/tools.md should exist")
      (is (pos? (.length f)) "system/tools.md should not be empty")
      (is (re-find #"(?m)```allowlist" (slurp f)) "ships with an allowlist block"))))

(deftest bootstrap-creates-memory-wisdom-template
  (testing "memory/memory.md is created with content"
    (fs/bootstrap! *tmp-root*)
    (let [f (io/file *tmp-root* "memory" "memory.md")]
      (is (.exists f) "memory/memory.md should exist")
      (is (pos? (.length f)) "memory/memory.md should not be empty"))))

(deftest bootstrap-creates-event-log
  (testing "empty events.edn is created"
    (fs/bootstrap! *tmp-root*)
    (is (.exists (io/file *tmp-root* "events" "events.edn")))))

(deftest bootstrap-is-idempotent
  (testing "running bootstrap twice does not overwrite existing files"
    (fs/bootstrap! *tmp-root*)
    (let [identity (io/file *tmp-root* "identity" "identity.md")
          tools    (io/file *tmp-root* "system" "tools.md")]
      (spit identity "custom content")
      (spit tools "custom allowlist")
      (fs/bootstrap! *tmp-root*)
      (is (= "custom content" (slurp identity)))
      (is (= "custom allowlist" (slurp tools)) "an edited tools.md is preserved"))))
