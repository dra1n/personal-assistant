(ns pa.commands.handlers
  "Handlers for the built-in commands' target events. Command events flow through
  the normal dispatch -> coeffect -> handler -> effect pipeline like any other
  event; these handlers return declarative effects only (no side effects here).
  Loaded for its registration side effects — required by pa.system at startup.

  :command/help      -> append a system turn listing the registered commands.
  :memory/note       -> append the note to permanent memory via :wisdom/merge.
  :conversation/clear-> reset the active conversation context via :db.
  :command/rejected  -> surface a usage error as a UI notification via :db.

  (/markdown's :settings/set handler is generic and lives in pa.runtime.handlers.)"
  (:require [clojure.string :as str]
            [pa.commands.registry :as commands]
            [pa.runtime.registry :as registry]
            [pa.state.transitions :as tr]))

(defn help-text
  "A newline-separated listing of every registered command as '/name — desc',
  sorted by name. Read straight from the registry so it always matches what is
  registered."
  []
  (->> (commands/all-commands)
       (sort-by :command)
       (map (fn [{:keys [command description]}]
              (str "/" command " — " description)))
       (str/join "\n")
       (str "Available commands:\n")))

(registry/reg-handler :command/help
                      (fn [{:keys [db]}]
                        {:db (tr/add-conversation-entry
                              db {:role :system :content (help-text)})}))

(registry/reg-handler :memory/note
                      (fn [{:keys [event]}]
                        {:wisdom/merge [(:text event)]}))

(registry/reg-handler :conversation/clear
                      (fn [{:keys [db]}]
                        {:db (tr/clear-conversation db)}))

(registry/reg-handler :command/rejected
                      (fn [{:keys [db event]}]
                        {:db (tr/add-notification
                              db {:id      (:event/id event)
                                  :type    :command/error
                                  :payload {:text (:message event)}})}))
