(ns pa.storage.identity
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [integrant.core :as ig]))

;; ---------------------------------------------------------------------------
;; YAML front-matter parser
;;
;; Identity files use the format:
;;   ---
;;   key: value
;;   ---
;;   prose content ...
;;
;; load-identity-file returns a map with:
;;   :front-matter  — EDN map of parsed YAML fields (keys keywordized)
;;   :prose         — the Markdown prose below the front-matter block
;;   :source        — the filename (e.g. "identity.md")
;; ---------------------------------------------------------------------------

(defn- split-front-matter [text]
  (let [lines (str/split-lines text)]
    (if (= "---" (str/trim (first lines)))
      (let [close-idx (->> lines rest (keep-indexed #(when (= "---" (str/trim %2)) %1)) first)]
        (if close-idx
          (let [fm-lines (take close-idx (rest lines))
                prose    (str/join "\n" (drop (+ 2 close-idx) lines))]
            [(str/join "\n" fm-lines) (str/trim prose)])
          ["" (str/join "\n" lines)]))
      ["" (str/join "\n" lines)])))

(defn- keywordize [m]
  (walk/postwalk
   (fn [x] (if (string? x) x
               (if (map? x)
                 (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v]) x))
                 x)))
   m))

(defn load-identity-file
  "Parse a single identity Markdown file at path.
  Returns {:front-matter <EDN map> :prose <string> :source <filename>}."
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (let [text            (slurp f)
            [fm-text prose] (split-front-matter text)
            front-matter    (when (seq (str/trim fm-text))
                              (keywordize (yaml/parse-string fm-text)))]
        {:front-matter (or front-matter {})
         :prose        prose
         :source       (.getName f)})
      {:front-matter {}
       :prose        ""
       :source       (.getName f)})))

;; ---------------------------------------------------------------------------
;; load-all
;;
;; Loads the identity files and merges their front-matter under named
;; keys into a single :identity context map injected at startup.
;; ---------------------------------------------------------------------------

(def ^:private identity-files
  [["identity.md" :identity]
   ["user.md"     :user]
   ["agents.md"   :agents]])

(defn load-all
  "Load identity.md, user.md, agents.md from root/identity/.
  Returns a merged context map keyed by file, each value carrying both the
  parsed front-matter and the prose body:
    {:identity {:front-matter <map> :prose <string>}
     :user     {:front-matter <map> :prose <string>}
     :agents   {:front-matter <map> :prose <string>}}"
  [root]
  (into {}
        (map (fn [[filename key]]
               (let [{:keys [front-matter prose]}
                     (load-identity-file (str root "/identity/" filename))]
                 [key {:front-matter front-matter :prose prose}]))
             identity-files)))

;; ---------------------------------------------------------------------------
;; Integrant component
;;
;; Loads identity from disk and returns it. The dispatcher depends on this
;; component and dispatches :system/identity-loaded so the handler applies
;; it to state/db through the normal event pipeline.
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :storage/identity [_ {:keys [fs]}]
  {:identity (load-all (:root fs))})

(defmethod ig/halt-key! :storage/identity [_ _])
