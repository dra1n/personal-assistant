(ns pa.state.db)

;; ---------------------------------------------------------------------------
;; Runtime state atom
;;
;; This is the single source of truth for operational runtime state.
;; It is intentionally small — this is not long-term memory.
;;
;; MUTATION RULE: two permitted mutation sites only —
;;   1. pa.runtime.executor/execute-effect :db  — applies handler state transitions
;;   2. pa.runtime.dispatcher/process-event!    — appends to :events/recent before handler runs
;; All other code reads via current-db. No swap!/reset! elsewhere.
;; ---------------------------------------------------------------------------

(def initial-db
  {:conversation  []
   :tasks         {}
   :memories      []
   :events/recent []
   :ui            {}
   :identity      {}})

(def db (atom initial-db))

(defn current-db
  "Return a snapshot of the current runtime state. Used by the coeffect injector."
  []
  @db)

;; ---------------------------------------------------------------------------
;; Append-only trace log. Each entry is a map written by the :trace effect.
;; ---------------------------------------------------------------------------

(def trace-log (atom []))
