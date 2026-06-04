(ns pa.tools.fs-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.runtime.executor :as executor]
            [pa.tools.fs :as fs]
            [pa.tools.fs.policy :as policy]
            [pa.tools.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Temp data-root fixture
;; ---------------------------------------------------------------------------

(def ^:dynamic *root* nil)

(defn- delete-dir! [f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)] (delete-dir! child)))
  (.delete f))

(use-fixtures :each
  (fn [f]
    (let [tmp (java.nio.file.Files/createTempDirectory
               "pa-fs-" (make-array java.nio.file.attribute.FileAttribute 0))]
      (binding [*root* (str tmp)]
        (try (f) (finally (delete-dir! (io/file (str tmp)))))))))

(defn- at [& segs] (.getPath (apply io/file *root* segs)))
(defn- pol [lines] (policy/build-policy *root* (str "```allowlist\n" lines "```\n")))

;; A policy granting read+write on ws/, read-only on ro/.
(defn- ctx []
  (.mkdirs (io/file *root* "ws"))
  (.mkdirs (io/file *root* "ro"))
  {:tool.fs/policy (pol "ws read write\nro read\n")})

;; ---------------------------------------------------------------------------
;; read-file / write-file
;; ---------------------------------------------------------------------------

(deftest write-then-read-round-trips
  (let [c (ctx)]
    (let [w (fs/write-file {:path (at "ws" "note.txt") :content "hello"} c)]
      (is (= 5 (:bytes-written w)))
      (is (= (.getCanonicalPath (io/file *root* "ws" "note.txt")) (:path w))))
    (is (= "hello" (:content (fs/read-file {:path (at "ws" "note.txt")} c))))))

(deftest write-file-creates-parent-dirs
  (let [c (ctx)]
    (fs/write-file {:path (at "ws" "a" "b" "c.txt") :content "deep"} c)
    (is (= "deep" (slurp (io/file *root* "ws" "a" "b" "c.txt"))))))

(deftest read-file-denied-outside-allowlist
  (let [c (ctx)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"access denied"
                          (fs/read-file {:path (at "elsewhere" "x.txt")} c)))))

(deftest write-file-to-read-only-root-is-refused-and-writes-nothing
  (let [c      (ctx)
        target (at "ro" "x.txt")]
    (is (thrown? clojure.lang.ExceptionInfo
                 (fs/write-file {:path target :content "z"} c)))
    (is (not (.exists (io/file target))) "no file created on a refused write")))

;; ---------------------------------------------------------------------------
;; list-dir
;; ---------------------------------------------------------------------------

(deftest list-dir-returns-sorted-typed-entries
  (let [c (ctx)]
    (spit (io/file *root* "ws" "b.txt") "")
    (spit (io/file *root* "ws" "a.txt") "")
    (.mkdirs (io/file *root* "ws" "sub"))
    (is (= [{:name "a.txt" :type :file}
            {:name "b.txt" :type :file}
            {:name "sub"   :type :dir}]
           (:entries (fs/list-dir {:path (at "ws")} c))))))

(deftest list-dir-on-non-directory-errors
  (let [c (ctx)]
    (spit (io/file *root* "ws" "f.txt") "x")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a directory"
                          (fs/list-dir {:path (at "ws" "f.txt")} c)))))

(deftest list-dir-denied-outside-allowlist
  (let [c (ctx)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (fs/list-dir {:path (at "elsewhere")} c)))))

;; ---------------------------------------------------------------------------
;; make-dir
;; ---------------------------------------------------------------------------

(deftest make-dir-creates-nested-and-is-idempotent
  (let [c (ctx)]
    (is (true? (:created (fs/make-dir {:path (at "ws" "a" "b" "c")} c))))
    (is (.isDirectory (io/file *root* "ws" "a" "b" "c")))
    (is (false? (:created (fs/make-dir {:path (at "ws" "a" "b" "c")} c))) "idempotent")))

(deftest make-dir-denied-on-read-only-root
  (is (thrown? clojure.lang.ExceptionInfo (fs/make-dir {:path (at "ro" "x")} (ctx)))))

;; ---------------------------------------------------------------------------
;; delete
;; ---------------------------------------------------------------------------

(deftest delete-file-and-empty-dir
  (let [c (ctx)]
    (spit (io/file *root* "ws" "f.txt") "x")
    (is (true? (:deleted (fs/delete {:path (at "ws" "f.txt")} c))))
    (is (not (.exists (io/file *root* "ws" "f.txt"))))
    (.mkdirs (io/file *root* "ws" "empty"))
    (is (true? (:deleted (fs/delete {:path (at "ws" "empty")} c))))))

(deftest delete-missing-path-is-idempotent
  (is (false? (:deleted (fs/delete {:path (at "ws" "nope.txt")} (ctx))))))

(deftest delete-nonempty-dir-requires-recursive
  (let [c (ctx)]
    (.mkdirs (io/file *root* "ws" "d"))
    (spit (io/file *root* "ws" "d" "f.txt") "x")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not empty"
                          (fs/delete {:path (at "ws" "d")} c)))
    (is (.exists (io/file *root* "ws" "d" "f.txt")) "refused delete removes nothing")
    (is (true? (:deleted (fs/delete {:path (at "ws" "d") :recursive true} c))))
    (is (not (.exists (io/file *root* "ws" "d"))))))

(deftest delete-refuses-an-allowlist-root
  (let [c (ctx)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"allowlist root"
                          (fs/delete {:path (at "ws") :recursive true} c)))
    (is (.isDirectory (io/file *root* "ws")) "the sandbox root survives")))

(deftest delete-denied-outside-allowlist
  (is (thrown? clojure.lang.ExceptionInfo (fs/delete {:path (at "ro" "x")} (ctx)))))

;; ---------------------------------------------------------------------------
;; move
;; ---------------------------------------------------------------------------

(deftest move-renames-and-creates-dest-parents
  (let [c (ctx)]
    (spit (io/file *root* "ws" "a.txt") "data")
    (fs/move {:from (at "ws" "a.txt") :to (at "ws" "sub" "b.txt")} c)
    (is (not (.exists (io/file *root* "ws" "a.txt"))))
    (is (= "data" (slurp (io/file *root* "ws" "sub" "b.txt"))))))

(deftest move-refuses-existing-destination
  (let [c (ctx)]
    (spit (io/file *root* "ws" "a.txt") "1")
    (spit (io/file *root* "ws" "b.txt") "2")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists"
                          (fs/move {:from (at "ws" "a.txt") :to (at "ws" "b.txt")} c)))))

(deftest move-needs-write-on-both-ends
  (let [c (ctx)]
    (spit (io/file *root* "ws" "a.txt") "1")
    (testing "destination outside the allowlist is refused, source preserved"
      (is (thrown? clojure.lang.ExceptionInfo
                   (fs/move {:from (at "ws" "a.txt") :to (at "elsewhere" "b.txt")} c)))
      (is (.exists (io/file *root* "ws" "a.txt"))))
    (testing "read-only source is refused"
      (spit (io/file *root* "ro" "r.txt") "1")
      (is (thrown? clojure.lang.ExceptionInfo
                   (fs/move {:from (at "ro" "r.txt") :to (at "ws" "r.txt")} c))))))

;; ---------------------------------------------------------------------------
;; file-info
;; ---------------------------------------------------------------------------

(deftest file-info-reports-type-and-size
  (let [c (ctx)]
    (spit (io/file *root* "ws" "f.txt") "hello")
    (let [i (fs/file-info {:path (at "ws" "f.txt")} c)]
      (is (true? (:exists i)))
      (is (= :file (:type i)))
      (is (= 5 (:size i))))
    (let [d (fs/file-info {:path (at "ws")} c)]
      (is (= :dir (:type d)))
      (is (nil? (:size d))))
    (let [m (fs/file-info {:path (at "ws" "nope")} c)]
      (is (false? (:exists m)))
      (is (nil? (:type m))))))

(deftest file-info-denied-outside-allowlist
  (is (thrown? clojure.lang.ExceptionInfo (fs/file-info {:path (at "secret" "x")} (ctx)))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(deftest tools-are-registered
  (doseq [name [:fs/read-file :fs/list-dir :fs/write-file
                :fs/make-dir :fs/delete :fs/move :fs/file-info]]
    (let [t (registry/get-tool name)]
      (is (ifn? (:fn t)) (str name " has a fn"))
      (is (string? (:description t)) (str name " has a description"))
      (is (= "object" (get-in t [:schema :type])) (str name " has an object schema")))))

;; ---------------------------------------------------------------------------
;; End-to-end through :tool/invoke (ties Groups 1+2+3)
;; ---------------------------------------------------------------------------

(deftest invoke-denied-write-yields-typed-error-result
  (testing "a denied write flows through :tool/invoke as a typed :tool/result error, no file written"
    (let [dispatched (atom [])
          target     (at "elsewhere" "x.txt")
          c          (assoc (ctx) :dispatch! #(swap! dispatched conj %))]
      (executor/execute-effect :tool/invoke
                               {:tool/name :fs/write-file
                                :tool/args {:path target :content "z"}}
                               c)
      (let [r (first @dispatched)]
        (is (= :tool/result (:event/type r)))
        (is (= :error (:tool/status r)))
        (is (= :tool/access-denied (get-in r [:tool/error :type]))
            "the policy's ex-data type is preserved, not flattened to :exception"))
      (is (not (.exists (io/file target))) "denied write performed no side effect"))))

(deftest invoke-ok-write-dispatches-ok-result
  (testing "an allowed write through :tool/invoke succeeds and writes the file"
    (let [dispatched (atom [])
          target     (at "ws" "out.txt")
          c          (assoc (ctx) :dispatch! #(swap! dispatched conj %))]
      (executor/execute-effect :tool/invoke
                               {:tool/name :fs/write-file
                                :tool/args {:path target :content "data"}}
                               c)
      (is (= :ok (:tool/status (first @dispatched))))
      (is (= "data" (slurp (io/file target)))))))
