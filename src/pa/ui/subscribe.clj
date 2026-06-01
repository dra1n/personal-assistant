(ns pa.ui.subscribe
  (:require [charm.program :as charm]
            [clojure.core.async :as async]))

;; ---------------------------------------------------------------------------
;; Runtime state subscription bridge
;;
;; Bridges the tap> stream emitted by db-tap-interceptor into the charm
;; event loop via a core.async channel and a recurring command.
;;
;; Usage:
;;   (let [{:keys [db-ch tap-sink watch-cmd]} (make-subscription)]
;;     (add-tap tap-sink)           ; register at init
;;     ;; pass watch-cmd as the init command to charm/run-async
;;     ;; handle :runtime/db-updated messages in update-model
;;     ;; call (remove-tap tap-sink) and (async/close! db-ch) on halt)
;;
;; Message emitted into the charm loop:
;;   {:type :runtime/db-updated :db <snapshot>}
;; ---------------------------------------------------------------------------

(defn watch-db-cmd
  "Return a charm command that parks on db-ch and emits a
  :runtime/db-updated message when a snapshot arrives."
  [db-ch]
  (charm/cmd
    (fn []
      (when-let [snapshot (async/<!! db-ch)]
        {:type :runtime/db-updated :db snapshot}))))

(defn make-tap-sink
  "Return a tap fn that forwards db snapshots from db-tap-interceptor
  onto db-ch. Uses offer! (non-blocking) — the sliding buffer ensures
  only the latest snapshot is kept."
  [db-ch]
  (fn [{:db/keys [transition]}]
    (when transition
      (async/offer! db-ch transition))))

(defn make-subscription
  "Create the subscribe components. Returns:
    :db-ch    — core.async channel (sliding-buffer 1)
    :tap-sink — fn to pass to add-tap / remove-tap
    :watch-cmd — initial charm command; reschedule on every :runtime/db-updated"
  []
  (let [db-ch (async/chan (async/sliding-buffer 1))]
    {:db-ch     db-ch
     :tap-sink  (make-tap-sink db-ch)
     :watch-cmd (watch-db-cmd db-ch)}))

;; ---------------------------------------------------------------------------
;; Log subscription
;;
;; A Timbre appender forwards formatted log entries onto log-ch, which the
;; charm loop drains one entry at a time via watch-log-cmd. Unlike db-ch this
;; uses a dropping buffer (not sliding-1) so individual entries are delivered
;; rather than coalesced; offer! keeps the logging thread non-blocking.
;;
;; Message emitted into the charm loop:
;;   {:type :log/appended :entry {:level kw :msg str :instant inst}}
;; ---------------------------------------------------------------------------

(defn watch-log-cmd
  "Return a charm command that parks on log-ch and emits a :log/appended
  message when an entry arrives."
  [log-ch]
  (charm/cmd
    (fn []
      (when-let [entry (async/<!! log-ch)]
        {:type :log/appended :entry entry}))))

(defn make-log-appender
  "A Timbre appender map that offers compact entries onto log-ch. Non-blocking
  (dropping buffer). Captures :debug and above so the panel is a live tail of
  runtime activity (the durable file appender records everything regardless)."
  [log-ch]
  {:enabled?  true
   :async?    false
   :min-level :debug
   :fn (fn [{:keys [level msg_ instant]}]
         (async/offer! log-ch {:level   level
                               :msg     (force msg_)
                               :instant instant}))})

(defn make-log-subscription
  "Create the log-panel subscription. Returns:
    :log-ch        — core.async channel (dropping-buffer)
    :log-appender  — Timbre appender map to register via merge-config!
    :watch-log-cmd — initial charm command; reschedule on every :log/appended"
  []
  (let [log-ch (async/chan (async/dropping-buffer 1024))]
    {:log-ch        log-ch
     :log-appender  (make-log-appender log-ch)
     :watch-log-cmd (watch-log-cmd log-ch)}))
