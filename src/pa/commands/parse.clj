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

(defn parse
  "Parse input into {:command <name> :raw-args <string>}, or nil.

  Returns nil when input is not a string, does not start with a leading slash
  (leading whitespace counts as no slash), is a bare slash, or names a command
  that is not registered — in every such case the line is an ordinary message."
  [input]
  (when (string? input)
    (when-let [[_ command raw] (re-matches command-re input)]
      (when (registry/get-command command)
        {:command command :raw-args (or raw "")}))))
