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

(defn unreg-tool
  "Remove tool-name from the registry. Returns tool-name.

  Registrations are normally permanent — native tools self-register at load
  time and stay. Dynamic families (an MCP server's tools) need the inverse, so
  a tool name cannot outlive the connection standing behind it."
  [tool-name]
  (swap! registry dissoc tool-name)
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

(defn- arg-key
  "Normalize a schema's argument name to the keyword an args map is keyed by.

  Schemas arrive from two places and disagree on this. A native tool's schema is
  hand-written EDN, so it names arguments with keywords (:required [:path]).
  A schema from an MCP server is JSON Schema: keywordizing its keys on the way
  in leaves the strings inside :required untouched, so it names them as JSON
  wrote them. Both forms name the same argument."
  [k]
  (if (keyword? k) k (keyword (str k))))

(defn- prop-type
  "The declared type of a property, whichever key shape the schema uses."
  [prop]
  (or (:type prop) (get prop "type")))

(defn validate-args
  "Returns nil if args satisfy schema, or a descriptive error string on failure.
  Checks: all :required keys are present; each :properties entry whose key is
  present in args has the declared type. An empty schema {} always passes.
  Argument names may be keywords or strings — see arg-key."
  [schema args]
  (let [required   (map arg-key (or (:required schema) []))
        properties (:properties schema)]
    (or
     (when-let [missing (seq (remove #(contains? args %) required))]
       (str "Missing required argument(s): " (str/join ", " (map name missing))))
     (when properties
       (some (fn [[prop spec]]
               (let [k (arg-key prop)
                     t (prop-type spec)]
                 (when (and t (contains? args k))
                   (when-not (type-matches? t (get args k))
                     (str "Argument " (name k) " must be " t
                          " but got " (pr-str (get args k)))))))
             properties)))))

(defn snapshot
  "Return the current registry map. Used by test fixtures to save state."
  []
  @registry)

(defn restore!
  "Replace the registry with a previously snapshotted map. Used by test fixtures."
  [m]
  (reset! registry m))
