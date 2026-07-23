(ns pa.commands.args
  "Argument resolution — the pure step between parsing and dispatch.

  parse (pa.commands.parse) splits a line into {:command :raw-args}; resolve
  interprets :raw-args against the command's :arg-spec :kind and produces either
  the argument map to feed the command's :->event, or a structured usage error.
  It never builds or dispatches an event — a miss surfaces as data the UI renders
  inline, never as an LLM turn.

  raw-args is always a string, wherever it comes from: for the typed kinds it is
  the text after the command; for :select it is the value the Group 5 overlay
  picker completed into the buffer (the overlay only edits the buffer, so by
  submission time there is nothing special to extract). Resolution is therefore
  uniform over that string — the dispatch site does not special-case any kind.

  resolve is a multimethod keyed on :arg-spec :kind, so a new argument kind is
  added by defmethod alone, with no change to callers.

  Return shape (exactly one key):
    {:args  <map>}          — resolved; pass to (:->event spec)
    {:error <usage-error>}  — a structured usage error:
      {:command <name> :reason <kw> :message <string> :hint <usage-hint>}
      :reason is one of :missing-argument, :unknown-value, :surplus-argument.

  Per :arg-spec :kind:
    :none      — takes no argument; any non-blank :raw-args is :surplus-argument.
                 -> {:args {}}
    :free-text — the rest of the line verbatim (internal spacing preserved).
                 -> {:args {:text <raw-args>}}; :required + blank is
                 :missing-argument.
    :enum      — the trimmed token must be a member of :values.
                 -> {:args {:token <token>}}; blank is :missing-argument, a
                 non-member (including a multi-word arg) is :unknown-value.
    :select    — the picker already constrained the choice to a valid option, so
                 resolution passes the completed value straight through.
                 -> {:args {:value <raw-args>}}. Validating against :options-fn
                 needs runtime db and is the picker's job, not this layer's; the
                 only thing deferred for :select is a command to use it (/load,
                 /save need the sessions layer)."
  (:refer-clojure :exclude [resolve])
  (:require [clojure.string :as str]
            [pa.commands.registry :as registry]))

(defn- usage-error
  "Build a structured usage error for spec with the given reason and message,
  attaching the command's derived usage hint."
  [spec reason message]
  {:error {:command (:command spec)
           :reason  reason
           :message message
           :hint    (registry/usage-hint spec)}})

(defmulti resolve
  "Resolve raw-args against command-spec into {:args <map>} or
  {:error <usage-error>} (see ns docstring). Dispatches on :arg-spec :kind. Pure
  — does not touch the registry atom or dispatch anything."
  (fn [spec _raw-args] (get-in spec [:arg-spec :kind])))

(defmethod resolve :none [spec raw-args]
  (if (str/blank? raw-args)
    {:args {}}
    (usage-error spec :surplus-argument
                 (str "/" (:command spec) " takes no arguments"))))

(defmethod resolve :free-text [spec raw-args]
  (let [{:keys [required placeholder]} (:arg-spec spec)]
    (if (and required (str/blank? raw-args))
      (usage-error spec :missing-argument
                   (str "/" (:command spec) " requires " (or placeholder "an argument")))
      {:args {:text raw-args}})))

(defmethod resolve :enum [spec raw-args]
  (let [values (get-in spec [:arg-spec :values])
        token  (str/trim raw-args)
        hint   (registry/usage-hint spec)]
    (cond
      (str/blank? token)
      (usage-error spec :missing-argument
                   (str "/" (:command spec) " requires one of: " hint))

      (not (some #{token} values))
      (usage-error spec :unknown-value
                   (str "/" (:command spec) ": unknown value " (pr-str token)
                        " — expected one of: " hint))

      :else
      {:args {:token token}})))

(defmethod resolve :select [_spec raw-args]
  ;; The picker constrains the choice to a valid option before completing it into
  ;; the buffer, so this layer trusts the value and passes it through verbatim.
  {:args {:value raw-args}})

(defmethod resolve :default [spec _raw-args]
  ;; :arg-spec :kind is constrained to the known set at registration
  ;; (pa.commands.registry ::kind), so this is a programmer error, not user input.
  (throw (ex-info "Unknown arg-spec :kind"
                  {:command (:command spec)
                   :kind    (get-in spec [:arg-spec :kind])})))
