(ns pa.ui.core
  (:require [charm.program :as charm]
            [clojure.core.async :as async]
            [integrant.core :as ig]
            [pa.logging :as logging]
            [pa.ui.app :as app]
            [pa.ui.subscribe :as subscribe]
            [pa.ui.view :as view]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Integrant component — assembles subscribe + app, manages lifecycle
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :pa.ui/terminal [_ {:keys [dispatcher]}]
  (let [{:keys [db-ch tap-sink watch-cmd] :as sub}     (subscribe/make-subscription)
        {:keys [log-ch log-appender watch-log-cmd]}    (subscribe/make-log-subscription)
        _                        (add-tap tap-sink)
        ;; Route logs into the in-app panel; silence the stdout appender,
        ;; which would otherwise scribble over the charm-rendered frame.
        ;; set-console! also flips the flag so a later pa.logging init can't
        ;; re-enable :println. (The file appender stays on.)
        _                        (logging/set-console! false)
        _                        (log/merge-config! {:appenders {:panel log-appender}})
        {:keys [quit! result]}   (charm/run-async
                                  {:init   (app/init (assoc sub
                                                            :dispatch!     (:dispatch! dispatcher)
                                                            :log-ch        log-ch
                                                            :watch-log-cmd watch-log-cmd))
                                   :update app/update-model
                                   :view   view/view
                                   :alt-screen  false
                                   :hide-cursor false})]
    (log/info "terminal UI initialized")
    {:db-ch    db-ch
     :tap-sink tap-sink
     :watch-cmd watch-cmd
     :log-ch   log-ch
     :quit!    quit!
     :result   result}))

(defmethod ig/halt-key! :pa.ui/terminal [_ {:keys [quit! db-ch tap-sink log-ch]}]
  (remove-tap tap-sink)
  ;; Drop the panel appender and restore stdout logging (for REPL use).
  (log/merge-config! {:appenders {:panel {:enabled? false}}})
  (logging/set-console! true)
  (async/close! db-ch)
  (when log-ch (async/close! log-ch))
  (quit!)
  (log/info "terminal UI stopped"))
