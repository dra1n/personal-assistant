(ns pa.commands.registry
  "Slash-command registry — a global table mapping a command name to its spec.

  Mirrors pa.tools.registry: commands self-register at namespace load time via
  reg-command, and the parser/selector read from this atom at use time (not at
  start time), so a command registered late at the REPL is available
  immediately.

  A command spec is {:command :description :arg-spec :->event} plus an optional
  :hint override:
    :command     — the command name, a string without the leading slash
                   (e.g. \"memory\"). This is the registry key.
    :description — a one-line summary; the selector's help line and /help text.
    :arg-spec    — the polymorphic argument descriptor. Its :kind selects the
                   argument shape and drives both resolution and the usage hint:
                     :none      — no argument.
                     :free-text — the rest of the line, verbatim, as one string.
                     :enum      — a fixed, validated set of tokens (:values).
                     :select    — a dynamic, interactive picker (documented,
                                  deferred — see requirements.md).
    :->event     — (fn [args] -> event-map). Builds the runtime event dispatched
                   when the command is submitted. args is the resolved argument
                   map (Group 2); the event flows through the normal
                   dispatch -> coeffect -> handler -> effect pipeline.
    :hint        — optional curated usage-hint string overriding the value
                   derived from :arg-spec (see usage-hint).

  Permitted mutation sites: reg-command, and test fixtures that save/restore the
  registry between tests."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Command spec (validated at registration)
;;
;; The map keys are unqualified (:command, :description, …) to keep the shape
;; documented in requirements.md and used by the example commands verbatim, so
;; the s/keys specs use :req-un / :opt-un. :arg-spec is polymorphic on :kind, so
;; it is an s/multi-spec: the :kind selects which keys the arg-spec must carry,
;; letting reg-command reject e.g. an :enum command with no :values up front
;; rather than at first use.

(s/def ::command     (s/and string? seq))          ; non-empty name, no slash
(s/def ::description  string?)
(s/def ::->event      ifn?)                         ; (fn [args] -> event-map)
(s/def ::hint         string?)

(s/def ::kind         #{:none :free-text :enum :select})
(s/def ::required     boolean?)
(s/def ::placeholder  string?)
(s/def ::values       (s/coll-of string? :kind vector? :min-count 1))
(s/def ::current-fn   ifn?)                         ; (fn [db] -> current token)
(s/def ::options-fn   ifn?)                         ; (fn [db] -> [{:label :value}])

(defmulti ^:private arg-spec-kind :kind)
(defmethod arg-spec-kind :none      [_] (s/keys :req-un [::kind]))
(defmethod arg-spec-kind :free-text [_] (s/keys :req-un [::kind]
                                                :opt-un [::required ::placeholder]))
(defmethod arg-spec-kind :enum      [_] (s/keys :req-un [::kind ::values]
                                                :opt-un [::current-fn ::placeholder]))
(defmethod arg-spec-kind :select    [_] (s/keys :req-un [::kind ::options-fn]
                                                :opt-un [::placeholder]))
;; Unknown/missing :kind: fall back to a shape that fails the ::kind enum, so
;; s/valid? returns false with a clear message instead of the multimethod
;; throwing on an unregistered dispatch value.
(defmethod arg-spec-kind :default   [_] (s/keys :req-un [::kind]))

(s/def ::arg-spec (s/multi-spec arg-spec-kind :kind))

(s/def ::spec
  (s/keys :req-un [::command ::description ::arg-spec ::->event]
          :opt-un [::hint]))

(def ^:private registry (atom {}))

(defn reg-command
  "Register command-spec, keyed by its :command name. Overwrites any existing
  registration for that name. Returns the command name. Throws ex-info with a
  spec explanation if the spec is malformed (see ::spec)."
  [{:keys [command] :as spec}]
  (when-not (s/valid? ::spec spec)
    (throw (ex-info (str "Invalid command spec:\n" (s/explain-str ::spec spec))
                    {:spec spec})))
  (swap! registry assoc command spec)
  command)

(defn get-command
  "Return the command spec for command (a name string), or nil."
  [command]
  (get @registry command))

(defn ^:export registered-commands
  "Return the set of all registered command names."
  []
  (set (keys @registry)))

(defn all-commands
  "Return the seq of all registered command specs (values). Used by the selector
  and /help to enumerate commands."
  []
  (vals @registry))

(defn usage-hint
  "The usage hint shown next to a command in the selector. Uses an explicit
  :hint override when present, else derives from the :arg-spec:
    :enum      -> :values joined with \" | \"  (e.g. \"on | off\")
    :free-text -> :placeholder                 (e.g. \"<text>\")
    :select    -> :placeholder
    :none      -> \"\"  (blank)"
  [{:keys [hint arg-spec]}]
  (or hint
      (let [{:keys [kind values placeholder]} arg-spec]
        (case kind
          :enum      (str/join " | " values)
          :free-text (or placeholder "")
          :select    (or placeholder "")
          ""))))

(defn snapshot
  "Return the current registry map. Used by test fixtures to save state."
  []
  @registry)

(defn restore!
  "Replace the registry with a previously snapshotted map. Used by test fixtures."
  [m]
  (reset! registry m))
