(ns pa.memory.extraction
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Classification prompt
;;
;; Takes a conversation (vector of {:role :content} maps) and produces the
;; messages vector to send to the LLM for memory classification.
;;
;; The LLM is asked to separate content into:
;;   ephemeral — session-specific notes to journal (title + summary)
;;   permanent — enduring facts to pin in memory/memory.md (plain strings)
;; ---------------------------------------------------------------------------

(def ^:private system-prompt
  "You extract memories from a conversation session.

Return a JSON object with exactly two fields:
  \"ephemeral\" — session-specific notes worth journaling (what was worked on,
                  decisions made, context). Each item is an object:
                  {\"title\": \"short label\", \"summary\": \"prose description\"}.
  \"permanent\" — enduring facts about the user that should always be available
                  (preferences, skills, relationships, personal details).
                  Each item is a plain fact string.

Return empty arrays when there is nothing worth extracting.
Respond only with the JSON object — no markdown fences, no explanation.")

(defn- format-turns [conversation]
  (->> conversation
       (keep (fn [{:keys [role content]}]
               (when (and (#{:user :assistant} role) (seq content))
                 (str (str/capitalize (name role)) ": " content))))
       (str/join "\n\n")))

(defn classify-messages
  "Build the messages vector for the classification LLM call."
  [conversation]
  [{:role :system :content system-prompt}
   {:role :user   :content (format-turns conversation)}])

;; ---------------------------------------------------------------------------
;; Response parsing
;;
;; The model may wrap JSON in a markdown code fence; strip-code-fence removes
;; it before parsing. Returns {:ephemeral [...] :permanent [...]} on success,
;; or both empty on any parse failure.
;; ---------------------------------------------------------------------------

(defn- strip-code-fence [s]
  (-> s
      str/trim
      (str/replace #"(?s)^```[a-z]*\n?" "")
      (str/replace #"\n?```$" "")
      str/trim))

(defn parse-response
  "Parse the LLM's JSON response string into {:ephemeral [...] :permanent [...]}."
  [content]
  (try
    (let [parsed (json/read-str (strip-code-fence content) :key-fn keyword)]
      {:ephemeral (vec (:ephemeral parsed))
       :permanent (vec (:permanent parsed))})
    (catch Exception _
      {:ephemeral [] :permanent []})))
