(ns pa.state.queries
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Runtime state query layer
;;
;; Pure selector functions (fn [db] ...) over the runtime state map.
;; Consumers — UI, REPL tooling, replay, tests — call these instead of
;; reaching into db structure directly. State shape changes are contained here.
;; ---------------------------------------------------------------------------

(defn conversation
  "Return the conversation history vector."
  [db]
  (:conversation db))

(defn tasks
  "Return the tasks map."
  [db]
  (:tasks db))

(defn recent-events
  "Return the recent-events vector accumulated by the dispatcher."
  [db]
  (:events/recent db))

(defn ui-prefs
  "Return the UI preferences map."
  [db]
  (:ui db))

(defn identity-context
  "Return the identity context map loaded at startup."
  [db]
  (:identity db))

(defn- non-blank [s]
  (when-not (str/blank? (str s)) s))

(defn assistant-name
  "The assistant's display name from identity.md front-matter, or nil if unset."
  [db]
  (non-blank (get-in db [:identity :identity :front-matter :name])))

(defn user-name
  "The user's display name from user.md front-matter, or nil if unset."
  [db]
  (non-blank (get-in db [:identity :user :front-matter :name])))

(defn memories
  "Return the in-session memory records vector."
  [db]
  (:memories db))
