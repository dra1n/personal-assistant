(ns pa.ui.app
  (:require [charm.message :as msg]
            [charm.program :as charm]
            [pa.runtime.queries :as queries]
            [pa.runtime.state :as state]
            [pa.ui.subscribe :as subscribe]))

;; ---------------------------------------------------------------------------
;; Charm model
;;
;; {:db    <latest runtime state snapshot>
;;  :db-ch <core.async channel — owned by pa.ui.core, passed in at init>}
;;
;; UI-local state (input buffer, scroll, etc.) lives here alongside :db.
;; Runtime state is read only through pa.runtime.queries.
;; ---------------------------------------------------------------------------

(defn init
  "Return a charm init fn. Returns the initial model plus the first watch-db command."
  [{:keys [db-ch watch-cmd]}]
  (fn []
    [{:db    (state/current-db)
      :db-ch db-ch}
     watch-cmd]))

(defn update-model [model message]
  (cond
    (and (msg/key-press? message) (msg/key-match? message "ctrl+c"))
    [model charm/quit-cmd]

    (= :runtime/db-updated (:type message))
    [(assoc model :db (:db message))
     (subscribe/watch-db-cmd (:db-ch model))]

    :else
    [model nil]))

(defn view [model]
  (let [db          (:db model)
        event-count (count (queries/recent-events db))]
    (str "┌─────────────────────────────┐\n"
         "│  personal assistant  v0.0.0 │\n"
         "└─────────────────────────────┘\n"
         "\nevents processed: " event-count
         "\nPress Ctrl+C to quit")))
