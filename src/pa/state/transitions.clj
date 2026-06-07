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
