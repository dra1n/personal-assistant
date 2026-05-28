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
  "Register handler-fn to handle events of event-type.
  Overwrites any existing registration for that type."
  [event-type handler-fn]
  {:pre [(qualified-keyword? event-type) (fn? handler-fn)]}
  (swap! registry assoc event-type handler-fn))

(defn get-handler
  "Return the handler-fn registered for event-type, or nil."
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
