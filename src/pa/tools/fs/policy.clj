(ns pa.tools.fs.policy
  "Filesystem access policy — the capability-flagged path allowlist that gates
  every filesystem tool.

  This is the fs tool family's own policy. Other tool families (web, youtube)
  carry their own sibling policies — there is no single 'tool policy'. See the
  spec design-notes for the convention.

  The allowlist is authored in `<data-root>/system/tools.md` inside a fenced
  ```allowlist block, one `<path> <capabilities...>` entry per line. It is
  parsed once at startup into a policy value:

    {:data-root <canonical abs path>
     :roots [{:root <canonical abs path> :caps #{:read :write}} ...]}

  Resolution (see resolve-path): the requested path is canonicalized — `~` is
  expanded, relative paths anchor to the data root, and `..`/symlinks resolve to
  a real absolute path BEFORE any check — then matched against the roots:
    - if the resolved path is under any `deny` root, it is denied (deny wins);
    - otherwise the longest-prefix non-deny root supplies the capability set;
    - a path under no root is denied (default-deny);
    - `write` does not imply `read`.

  Tools call `check` to obtain the safe canonical path or be refused."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Parsing
;; ---------------------------------------------------------------------------

(def ^:private cap->kw {"read" :read "write" :write "deny" :deny})

(defn- allowlist-block
  "Return the raw lines inside the first fenced ```allowlist block, or []."
  [text]
  (let [lines (str/split-lines text)
        after (->> lines
                   (drop-while #(not (re-matches #"\s*```allowlist\s*" %)))
                   rest)]
    (take-while #(not (re-matches #"\s*```\s*" %)) after)))

(defn parse-allowlist
  "Parse the allowlist block of tools.md text into a vector of
  {:path <string> :caps #{:read|:write|:deny}}. Blank lines, comment lines
  (`#`), and lines with no recognized capability are skipped."
  [text]
  (->> (allowlist-block text)
       (map str/trim)
       (remove str/blank?)
       (remove #(str/starts-with? % "#"))
       (keep (fn [line]
               (let [[path & caps] (str/split line #"\s+")
                     caps          (into #{} (keep cap->kw) caps)]
                 (when (and path (seq caps))
                   {:path path :caps caps}))))
       vec))

;; ---------------------------------------------------------------------------
;; Canonicalization
;; ---------------------------------------------------------------------------

(defn- expand-home [path]
  (let [home (System/getProperty "user.home")]
    (cond
      (= path "~")               home
      (str/starts-with? path "~/") (str home (subs path 1))
      :else                      path)))

(defn- canonical
  "Canonical absolute path string for `path`: expand `~`, anchor relatives to
  `base`, then resolve `.`/`..` and existing symlinks via getCanonicalPath."
  [base path]
  (let [expanded (expand-home path)
        f        (let [f (io/file expanded)]
                   (if (.isAbsolute f) f (io/file base expanded)))]
    (.getCanonicalPath f)))

(defn- under?
  "True if canonical path `p` is `root` itself or nested beneath it. Compares
  whole path segments, so /foo is not a prefix of /foobar."
  [root p]
  (or (= p root)
      (str/starts-with? p (str root File/separator))))

;; ---------------------------------------------------------------------------
;; Policy construction
;; ---------------------------------------------------------------------------

(defn build-policy
  "Build a policy value from the data root and tools.md text. Each allowlist
  root is canonicalized (relative roots anchored to the data root)."
  [data-root text]
  (let [base (.getCanonicalPath (io/file data-root))]
    {:data-root base
     :roots     (mapv (fn [{:keys [path caps]}]
                        {:root (canonical base path) :caps caps})
                      (parse-allowlist text))}))

(defn load-policy
  "Load and build the policy from `<root>/system/tools.md`. A missing file
  yields an empty allowlist (default-deny everything)."
  [root]
  (let [f (io/file root "system" "tools.md")]
    (build-policy root (if (.exists f) (slurp f) ""))))

;; ---------------------------------------------------------------------------
;; Resolution & checks
;; ---------------------------------------------------------------------------

(defn resolve-path
  "Canonicalize `path` and return {:path <canonical> :caps <granted-set>}.
  caps is a subset of #{:read :write}, or #{} when denied (deny root,
  unmatched path, or a deny-only match)."
  [{:keys [data-root roots]} path]
  (let [p     (canonical data-root path)
        deny? (some (fn [{:keys [root caps]}]
                      (and (contains? caps :deny) (under? root p)))
                    roots)
        match (when-not deny?
                (->> roots
                     (remove #(contains? (:caps %) :deny))
                     (filter #(under? (:root %) p))
                     (sort-by #(count (:root %)) >)
                     first))]
    {:path p
     :caps (if (or deny? (nil? match)) #{} (:caps match))}))

(defn capable?
  "True if `capability` (:read or :write) is granted for `path`."
  [policy path capability]
  (contains? (:caps (resolve-path policy path)) capability))

(defn check
  "Return the safe canonical path if `capability` is granted for `path`;
  otherwise throw an ex-info {:type :tool/access-denied}. Tools call this and
  then operate on the returned canonical path, never the raw argument."
  [policy path capability]
  (let [{p :path caps :caps} (resolve-path policy path)]
    (if (contains? caps capability)
      p
      (throw (ex-info "filesystem access denied"
                      {:type :tool/access-denied :path p :capability capability})))))

(defn root-path?
  "True if `canonical-path` is exactly one of the allowlist roots. Destructive
  tools use this to refuse operating on a root itself (e.g. deleting the
  workspace sandbox)."
  [policy canonical-path]
  (boolean (some #(= canonical-path (:root %)) (:roots policy))))

;; ---------------------------------------------------------------------------
;; Integrant component
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :tool.fs/policy [_ {:keys [fs]}]
  (load-policy (:root fs)))

(defmethod ig/halt-key! :tool.fs/policy [_ _])
