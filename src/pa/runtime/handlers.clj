(ns pa.runtime.handlers
  (:require [pa.llm.prompt :as prompt]
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
