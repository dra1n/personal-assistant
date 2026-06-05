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
;; Delta channel — the LLM streaming side-channel, shared by the dispatcher
;; (producer, via emit-delta!) and the terminal UI (consumer). Owned by its
;; own component so both can depend on it without a dependency cycle.
;; Dropping buffer: live display is best-effort; the full response text is
;; accumulated by the :llm/invoke effect, not reconstructed from this channel.
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :ui/deltas [_ _]
  (async/chan (async/dropping-buffer 4096)))

(defmethod ig/halt-key! :ui/deltas [_ ch]
  (some-> ch async/close!))

;; ---------------------------------------------------------------------------
;; Integrant component — assembles subscribe + app, manages lifecycle
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :pa.ui/terminal [_ {:keys [dispatcher deltas]}]
  (let [{:keys [db-ch tap-sink watch-cmd]}          (subscribe/make-subscription)
        {:keys [log-ch log-appender watch-log-cmd]} (subscribe/make-log-subscription)
        _                        (add-tap tap-sink)
        ;; Route logs into the in-app panel; silence the stdout appender,
        ;; which would otherwise scribble over the charm-rendered frame.
        ;; set-console! also flips the flag so a later pa.logging init can't
        ;; re-enable :println. (The file appender stays on.)
        _                        (logging/set-console! false)
        _                        (log/merge-config! {:appenders {:panel log-appender}})
        {:keys [quit! result]}   (charm/run-async
                                  {:init   (app/init {:db-ch           db-ch
                                                      :watch-cmd       watch-cmd
                                                      :dispatch!       (:dispatch! dispatcher)
                                                      :log-ch          log-ch
                                                      :watch-log-cmd   watch-log-cmd
                                                      :delta-ch        deltas
                                                      :watch-delta-cmd (subscribe/watch-delta-cmd deltas)})
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
  ;; Disable bracketed paste mode so the terminal is clean after exit.
  (doto System/out (.print "[?2004l") (.flush))
  (log/info "terminal UI stopped"))
