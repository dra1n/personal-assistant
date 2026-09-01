(ns pa.ui.selector.sources
  "The two things the input overlay can offer: slash commands and @-mentioned
  MCP resources. Each is built as a `pa.ui.selector.state` source, so the same
  state machine drives both and they cannot drift in behaviour.

  Building a source is cheap and done per keystroke, matching what the selector
  did before (it read the command registry on every call) — which is also what
  keeps a command registered at the REPL visible immediately."
  (:require [pa.commands.registry :as commands]
            [pa.tools.mcp :as mcp]
            [pa.ui.selector.state :as selector]))

(defn command-source
  "Slash commands, filtered by name prefix and anchored to the start of the
  buffer — a command is the whole line, not a word inside one."
  []
  {:trigger   "/"
   :anchored? true
   :match     :prefix
   :rows      (mapv (fn [spec]
                      {:label (str "/" (:command spec))
                       :hint  (commands/usage-hint spec)
                       :help  (:description spec)
                       :value spec
                       :kind  :command})
                    (sort-by :command (commands/all-commands)))})

(defn mention-source
  "Connected servers' resources, keyed `@server:uri`. Unanchored, because a
  mention belongs inside a sentence, and substring-matched, because the label
  begins with a server name nobody types first — `@arch` should find
  `@everything:demo://…/architecture.md`."
  [resources]
  {:trigger   "@"
   :anchored? false
   :match     :substring
   :rows      (mapv (fn [row]
                      {:label (str "@" (mcp/resource-label row))
                       :hint  (:mime-type row)
                       :help  (:description row)
                       :value row
                       :kind  :resource})
                    resources)})

(defn active
  "The source the buffer is currently invoking, or nil. At most one can be in
  phase: a command owns the whole line, a mention is a word inside it."
  [{:keys [input resources]}]
  (let [candidates (cond-> [(command-source)]
                     (seq resources) (conj (mention-source resources)))]
    (first (filter #(selector/token % input) candidates))))
