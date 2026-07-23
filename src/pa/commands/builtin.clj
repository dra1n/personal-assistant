(ns pa.commands.builtin
  "The built-in slash commands registered at load time, mirroring how tool
  namespaces self-register via reg-tool. Each command is a data spec whose
  :->event builds the runtime event the dispatch site pushes into the pipeline
  (pa.ui.app); the target-event handlers live in pa.commands.handlers (plus the
  generic :settings/set in pa.runtime.handlers).

  Loaded for its registration side effects — required by pa.system at startup
  and by tests that exercise command dispatch."
  (:require [pa.commands.registry :as registry]
            [pa.state.queries :as queries]))

(registry/reg-command
 {:command     "help"
  :description "List the available slash commands"
  :arg-spec    {:kind :none}
  :->event     (fn [_] {:event/type :command/help})})

(registry/reg-command
 {:command     "memory"
  :description "Append a note to the assistant's permanent memory"
  :arg-spec    {:kind :free-text :required true :placeholder "<text>"}
  :->event     (fn [args] {:event/type :memory/note :text (:text args)})})

(registry/reg-command
 {:command     "markdown"
  :description "Toggle terminal markdown rendering"
  :arg-spec    {:kind       :enum
                :values     ["on" "off"]
                ;; Feeds the Group 5 enum ghost placeholder; nil setting -> "off".
                :current-fn (fn [db] (if (queries/setting db :markdown) "on" "off"))}
  :->event     (fn [args] {:event/type :settings/set
                           :key        :markdown
                           :value      (= "on" (:token args))})})

(registry/reg-command
 {:command     "clear"
  :description "Start a fresh conversation context (persisted events are kept)"
  :arg-spec    {:kind :none}
  :->event     (fn [_] {:event/type :conversation/clear})})
