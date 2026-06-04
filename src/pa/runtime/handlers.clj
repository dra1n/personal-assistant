(ns pa.runtime.handlers
  (:require [pa.llm.prompt :as prompt]
            [pa.runtime.events :as events]
            [pa.runtime.registry :as registry]
            [pa.state.transitions :as tr]
            [pa.tools.registry :as tools]))

(defn- assemble-for
  "Assemble the prompt messages from the current runtime state."
  [db]
  (prompt/assemble {:identity        (:identity db)
                    :conversation    (:conversation db)
                    :memory-snippets []}))

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
;; A plain turn is two persisted events: :user/message (in) and
;; :assistant/response (out). Both append to :conversation and are stored, so
;; replay reconstructs the conversation from them alone. :user/message also
;; assembles the prompt and emits the :llm/invoke effect; replay applies only
;; :db, so the LLM is never called on replay.
;;
;; When the model calls a tool instead of answering, the turn grows to four
;; persisted events: :user/message, :assistant/tool-call (the request),
;; :tool/result (the outcome), :assistant/response (the final text). The single
;; hop is enforced structurally — the follow-up :llm/invoke advertises no tools,
;; so the model must answer in text.
;; ---------------------------------------------------------------------------

(registry/reg-handler :user/message
                      (fn [{:keys [db event]}]
                        (let [db' (tr/add-conversation-entry db {:role :user :content (:content event)})]
                          {:db          db'
                           :event/store event
                           :llm/invoke  {:messages (assemble-for db') :opts {:tools (tools/advertise)}}
                           :trace       {:event/type :user/message}})))

(registry/reg-handler :assistant/tool-call
                      (fn [{:keys [db event]}]
                        (let [{:keys [content tool-calls]} event
                              tc  (first tool-calls)                ; minimal: one tool per hop
                              db' (tr/add-conversation-entry db {:role       :assistant
                                                                 :content    content
                                                                 :tool-calls tool-calls})]
                          {:db          db'
                           :event/store event
                           :tool/invoke {:tool/name      (:name tc)
                                         :tool/args      (:arguments tc)
                                         :tool/call-id   (:id tc)
                                         :llm/follow-up? true}
                           :trace       {:event/type :assistant/tool-call :tool/name (:name tc)}})))

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
;;
;; When the result carries a :tool/call-id it came from an LLM tool call, so the
;; outcome is also appended to the conversation as a :role :tool turn; if it
;; also carries :llm/follow-up? the LLM is re-invoked (no tools) to finish.
;; A result with no :tool/call-id (a directly-invoked tool) only records.
;; ---------------------------------------------------------------------------

(defn- tool-result->content
  "A string rendering of the tool outcome for the :role :tool conversation turn."
  [event]
  (if (= :error (:tool/status event))
    (str "ERROR: " (get-in event [:tool/error :message] (:tool/error event)))
    (pr-str (:tool/output event))))

(registry/reg-handler :tool/result
                      (fn [{:keys [db event]}]
                        (let [base {:db          (tr/add-tool-result db (events/payload event))
                                    :event/store event
                                    :trace       {:event/type  :tool/result
                                                  :tool/name   (:tool/name event)
                                                  :tool/status (:tool/status event)}}]
                          (if-let [call-id (:tool/call-id event)]
                            (let [db' (tr/add-conversation-entry (:db base)
                                                                 {:role         :tool
                                                                  :tool-call-id call-id
                                                                  :content      (tool-result->content event)})]
                              (cond-> (assoc base :db db')
                                (:llm/follow-up? event)
                                (assoc :llm/invoke {:messages (assemble-for db')})))
                            base))))
