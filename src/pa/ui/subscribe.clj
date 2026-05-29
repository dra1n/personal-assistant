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
