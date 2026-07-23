(ns pa.state.transitions)

;; ---------------------------------------------------------------------------
;; Runtime state transitions
;;
;; Pure transition functions (fn [db ...] -> db) over the runtime state map.
;; Handlers must use these instead of manipulating db keys directly.
;; State shape changes are contained here, symmetric to pa.state.queries.
;; ---------------------------------------------------------------------------

(defn set-identity [db identity]
  (assoc db :identity identity))

(defn set-history [db entries]
  (assoc db :ui/history (vec entries)))

(defn add-conversation-entry [db entry]
  (update db :conversation conj entry))

(defn clear-conversation
  "Reset the active conversation context to empty so the next LLM turn carries
  no prior turns. A context reset only — persisted events on disk are untouched.
  Drives the /clear command's :conversation/clear event."
  [db]
  (assoc db :conversation []))

(defn set-ui [db ui-map]
  (assoc db :ui ui-map))

(defn update-ui [db f & args]
  (apply update db :ui f args))

(defn set-task [db id value]
  (assoc-in db [:tasks id] value))

(defn remove-task [db id]
  (update db :tasks dissoc id))

(defn add-memory [db record]
  (update db :memories (fnil conj []) record))

(defn add-tool-result [db result]
  (update db :tool/results (fnil conj []) result))

(defn append-history [db entry]
  (update db :ui/history (fnil conj []) entry))

(defn add-notification [db notification]
  (update db :ui/notifications (fnil conj []) notification))

(defn clear-notifications [db]
  (assoc db :ui/notifications []))

(defn dismiss-notification [db id]
  (update db :ui/notifications (fnil #(filterv (fn [n] (not= (:id n) id)) %) [])))

(defn set-setting
  "Set runtime setting k to v in the :settings map. The sole transition for
  settings; commands reach it through the :settings/set handler + :db effect."
  [db k v]
  (assoc-in db [:settings k] v))

(defn load-scheduled-tasks [db tasks]
  (assoc db :tasks/scheduled (vec tasks)))

(defn register-scheduled-task [db task]
  (update db :tasks/scheduled (fnil conj []) task))

(defn remove-scheduled-task [db id]
  (update db :tasks/scheduled (fnil #(filterv (fn [t] (not= (:task/id t) id)) %) [])))

(defn replace-scheduled-task [db task]
  (update db :tasks/scheduled
          (fnil #(mapv (fn [t] (if (= (:task/id t) (:task/id task)) task t)) %) [])))
