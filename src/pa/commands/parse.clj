(ns pa.commands.parse
  "Pure slash-command parsing — the single input choke point that decides whether
  a submitted line is a command or an ordinary message.

  parse turns a raw input string into {:command <name> :raw-args <string>} when
  the line starts with a leading slash and names a registered command; otherwise
  it returns nil and the caller submits the line as today's :user/message.

  Splitting stops at the command token only — :raw-args is the rest of the line
  verbatim (internal spacing preserved), leaving argument resolution (Group 2) to
  interpret it per the command's :arg-spec."
  (:require [pa.commands.registry :as registry]))

;; ^/(token)(one whitespace)(rest)$ — (?s) so a multiline body is captured whole.
;; \S+ requires a non-blank command token, so a bare "/" (or "/ args") fails to
;; match and is treated as an ordinary message. A leading space fails the ^/
;; anchor for the same reason.
(def ^:private command-re #"(?s)^/(\S+)(?:\s(.*))?$")

(defn command-line
  "The attempt-level parse: if input is a command attempt — a leading slash
  followed by a non-blank command token — return {:command <token> :raw-args
  <string>} REGARDLESS of whether the token names a registered command; else
  nil (an ordinary message, including a bare slash and leading whitespace).

  This is what the dispatch site needs to tell an unknown /command (surface a
  usage error, never the LLM) apart from a plain message (route to the LLM);
  parse only recognises registered commands."
  [input]
  (when (string? input)
    (when-let [[_ command raw] (re-matches command-re input)]
      {:command command :raw-args (or raw "")})))

(defn parse
  "Parse input into {:command <name> :raw-args <string>} for a REGISTERED
  command, or nil. nil covers a non-command line, a bare slash, leading
  whitespace, and an unknown /command alike; use command-line when the unknown
  case must be told apart from an ordinary message."
  [input]
  (when-let [{:keys [command] :as parsed} (command-line input)]
    (when (registry/get-command command)
      parsed)))
