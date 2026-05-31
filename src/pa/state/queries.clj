(ns pa.state.queries)

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

(defn memories
  "Return the in-session memory records vector."
  [db]
  (:memories db))
