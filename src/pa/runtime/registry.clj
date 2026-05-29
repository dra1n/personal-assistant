(ns pa.runtime.registry)

;; ---------------------------------------------------------------------------
;; Handler registry
;;
;; A global atom mapping :event/type keyword → handler-fn.
;; Handlers self-register at namespace load time via reg-handler.
;; The dispatcher reads from this atom at dispatch time (not at start time)
;; so handlers registered late at the REPL are available immediately.
;;
;; Permitted mutation sites: reg-handler, and test fixtures that save/restore
;; the registry between tests.
;; ---------------------------------------------------------------------------

(def ^:private registry (atom {}))

(defn reg-handler
  "Register a handler for event-type.

  2-arity: (reg-handler event-type handler-fn)
    Registers handler-fn with no extra interceptors.

  3-arity: (reg-handler event-type interceptors handler-fn)
    Registers handler-fn with a vector of per-handler interceptors that run
    after base coeffect injection and before the handler fn itself. Use this
    to inject event-type-specific coeffects from any module without touching
    the base coeffect injector.

  Overwrites any existing registration for event-type."
  ([event-type handler-fn]
   (reg-handler event-type [] handler-fn))
  ([event-type interceptors handler-fn]
   {:pre [(qualified-keyword? event-type)
          (vector? interceptors)
          (fn? handler-fn)]}
   (swap! registry assoc event-type {:interceptors interceptors :fn handler-fn})))

(defn get-handler
  "Return the registry entry for event-type as {:interceptors [...] :fn fn}, or nil."
  [event-type]
  (get @registry event-type))

(defn ^:export registered-types
  "Return the set of all registered event types."
  []
  (set (keys @registry)))

(defn snapshot
  "Return the current registry map. Used by test fixtures to save state."
  []
  @registry)

(defn restore!
  "Replace the registry with a previously snapshotted map. Used by test fixtures."
  [m]
  (reset! registry m))
