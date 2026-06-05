(ns pa.llm.prompt
  "Prompt assembly — a pure function that turns runtime context into the
  messages vector an LLM provider expects.

  assemble takes:
    {:identity        <identity context map from pa.storage.identity/load-all>
     :conversation    <vector of {:role :content ...} entries>
     :memory-snippets <seq of memory records to surface as context>}

  and returns an ordered vector of {:role :content} maps: a single :system
  message (identity + memory context) followed by the conversation turns.

  Memory retrieval is injected, never called here — callers pass whatever
  records they want surfaced, so Phase 5 retrieval drops into the same seam."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Value / front-matter rendering
;; ---------------------------------------------------------------------------

(defn- non-empty? [v]
  (cond
    (string? v) (not (str/blank? v))
    (coll? v)   (boolean (seq v))
    (nil? v)    false
    :else       true))

(defn- fmt-value [v]
  (cond
    (string? v)     v
    (sequential? v) (str/join ", " (map fmt-value v))
    (map? v)        (str/join "; " (map (fn [[k val]] (str (name k) ": " (fmt-value val))) v))
    :else           (str v)))

(defn- render-front-matter [fm]
  (->> fm
       (filter (fn [[_ v]] (non-empty? v)))
       (map (fn [[k v]] (str (name k) ": " (fmt-value v))))
       (str/join "\n")))

(defn- clean-prose
  "Strip HTML comments (the identity templates ship prose as <!-- ... -->
  placeholders) and trim, so unfilled templates contribute nothing."
  [prose]
  (when prose
    (-> prose
        (str/replace #"(?s)<!--.*?-->" "")
        str/trim)))

(defn- render-section [title {:keys [front-matter prose]}]
  (let [parts (remove str/blank? [(render-front-matter front-matter)
                                  (clean-prose prose)])]
    (when (seq parts)
      (str "# " title "\n" (str/join "\n\n" parts)))))

;; ---------------------------------------------------------------------------
;; System message
;; ---------------------------------------------------------------------------

(def ^:private identity-sections
  [[:identity      "Assistant identity"]
   [:user          "About the user"]
   [:agents        "Operating guidelines"]
   [:memory-wisdom "Permanent memory"]])

(defn- render-memories [snippets]
  (when (seq snippets)
    (str "# Relevant context from memory\n"
         (str/join "\n"
                   (map (fn [{:memory/keys [title summary]}]
                          (if (str/blank? summary)
                            (str "- " title)
                            (str "- " title ": " summary)))
                        snippets)))))

(defn- system-content [identity memory-snippets]
  (let [memories (render-memories memory-snippets)
        sections (cond-> (keep (fn [[k title]] (render-section title (get identity k)))
                               identity-sections)
                   memories (concat [memories]))]
    (when (seq sections)
      (str/join "\n\n" sections))))

;; ---------------------------------------------------------------------------
;; Conversation
;; ---------------------------------------------------------------------------

(defn- conversation->message
  "Project a stored conversation entry down to a prompt message, dropping any
  metadata (timestamps, ids) the entry may carry. Tool turns keep the extra
  keys a provider needs to serialize them:
    - an assistant turn that requested tools keeps :tool-calls;
    - a tool-result turn keeps :tool-call-id."
  [entry]
  (cond
    (:tool-calls entry)   (select-keys entry [:role :content :tool-calls])
    (:tool-call-id entry) (select-keys entry [:role :content :tool-call-id])
    :else                 (select-keys entry [:role :content])))

;; ---------------------------------------------------------------------------
;; Assemble
;; ---------------------------------------------------------------------------

(defn assemble
  "Build the messages vector from runtime context. Omits the system message
  entirely when there is no identity or memory content to include."
  [{:keys [identity conversation memory-snippets]}]
  (let [sys (system-content identity memory-snippets)]
    (into (if sys [{:role :system :content sys}] [])
          (map conversation->message conversation))))
