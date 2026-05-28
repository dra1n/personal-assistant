(ns pa.runtime.coeffects
  (:require [pa.runtime.state :as state])
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

(defn inject-coeffects
  "Build and return the coeffect map for event, enriched with system-context.
  system-context must contain :config and :runtime keys."
  [event system-context]
  {:db      (state/current-db)
   :now     (Instant/now)
   :config  (:config system-context)
   :runtime (:runtime system-context)
   :event   event})
