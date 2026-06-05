(ns pa.runtime.coeffects
  (:require [pa.state.db :as db])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Coeffect injection
;;
;; inject-coeffects builds the context map that is handed to every handler.
;; Handlers must not fetch runtime dependencies themselves — everything they
;; need arrives here.
;;
;; Coeffect map keys:
;;   :db       — current runtime state snapshot
;;   :now      — wall clock at dispatch time
;;   :config   — system config map
;;   :runtime  — curated runtime capabilities: {:dispatch! fn}
;;   :event    — the triggering event
;;
;; The system-context map is built once at dispatcher start time and contains
;; the curated :config and :runtime values. This avoids reconstructing them
;; on every dispatch.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; memories-interceptor
;;
;; Per-handler interceptor for :user/message events. Calls retrieve-memories!
;; from system-context.runtime using the event :content as query text, then
;; injects the results as :memories into the coeffect map. Returns [] when
;; retrieve-memories! is absent (e.g. tests that don't wire up a datasource).
;; ---------------------------------------------------------------------------

(def memories-interceptor
  {:before (fn [ctx]
             (let [retrieve! (get-in ctx [:system-context :runtime :retrieve-memories!])
                   text      (get-in ctx [:event :content])
                   memories  (if (and retrieve! (seq text))
                               (retrieve! {:query/text text :query/limit 5})
                               [])]
               (tap> {:memories/retrieved memories})
             (update ctx :coeffects assoc :memories memories)))
   :after nil})

(defn inject-coeffects
  "Build and return the coeffect map for event, enriched with system-context.
  system-context must contain :config and :runtime keys."
  [event system-context]
  {:db      (db/current-db)
   :now     (Instant/now)
   :config  (:config system-context)
   :runtime (:runtime system-context)
   :event   event})
