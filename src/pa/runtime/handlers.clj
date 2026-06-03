(ns pa.runtime.handlers
  (:require [pa.llm.prompt :as prompt]
            [pa.runtime.events :as events]
            [pa.runtime.registry :as registry]
            [pa.state.transitions :as tr]))

;; ---------------------------------------------------------------------------
;; System lifecycle handlers
;; ---------------------------------------------------------------------------

(registry/reg-handler :system/identity-loaded
  (fn [{:keys [db event]}]
    {:db (tr/set-identity db (:identity event))}))

;; ---------------------------------------------------------------------------
;; Memory handlers
;; ---------------------------------------------------------------------------

(registry/reg-handler :memory/stored
  (fn [{:keys [db event]}]
    {:db           (tr/add-memory db (:record event))
     :memory/index (:record event)
     :trace        {:event/type :memory/stored :id (get-in event [:record :memory/id])}}))

;; ---------------------------------------------------------------------------
;; Conversation handlers
;;
;; A turn is exactly two persisted events: :user/message (in) and
;; :assistant/response (out). Both append to :conversation and are stored, so
;; replay reconstructs the conversation from them alone. :user/message also
;; assembles the prompt and emits the :llm/invoke effect; replay applies only
;; :db, so the LLM is never called on replay.
;; ---------------------------------------------------------------------------

(registry/reg-handler :user/message
  (fn [{:keys [db event]}]
    (let [db'      (tr/add-conversation-entry db {:role :user :content (:content event)})
          messages (prompt/assemble {:identity        (:identity db')
                                     :conversation    (:conversation db')
                                     :memory-snippets []})]
      {:db          db'
       :event/store event
       :llm/invoke  {:messages messages}
       :trace       {:event/type :user/message}})))

(registry/reg-handler :assistant/response
  (fn [{:keys [db event]}]
    {:db          (tr/add-conversation-entry db {:role :assistant :content (:content event)})
     :event/store event
     :trace       {:event/type :assistant/response}}))

;; ---------------------------------------------------------------------------
;; Tool handlers
;;
;; :tool/result is the persisted outcome of a :tool/invoke effect. The effect
;; performs the side effect (and is never replayed); this handler records the
;; result into runtime state and stores it, so replay reconstructs tool
;; outcomes as data without re-running the tool — the same pattern as
;; :llm/invoke -> :assistant/response and :memory/write -> :memory/stored.
;; ---------------------------------------------------------------------------

(registry/reg-handler :tool/result
  (fn [{:keys [db event]}]
    {:db          (tr/add-tool-result db (events/payload event))
     :event/store event
     :trace       {:event/type  :tool/result
                   :tool/name   (:tool/name event)
                   :tool/status (:tool/status event)}}))
