(ns pa.memory.consolidation
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Consolidation prompt
;;
;; Sends the current wisdom bullet list to the LLM and asks it to deduplicate,
;; merge overlapping entries, and resolve contradictions. Conservative by
;; design: when in doubt, keep the fact.
;; ---------------------------------------------------------------------------

(def ^:private system-prompt
  "You are cleaning up a list of permanent memory facts about a user.

Return a JSON array of cleaned facts:
- Merge overlapping or near-duplicate entries into a single, more complete entry
- Remove exact duplicates
- When two facts contradict, keep the more specific or more recently stated one
- Preserve all distinct information — when in doubt, keep the fact
- Return each fact as a plain string without bullet markers

Respond only with a JSON array of strings — no markdown fences, no explanation.")

(defn consolidation-messages
  "Build the messages vector for the consolidation LLM call."
  [bullets]
  [{:role :system :content system-prompt}
   {:role :user   :content (str/join "\n" bullets)}])

(defn- strip-code-fence [s]
  (-> s
      str/trim
      (str/replace #"(?s)^```[a-z]*\n?" "")
      (str/replace #"\n?```$" "")
      str/trim))

(defn parse-response
  "Parse the LLM's JSON array response into a vector of fact strings."
  [content]
  (try
    (let [parsed (json/read-str (strip-code-fence content))]
      (vec (filter (every-pred string? seq) parsed)))
    (catch Exception _ [])))
