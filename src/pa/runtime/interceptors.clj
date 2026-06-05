(ns pa.runtime.interceptors
  (:require [clojure.set :as set]
            [pa.runtime.coeffects :as coeffects]
            [pa.runtime.executor :as executor]
            [pa.runtime.registry :as registry]
            [pa.state.db :as db]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Interceptor chain runner
;;
;; An interceptor is a map of {:before fn :after fn}.
;;   :before — called left-to-right before the handler runs; receives context, returns context
;;   :after  — called right-to-left after the handler runs; receives context, returns context
;;
;; Context map shape flowing through the chain:
;;   :event          — the triggering event (immutable; set before chain starts)
;;   :system-context — runtime capabilities {:config :runtime}
;;   :coeffects      — injected by the coeffect interceptor's :before
;;   :effects        — written by the handler interceptor's :before; read by after fns
;;   :trace          — accumulated trace entries (vector)
;; ---------------------------------------------------------------------------

(defn run-chain
  "Run interceptors in order: apply all :before fns left-to-right, then all
  :after fns right-to-left. Returns the final context map."
  [chain ctx]
  (let [befores (keep :before chain)
        afters  (keep :after (reverse chain))
        ctx'    (reduce (fn [c f] (f c)) ctx befores)]
    (reduce (fn [c f] (f c)) ctx' afters)))

;; ---------------------------------------------------------------------------
;; Tracing interceptor
;;
;; Records event entry time (:before) and exit time + elapsed (:after).
;; ---------------------------------------------------------------------------

(def tracing-interceptor
  {:before (fn [ctx]
             (assoc ctx :trace/entered-at (Instant/now)))
   :after  (fn [ctx]
             (let [entered  (:trace/entered-at ctx)
                   exited   (Instant/now)
                   elapsed  (when entered
                              (- (.toEpochMilli exited)
                                 (.toEpochMilli entered)))
                   event-type (get-in ctx [:event :event/type])]
               (log/debug "event processed" {:event/type event-type :elapsed-ms elapsed})
               (swap! db/trace-log conj
                      {:trace/event-type event-type
                       :trace/entered-at entered
                       :trace/exited-at  exited
                       :trace/elapsed-ms elapsed})
               ctx))})

;; ---------------------------------------------------------------------------
;; Coeffect injection interceptor
;;
;; Calls inject-coeffects and assocs the result into :coeffects.
;; Replaces the manual wiring in dispatcher/process-event!.
;; ---------------------------------------------------------------------------

(def coeffect-interceptor
  {:before (fn [ctx]
             (assoc ctx :coeffects
                    (coeffects/inject-coeffects (:event ctx)
                                                (:system-context ctx))))
   :after  nil})

;; ---------------------------------------------------------------------------
;; Handler interceptor
;;
;; Looks up the registry entry {:interceptors [...] :fn handler-fn}.
;; Per-handler interceptors run as a sub-chain (before fns left-to-right,
;; after fns right-to-left) with the invoke-handler step appended at the end.
;; This lets any module inject extra coeffects for a specific event type by
;; passing interceptors at reg-handler time, without touching coeffects.clj.
;;
;; Sub-chain context is the same map that flows through the outer chain, so
;; per-handler :before fns can assoc keys into :coeffects directly.
;; ---------------------------------------------------------------------------

(defn- invoke-handler-interceptor [handler-fn]
  {:before (fn [ctx]
             (let [effects (handler-fn (:coeffects ctx))]
               (assoc ctx :effects (when (map? effects) effects))))
   :after nil})

(def handler-interceptor
  {:before (fn [ctx]
             (let [event-type (get-in ctx [:event :event/type])
                   entry      (registry/get-handler event-type)]
               (if entry
                 (run-chain (conj (:interceptors entry)
                                  (invoke-handler-interceptor (:fn entry)))
                            ctx)
                 (do
                   (log/debug "no handler registered for" event-type)
                   ctx))))
   :after  nil})

;; ---------------------------------------------------------------------------
;; Effect validation interceptor
;;
;; Logs a warning for any effect type not registered in execute-effect.
;; Does not block execution — the :default method is the safety net.
;; ---------------------------------------------------------------------------

(def effect-validation-interceptor
  {:before nil
   :after  (fn [ctx]
             (when-let [effects (:effects ctx)]
               (let [known   (set (keys (methods executor/execute-effect)))
                     unknown (set/difference (set (keys effects)) known)]
                 (when (seq unknown)
                   (log/warn "unknown effect types will be ignored" {:effect/types unknown}))))
             ctx)})

;; ---------------------------------------------------------------------------
;; Effect tracing interceptor
;;
;; Records which effects were executed and their params into the trace log.
;; ---------------------------------------------------------------------------

(def effect-tracing-interceptor
  {:before nil
   :after  (fn [ctx]
             (when-let [effects (:effects ctx)]
               (swap! db/trace-log conj
                      {:trace/effects        (keys effects)
                       :trace/event-type     (get-in ctx [:event :event/type])
                       :trace/timestamp      (Instant/now)}))
             ctx)})

;; ---------------------------------------------------------------------------
;; Effect execution interceptor
;;
;; Calls execute-effects! with the effects map from :effects.
;; ---------------------------------------------------------------------------

(def effect-execution-interceptor
  {:before nil
   :after  (fn [ctx]
             (when-let [effects (:effects ctx)]
               (executor/execute-effects! effects
                                          (:runtime (:system-context ctx))))
             ctx)})

;; ---------------------------------------------------------------------------
;; DB tap interceptor
;;
;; Emits the post-transition db snapshot via tap> after all effects have
;; executed. Sits at the front of the chain so its :after runs last.
;; Skipped during replay (replay builds its own partial chain).
;; ---------------------------------------------------------------------------

(def db-tap-interceptor
  {:before nil
   :after  (fn [ctx]
             (when (:effects ctx)
               (tap> {:db/transition (db/current-db)}))
             ctx)})

;; ---------------------------------------------------------------------------
;; Standard chain
;;
;; Order: db-tap → tracing → coeffect injection → handler → (after fns in reverse):
;;   effect execution ← effect tracing ← effect validation ← (coeffect) ← tracing ← db-tap
;;
;; db-tap is outermost so its :after fires last — after effects have executed
;; and state/db reflects the new value.
;; ---------------------------------------------------------------------------

(def standard-chain
  [db-tap-interceptor
   tracing-interceptor
   coeffect-interceptor
   handler-interceptor
   effect-validation-interceptor
   effect-tracing-interceptor
   effect-execution-interceptor])

(defn run-standard-chain
  "Process event through the standard interceptor chain.
  system-context must contain :config and :runtime keys."
  [event system-context]
  (run-chain standard-chain
             {:event          event
              :system-context system-context
              :coeffects      nil
              :effects        nil}))
