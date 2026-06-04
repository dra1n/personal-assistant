(ns pa.tools.registry
  "Tool registry — a global table mapping a tool name to its implementation,
  argument schema, and human/LLM-facing description.

  Mirrors pa.runtime.registry: tools self-register at namespace load time via
  reg-tool, and the :tool/invoke effect reads from this atom at invocation time
  (not at start time), so a tool registered late at the REPL is available
  immediately.

  A tool spec is {:fn fn, :schema <schema>, :description string}:
    :fn          — (fn [args ctx] -> result). args is the caller-supplied
                   argument map; ctx is the runtime capability map handed to
                   effects, so a tool reaches the access policy, dispatch!, etc.
                   through it. Returns the tool's result value (any data).
    :schema      — the argument schema used to validate args before invocation;
                   its concrete shape is defined alongside the filesystem tools.
    :description — a one-line summary, surfaced to the LLM when advertising tools.

  Permitted mutation sites: reg-tool, and test fixtures that save/restore the
  registry between tests."
  (:require [clojure.string :as str]))

(def ^:private registry (atom {}))

(defn reg-tool
  "Register tool-spec under tool-name (a qualified keyword, e.g. :fs/read-file).
  Overwrites any existing registration for tool-name. Returns tool-name."
  [tool-name {:keys [schema description] :as spec}]
  {:pre [(qualified-keyword? tool-name)
         (ifn? (:fn spec))
         (some? schema)
         (string? description)]}
  (swap! registry assoc tool-name spec)
  tool-name)

(defn get-tool
  "Return the tool spec for tool-name as {:fn :schema :description}, or nil."
  [tool-name]
  (get @registry tool-name))

(defn ^:export registered-tools
  "Return the set of all registered tool names."
  []
  (set (keys @registry)))

(defn advertise
  "Provider-neutral specs for advertising the registered tools to an LLM:
  one {:name <kw> :description <string> :parameters <schema>} per tool, with
  the :fn dropped. A provider translates these into its own tool format."
  []
  (mapv (fn [[name {:keys [schema description]}]]
          {:name name :description description :parameters schema})
        @registry))

;; ---------------------------------------------------------------------------
;; Argument validation

(defn- type-matches? [type-str v]
  (case type-str
    "string"  (string? v)
    "boolean" (instance? Boolean v)
    "integer" (integer? v)
    "number"  (number? v)
    "array"   (sequential? v)
    "object"  (map? v)
    true))

(defn validate-args
  "Returns nil if args satisfy schema, or a descriptive error string on failure.
  Checks: all :required keys are present; each :properties entry whose key is
  present in args has the declared type. An empty schema {} always passes."
  [schema args]
  (let [required   (seq (:required schema))
        properties (:properties schema)]
    (or
     (when-let [missing (seq (remove #(contains? args %) (or required [])))]
       (str "Missing required argument(s): " (str/join ", " (map name missing))))
     (when properties
       (some (fn [[prop-kw {:keys [type]}]]
               (when (and type (contains? args prop-kw))
                 (when-not (type-matches? type (get args prop-kw))
                   (str "Argument " (name prop-kw) " must be " type
                        " but got " (pr-str (get args prop-kw))))))
             properties)))))

(defn snapshot
  "Return the current registry map. Used by test fixtures to save state."
  []
  @registry)

(defn restore!
  "Replace the registry with a previously snapshotted map. Used by test fixtures."
  [m]
  (reset! registry m))
