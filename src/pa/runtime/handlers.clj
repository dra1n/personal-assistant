(ns pa.runtime.handlers
  (:require [pa.llm.prompt :as prompt]
            [pa.runtime.coeffects :as coeffects]
            [pa.runtime.events :as events]
            [pa.runtime.registry :as registry]
            [pa.state.transitions :as tr]
            [pa.storage.history :as history]
            [pa.tools.registry :as tools]))

(defn- assemble-for [db memories]
  (prompt/assemble {:identity        (:identity db)
                    :conversation    (:conversation db)
                    :memory-snippets (or memories [])}))

;; ---------------------------------------------------------------------------
;; Scheduler handlers
;; ---------------------------------------------------------------------------

(registry/reg-handler :reminder/due
                      (fn [{:keys [db event]}]
                        (let [notification {:id      (:task/id event)
                                            :type    :reminder
                                            :payload (:task/payload event)}]
                          {:db    (tr/add-notification db notification)
                           :tap   {:reminder/due notification}
                           :trace {:event/type :reminder/due :task/id (:task/id event)}})))

(registry/reg-handler :notifications/clear
                      (fn [{:keys [db]}]
                        {:db (tr/clear-notifications db)}))

(registry/reg-handler :notification/dismiss
                      (fn [{:keys [db event]}]
                        {:db (tr/dismiss-notification db (:notification/id event))}))

;; ---------------------------------------------------------------------------
;; System lifecycle handlers
;; ---------------------------------------------------------------------------

(registry/reg-handler :system/identity-loaded
                      (fn [{:keys [db event]}]
                        {:db (tr/set-identity db (:identity event))}))

(registry/reg-handler :history/loaded
                      (fn [{:keys [db event]}]
                        {:db (tr/set-history db (:entries event))}))

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
;; When the model calls a tool instead of answering, the turn grows: each
;; :assistant/tool-call → :tool/result pair adds two persisted events, and a
;; final :assistant/response closes the turn. The follow-up :llm/invoke
;; re-advertises tools so the model can chain calls (search → fetch → answer);
;; the model is expected to stop calling tools once it has enough information.
;; ---------------------------------------------------------------------------

(registry/reg-handler :user/message
                      [coeffects/memories-interceptor]
                      (fn [{:keys [db event memories]}]
                        (let [content (:content event)
                              db'     (tr/add-conversation-entry db {:role :user :content content})
                              entry   (history/make-entry content)
                              duplicate?  (= content (:history/text (last (:ui/history db))))]
                          (cond-> {:db          (if duplicate? db' (tr/append-history db' entry))
                                   :event/store event
                                   :llm/invoke  {:messages (assemble-for db' memories) :opts {:tools (tools/advertise)}}
                                   :trace       {:event/type :user/message}}
                            (not duplicate?) (assoc :history/append entry)))))

(registry/reg-handler :assistant/tool-call
                      (fn [{:keys [db event]}]
                        (let [{:keys [content tool-calls]} event
                              tc  (first tool-calls)                ; fire the first; the rest run sequentially via :tool/result
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
;; outcome is also appended to the conversation as a :role :tool turn. The model
;; may have requested several tools in one turn; they run sequentially —
;; whenever a result lands, the next unresolved call in the batch is fired, and
;; only once every call has a result does the LLM get re-invoked (no tools) to
;; finish. This keeps the assistant message's N tool_calls matched by N tool
;; results, as the API requires. A result with no :tool/call-id (a
;; directly-invoked tool) only records.
;; ---------------------------------------------------------------------------

(defn- tool-result->content
  "A string rendering of the tool outcome for the :role :tool conversation turn."
  [event]
  (if (= :error (:tool/status event))
    (str "ERROR: " (get-in event [:tool/error :message] (:tool/error event)))
    (pr-str (:tool/output event))))

(defn- unresolved-tool-calls
  "The tool-calls of the most recent assistant tool-call turn that don't yet
  have a matching :role :tool result after it, in order. Empty once the whole
  batch is resolved. Drives sequential execution of a multi-call turn."
  [conversation]
  (let [conv (vec conversation)
        idx  (->> conv
                  (keep-indexed (fn [i e] (when (seq (:tool-calls e)) i)))
                  last)]
    (when idx
      (let [satisfied (set (keep :tool-call-id (subvec conv (inc idx))))]
        (remove #(satisfied (:id %)) (:tool-calls (nth conv idx)))))))

(registry/reg-handler :tool/result
                      (fn [{:keys [db event]}]
                        (let [base {:db          (tr/add-tool-result db (events/payload event))
                                    :event/store event
                                    :trace       {:event/type  :tool/result
                                                  :tool/name   (:tool/name event)
                                                  :tool/status (:tool/status event)}}]
                          (if-let [call-id (:tool/call-id event)]
                            (let [db'     (tr/add-conversation-entry (:db base)
                                                                     {:role         :tool
                                                                      :tool-call-id call-id
                                                                      :content      (tool-result->content event)})
                                  pending (unresolved-tool-calls (:conversation db'))]
                              (cond-> (assoc base :db db')
                                ;; more calls in this batch — fire the next one
                                (seq pending)
                                (assoc :tool/invoke {:tool/name      (:name (first pending))
                                                     :tool/args      (:arguments (first pending))
                                                     :tool/call-id   (:id (first pending))
                                                     :llm/follow-up? (:llm/follow-up? event)})
                                ;; batch complete — re-invoke the LLM with tools for multi-hop
                                (and (empty? pending) (:llm/follow-up? event))
                                (assoc :llm/invoke {:messages (assemble-for db' [])
                                                    :opts     {:tools (tools/advertise)}})))
                            base))))
