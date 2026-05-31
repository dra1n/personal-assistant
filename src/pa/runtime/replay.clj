(ns pa.runtime.replay
  (:require [pa.runtime.interceptors :as interceptors]
            [pa.state.db :as db]
            [pa.storage.events :as storage.events]))

;; ---------------------------------------------------------------------------
;; Replay
;;
;; Reconstructs runtime state from an initial state value plus an event
;; sequence by re-running each event through the interceptor chain.
;;
;; Only pure/internal effects are applied during replay:
;;   :db       — state transition (applied)
;;   everything else — skipped (external/non-deterministic: logs, dispatch, HTTP, etc.)
;;
;; replay does not touch the live db/db atom. It works on a local atom
;; so multiple calls with the same inputs always produce the same output.
;;
;; system-context is built with a no-op :dispatch! so any :dispatch effects
;; produced by handlers are silently dropped during replay.
;; ---------------------------------------------------------------------------

(defn- replay-system-context [config replay-db-atom]
  {:config  config
   :runtime {:dispatch! (fn [_])}
   :replay/db replay-db-atom})

(defn- apply-pure-effects
  "Apply only the :db effect from the effects map to replay-db-atom."
  [effects replay-db-atom]
  (when-let [new-db (:db effects)]
    (reset! replay-db-atom new-db)))

(defn- replay-event
  "Run a single event through the interceptor chain in replay mode.
  Returns the effects map produced by the handler (or nil)."
  [event system-context replay-db-atom]
  ;; Temporarily point db/db reads (inside inject-coeffects) at the replay atom.
  ;; We do this by binding a dynamic override in the coeffect injector contract:
  ;; inject-coeffects calls state/current-db which reads the live atom.
  ;; To keep the live atom untouched we intercept :db in the returned effects
  ;; rather than redirecting the atom read — handlers get a snapshot of replay
  ;; state via the :db coeffect by pre-assoc-ing it into the context.
  (let [base-ctx  {:event          event
                   :system-context system-context
                   :coeffects      nil
                   :effects        nil}
        ;; Override :db in coeffects to reflect the replay atom, not the live atom.
        override  {:before (fn [ctx]
                             (update ctx :coeffects assoc :db @replay-db-atom))
                   :after  nil}
        chain     (concat [interceptors/tracing-interceptor
                           interceptors/coeffect-interceptor
                           override]
                          [interceptors/handler-interceptor
                           interceptors/effect-validation-interceptor
                           interceptors/effect-tracing-interceptor])
        result    (interceptors/run-chain (vec chain) base-ctx)]
    (:effects result)))

(defn replay
  "Reconstruct runtime state from initial-db by replaying events in order.
  Returns the final state map. Does not mutate the live db/db atom.

  opts:
    :config — system config map passed to coeffects (defaults to {})

  Pure/internal effects applied: :db
  All other effects (dispatch, logs, tap, etc.) are skipped."
  ([events]
   (replay db/initial-db events {}))
  ([initial-db events]
   (replay initial-db events {}))
  ([initial-db events {:keys [config] :or {config {}}}]
   (let [replay-db (atom initial-db)
         sys-ctx   (replay-system-context config replay-db)]
     (doseq [event events]
       (let [effects (replay-event event sys-ctx replay-db)]
         (apply-pure-effects effects replay-db)))
     @replay-db)))

(defn replay-from-disk
  "Load events from the given events.edn path and replay them.
  Delegates to replay — same semantics, same opts."
  ([path]
   (replay-from-disk path db/initial-db {}))
  ([path initial-db]
   (replay-from-disk path initial-db {}))
  ([path initial-db opts]
   (replay initial-db (storage.events/load-events path) opts)))
