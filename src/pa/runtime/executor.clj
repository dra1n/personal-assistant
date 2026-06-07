(ns pa.runtime.executor
  (:require [clojure.core.async :as async]
            [pa.llm.provider :as provider]
            [pa.state.db :as db]
            [pa.tools.registry :as tools]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Effect executor
;;
;; execute-effect is a multimethod dispatched on effect-type keyword.
;; Handlers return an effects map; execute-effects! iterates it and calls
;; execute-effect for each entry.
;;
;; ctx is a map passed from the dispatcher containing runtime capabilities:
;;   {:dispatch! fn}
;;
;; Effect vocabulary (Phase 1):
;;
;;   Runtime effects (pure/internal — safe to replay):
;;     :db             — reset runtime state to the new value
;;     :dispatch       — enqueue a new event immediately
;;     :dispatch-later — schedule a delayed event dispatch
;;
;;   Observability effects:
;;     :log/info  — structured log entry via Timbre
;;     :trace     — append an entry to the runtime trace log
;;     :tap       — emit a value via tap> for Portal inspection
;;
;;   Persistence effects (stub — implemented in Phase 2):
;;     :event/store — no-op; defined so Phase 2 is a drop-in
;;
;; MUTATION RULE: :db is the only permitted site for swap!/reset! on db/db.
;; ---------------------------------------------------------------------------

(defmulti execute-effect
  (fn [effect-type _params _ctx] effect-type))

;; --- :db ---------------------------------------------------------------

(defmethod execute-effect :db [_ new-db _ctx]
  (reset! db/db new-db))

;; --- :dispatch ---------------------------------------------------------

(defmethod execute-effect :dispatch [_ event-map {:keys [dispatch!]}]
  (dispatch! event-map))

;; --- :dispatch-later ---------------------------------------------------
;;
;; params: {:event event-map :delay-ms ms}

(defmethod execute-effect :dispatch-later [_ {:keys [event delay-ms]} {:keys [dispatch!]}]
  (async/go
    (async/<! (async/timeout delay-ms))
    (dispatch! event)))

;; --- :log/info ---------------------------------------------------------
;;
;; params: {:message str, ...extra keys passed as context}

(defmethod execute-effect :log/info [_ {:keys [message] :as params} _ctx]
  (log/info message (dissoc params :message)))

;; --- :trace ------------------------------------------------------------
;;
;; params: any map; :timestamp is stamped automatically if absent

(defmethod execute-effect :trace [_ params _ctx]
  (let [entry (merge {:timestamp (java.time.Instant/now)} params)]
    (swap! db/trace-log conj entry)))

;; --- :tap --------------------------------------------------------------

(defmethod execute-effect :tap [_ value _ctx]
  (tap> value))

;; --- :event/store -------------------------------------------------------
;;
;; params: the event map to persist.
;; ctx must contain :store-event! fn supplied by :storage/events component.

(defmethod execute-effect :event/store [_ event {:keys [store-event!]}]
  (if store-event!
    (store-event! event)
    (log/warn ":event/store called but no :store-event! in ctx — is :storage/events wired?")))

;; --- :memory/write ------------------------------------------------------
;;
;; params: a memory record map (pa.memory.records/make output).
;; ctx must contain :write-memory! fn supplied by :memory/store component.
;; On success the handler receives a :memory/stored dispatch carrying the
;; persisted record (with :memory/path stamped by the writer).

(defmethod execute-effect :memory/write [_ record {:keys [write-memory! dispatch!]}]
  (if write-memory!
    (let [persisted (write-memory! record)]
      (dispatch! {:event/type :memory/stored :record persisted}))
    (log/warn ":memory/write called but no :write-memory! in ctx — is :memory/store wired?")))

;; --- :memory/index ------------------------------------------------------
;;
;; params: a persisted memory record map (with :memory/path stamped).
;; ctx must contain :index-memory! fn supplied by :memory/indexer component.

(defmethod execute-effect :memory/index [_ record {:keys [index-memory!]}]
  (if index-memory!
    (index-memory! record)
    (log/warn ":memory/index called but no :index-memory! in ctx — is :memory/indexer wired?")))

;; --- :llm/invoke --------------------------------------------------------
;;
;; params: {:messages [{:role :content} ...], :opts {...}?}
;; ctx must contain :llm-provider, :emit-delta!, and :dispatch!.
;;
;; Streams on a background thread so the dispatcher loop is never blocked.
;; Each delta is pushed to the UI via emit-delta! (best-effort live display);
;; the full accumulated text is dispatched as a single :assistant/response
;; event on completion (the authoritative, persisted result).

(defmethod execute-effect :llm/invoke [_ {:keys [messages opts]} {:keys [llm-provider emit-delta! dispatch!]}]
  (if llm-provider
    (future
      (try
        (let [{:keys [content tool-calls]}
              (provider/stream llm-provider messages (or opts {})
                               (fn [delta] (when emit-delta! (emit-delta! delta))))]
          (if (seq tool-calls)
            ;; The model requested tools — hand off to the tool-call path.
            (dispatch! {:event/type :assistant/tool-call :content content :tool-calls tool-calls})
            (dispatch! {:event/type :assistant/response :content content})))
        (catch Throwable e
          (log/error e "LLM stream failed")
          (dispatch! {:event/type :assistant/response
                      :content (str "⚠ LLM error: " (.getMessage e))}))))
    (log/warn ":llm/invoke called but no :llm-provider in ctx — is :llm/provider wired?")))

;; --- :tool/invoke -------------------------------------------------------
;;
;; params: {:tool/name <qualified-kw>, :tool/args <map>, :tool/dry-run? <bool>?}
;; ctx is the runtime capability map; it must contain :dispatch!. The tool fn is
;; called as (tool-fn args ctx), so a tool reaches its capabilities (the access
;; policy added in Group 2, dispatch!, etc.) through the same ctx.
;;
;; Resolves the tool from the global registry, times the call, emits a
;; structured log line, and dispatches exactly one :tool/result event carrying
;; the outcome. Like :llm/invoke, the side effect is the impure hop and is never
;; replayed; the dispatched :tool/result is the persisted, replayable record.
;;
;; Dry-run (params :tool/dry-run?) logs the descriptor and emits a :tool/result
;; with status :dry-run WITHOUT calling the tool fn, so no side effect occurs.

(defn- elapsed-ms [start-nanos]
  (quot (- (System/nanoTime) start-nanos) 1000000))

(defmethod execute-effect :tool/invoke
  [_ {tool-name :tool/name args :tool/args dry-run? :tool/dry-run?
      call-id :tool/call-id follow-up? :llm/follow-up?}
   {:keys [dispatch!] :as ctx}]
  (let [tool         (tools/get-tool tool-name)
        schema-error (when tool (tools/validate-args (:schema tool) args))
        ;; Echo correlation/routing keys back on the result so the
        ;; :tool/result handler can match an LLM tool call and continue the turn.
        result!      (fn [m] (dispatch! (merge {:event/type :tool/result
                                                :tool/name  tool-name
                                                :tool/args  args}
                                               (when call-id {:tool/call-id call-id})
                                               (when follow-up? {:llm/follow-up? true})
                                               m)))]
    (cond
      (nil? tool)
      (do (log/warn ":tool/invoke for unknown tool — ignoring" {:tool/name tool-name})
          (result! {:tool/status :error
                    :tool/error  {:type :unknown-tool :tool/name tool-name}}))

      (some? schema-error)
      (do (log/warn ":tool/invoke arg validation failed" {:tool/name tool-name :error schema-error})
          (result! {:tool/status :error
                    :tool/error  {:type :tool/invalid-args :message schema-error}}))

      dry-run?
      (do (log/info "tool dry-run — side effect skipped"
                    {:tool/name tool-name :tool/args args :tool/dry-run true})
          (result! {:tool/status :dry-run}))

      :else
      (let [start (System/nanoTime)]
        (try
          (let [output ((:fn tool) args ctx)
                ms     (elapsed-ms start)]
            (log/info "tool invoked"
                      {:tool/name tool-name :tool/args args :tool/duration-ms ms})
            (result! {:tool/status :ok :tool/output output :tool/duration-ms ms}))
          (catch Throwable e
            (let [ms       (elapsed-ms start)
                  ;; Preserve a thrown ex-info's data (e.g. :tool/access-denied
                  ;; from policy/check) so the result is distinguishable, not a
                  ;; generic :exception. Plain throwables fall back to :exception.
                  ex-data' (when (instance? clojure.lang.ExceptionInfo e) (ex-data e))]
              (log/error e "tool invocation failed" {:tool/name tool-name :tool/args args})
              (result! {:tool/status      :error
                        :tool/duration-ms ms
                        :tool/error       (merge {:type :exception :message (.getMessage e)}
                                                 ex-data')}))))))))

;; --- :history/append ----------------------------------------------------
;;
;; params: a history entry map (pa.storage.history/make-entry output).
;; ctx must contain :append-history! fn supplied by :storage/history component.
;; The in-memory :ui/history update is handled separately via the :db effect
;; in the handler; this effect is responsible for disk persistence only.

(defmethod execute-effect :history/append [_ entry {:keys [append-history!]}]
  (if append-history!
    (append-history! entry)
    (log/warn ":history/append called but no :append-history! in ctx — is :storage/history wired?")))

;; --- :task/schedule -----------------------------------------------------
;;
;; params: {:type kw, :payload map, :fire-at long, :interval-ms long|nil}
;; ctx must contain :schedule-task! fn from :scheduler component.

(defmethod execute-effect :task/schedule [_ spec {:keys [schedule-task!]}]
  (if schedule-task!
    (schedule-task! spec)
    (log/warn ":task/schedule called but no :schedule-task! in ctx — is :scheduler wired?")))

;; --- :task/cancel -------------------------------------------------------
;;
;; params: {:task/id string}
;; ctx must contain :cancel-task! fn from :scheduler component.

(defmethod execute-effect :task/cancel [_ {:keys [task/id]} {:keys [cancel-task!]}]
  (if cancel-task!
    (cancel-task! id)
    (log/warn ":task/cancel called but no :cancel-task! in ctx — is :scheduler wired?")))

;; --- default -----------------------------------------------------------

(defmethod execute-effect :default [effect-type _params _ctx]
  (log/warn "unknown effect type — ignoring" {:effect/type effect-type}))

;; ---------------------------------------------------------------------------
;; execute-effects!
;;
;; Iterates the effects map returned by a handler and executes each entry.
;; ctx is the runtime capabilities map {dispatch!}.
;; ---------------------------------------------------------------------------

(defn execute-effects! [effects ctx]
  (doseq [[effect-type params] effects]
    (execute-effect effect-type params ctx)))
