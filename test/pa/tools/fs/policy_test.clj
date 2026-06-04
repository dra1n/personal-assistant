(ns pa.tools.fs.policy-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.tools.fs.policy :as policy])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temp data-root fixture (paths are canonicalized, so tests run against real
;; directories under a throwaway root).
;; ---------------------------------------------------------------------------

(def ^:dynamic *root* nil)

(defn- delete-dir! [f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)] (delete-dir! child)))
  (.delete f))

(use-fixtures :each
  (fn [f]
    (let [tmp (Files/createTempDirectory "pa-policy-" (make-array FileAttribute 0))]
      (binding [*root* (str tmp)]
        (try (f) (finally (delete-dir! (io/file (str tmp)))))))))

(defn- mkdirs [& segs] (.mkdirs (apply io/file *root* segs)))
(defn- at [& segs] (.getPath (apply io/file *root* segs)))

;; Wrap bare allowlist lines in the fenced block build-policy expects.
(defn- pol [lines] (policy/build-policy *root* (str "```allowlist\n" lines "```\n")))

;; ---------------------------------------------------------------------------
;; Parsing
;; ---------------------------------------------------------------------------

(deftest parse-extracts-allowlist-block-only
  (testing "only lines inside the fenced ```allowlist block are parsed"
    (let [text (str "# Title\n"
                    "prose with read write words\n\n"
                    "```allowlist\n"
                    "# a comment\n"
                    "~/Projects   read write\n"
                    "~/.ssh       deny\n"
                    "docs         read\n"
                    "```\n\n"
                    "trailing prose write\n")]
      (is (= [{:path "~/Projects" :caps #{:read :write}}
              {:path "~/.ssh"     :caps #{:deny}}
              {:path "docs"       :caps #{:read}}]
             (policy/parse-allowlist text))))))

(deftest parse-empty-when-no-block
  (is (= [] (policy/parse-allowlist "no fenced block here\nread write\n"))))

;; ---------------------------------------------------------------------------
;; Capability matrix — read/write are independent per root
;; ---------------------------------------------------------------------------

(deftest capability-matrix
  (mkdirs "projects") (mkdirs "docs") (mkdirs "outbox")
  (let [p (pol "projects read write\ndocs read\noutbox write\n")]
    (testing "a read+write root grants both"
      (is (policy/capable? p (at "projects" "a.txt") :read))
      (is (policy/capable? p (at "projects" "a.txt") :write)))
    (testing "a read-only root refuses writes"
      (is (policy/capable? p (at "docs" "a.txt") :read))
      (is (not (policy/capable? p (at "docs" "a.txt") :write))))
    (testing "a write-only root refuses reads — write does not imply read"
      (is (policy/capable? p (at "outbox" "a.txt") :write))
      (is (not (policy/capable? p (at "outbox" "a.txt") :read))))))

(deftest default-deny-for-unmatched-paths
  (let [p (pol "projects read write\n")]
    (is (not (policy/capable? p (at "elsewhere" "a.txt") :read)) "no matching root")
    (is (not (policy/capable? p "/etc/passwd" :read)) "absolute path outside all roots")))

;; ---------------------------------------------------------------------------
;; Precedence — deny wins; otherwise longest prefix
;; ---------------------------------------------------------------------------

(deftest deny-wins-over-broader-allow
  (let [p (pol "projects read write\nprojects/secret deny\n")]
    (is (policy/capable? p (at "projects" "ok.txt") :read))
    (is (not (policy/capable? p (at "projects" "secret" "k.txt") :read))
        "deny root beats the broader allow it sits inside")
    (is (not (policy/capable? p (at "projects" "secret" "k.txt") :write)))))

(deftest longest-prefix-root-wins
  (let [p (pol "projects read\nprojects/pub read write\n")]
    (is (not (policy/capable? p (at "projects" "x.txt") :write))
        "outer root is read-only")
    (is (policy/capable? p (at "projects" "pub" "x.txt") :write)
        "the more specific root grants write")))

;; ---------------------------------------------------------------------------
;; Adversarial canonicalization — traversal & symlink escape
;; ---------------------------------------------------------------------------

(deftest dotdot-traversal-is-rejected
  (let [p (pol "projects read write\n")]
    (is (not (policy/capable? p (at "projects" ".." "outside.txt") :read))
        "../ is resolved to a real path before matching, escaping the root → denied")))

(deftest symlink-escape-is-rejected
  (mkdirs "projects")
  (mkdirs "secretdata")
  (Files/createSymbolicLink (.toPath (io/file *root* "projects" "link"))
                            (.toPath (io/file *root* "secretdata"))
                            (make-array FileAttribute 0))
  (let [p (pol "projects read write\n")]
    (is (not (policy/capable? p (at "projects" "link" "file.txt") :read))
        "a symlink under an allowed root pointing outside resolves out → denied")))

;; ---------------------------------------------------------------------------
;; check — returns the safe canonical path or throws
;; ---------------------------------------------------------------------------

(deftest check-returns-canonical-path-on-grant
  (mkdirs "projects")
  (let [p (pol "projects read write\n")]
    (is (= (.getCanonicalPath (io/file *root* "projects" "a.txt"))
           (policy/check p (at "projects" ".." "projects" "a.txt") :read))
        "check returns the canonicalized path, not the raw argument")))

(deftest root-path-detects-allowlist-roots
  (mkdirs "ws")
  (let [p (pol "ws read write\n")]
    (is (policy/root-path? p (.getCanonicalPath (io/file *root* "ws")))
        "the root itself is a root")
    (is (not (policy/root-path? p (.getCanonicalPath (io/file *root* "ws" "child"))))
        "a path under the root is not the root")))

(deftest check-throws-access-denied-on-refusal
  (let [p (pol "projects read\n")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"access denied"
                          (policy/check p (at "projects" "a.txt") :write)))
    (try
      (policy/check p (at "projects" "a.txt") :write)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :tool/access-denied (:type (ex-data e))))))))

;; ---------------------------------------------------------------------------
;; load-policy — disk integration & the shipped default template
;; ---------------------------------------------------------------------------

(deftest load-policy-missing-file-denies-everything
  (let [p (policy/load-policy *root*)]
    (is (= [] (:roots p)))
    (is (not (policy/capable? p (at "anything.txt") :read)))))

(deftest load-policy-reads-tools-md
  (mkdirs "system")
  (spit (io/file *root* "system" "tools.md")
        "```allowlist\nprojects read write\n```\n")
  (let [p (policy/load-policy *root*)]
    (is (policy/capable? p (at "projects" "a.txt") :write))))

(deftest default-template-grants-workspace-only
  (testing "the shipped tools.md template grants read+write on the workspace
            sandbox only — not the assistant's own data root files"
    (let [text (slurp (io/resource "templates/system/tools.md"))
          p    (policy/build-policy *root* text)]
      (is (policy/capable? p (at "workspace" "note.txt") :read))
      (is (policy/capable? p (at "workspace" "note.txt") :write))
      (testing "runtime state outside the workspace is not tool-writable"
        (is (not (policy/capable? p (at "events" "events.edn") :write)))
        (is (not (policy/capable? p (at "identity" "identity.md") :write)))
        (is (not (policy/capable? p (at "sqlite" "assistant.db") :write))))
      (is (not (policy/capable? p "/etc/passwd" :read))))))
