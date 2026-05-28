(ns pa.runtime.state)

;; ---------------------------------------------------------------------------
;; Runtime state atom
;;
;; This is the single source of truth for operational runtime state.
;; It is intentionally small — this is not long-term memory.
;;
;; MUTATION RULE: only pa.runtime.executor (Group 3) may call swap! or reset!
;; on db. All other code reads via current-db.
;; ---------------------------------------------------------------------------

(def initial-db
  {:conversation  []
   :tasks         {}
   :events/recent []
   :ui            {}})

(def db (atom initial-db))

(defn current-db
  "Return a snapshot of the current runtime state. Used by the coeffect injector."
  []
  @db)
