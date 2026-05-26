(ns pa.ui
  (:require [charm.program :as charm]
            [charm.message :as msg]
            [integrant.core :as ig]
            [taoensso.timbre :as log]))

(defn- view [_state]
  (str "┌─────────────────────────────┐\n"
       "│  personal assistant  v0.0.0 │\n"
       "└─────────────────────────────┘\n"
       "\nPress Ctrl+C to quit"))

(defmethod ig/init-key :pa.ui/terminal [_ _opts]
  (let [{:keys [quit! result]} (charm/run-async
                                 {:init   (fn [] nil)
                                  :update (fn [state message]
                                            (if (and (msg/key-press? message)
                                                     (msg/key-match? message "ctrl+c"))
                                              [state charm/quit-cmd]
                                              [state nil]))
                                  :view        view
                                  :alt-screen  false
                                  :hide-cursor false})]
    (log/info "terminal UI initialized")
    {:quit! quit! :result result}))

(defmethod ig/halt-key! :pa.ui/terminal [_ {:keys [quit!]}]
  (quit!)
  (log/info "terminal UI stopped"))
