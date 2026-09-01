(ns pa.tools.mcp.registry
  "The `:mcp/registry` component — connects to every enabled MCP server at
  startup and disconnects them at shutdown.

  Session-lifecycle, matching the Phase 6 model: connect on `ig/init-key`,
  disconnect on `ig/halt-key!`. There is no daemon and no reconnection loop —
  a server that dies stays down until the next restart.

  Servers connect concurrently, each bounded by its own
  `:connect-timeout-ms`, so a slow server costs startup its own budget rather
  than the sum of every server's. One that fails to connect contributes
  nothing and is logged; it never blocks startup or affects another server.

  The component value is:

    {:clients   {server -> connection}
     :resources {server -> [resource ...]}
     :prompts   {server -> [prompt ...]}}

  Resource and prompt listings are cached here at connect time, since the
  `@`-mention overlay and the slash-command registry need them synchronously
  while a user is typing. Tools are not cached: they are registered into
  `pa.tools.registry` instead, so the LLM advertisement and `:tool/invoke`
  find them exactly where native tools live. Their names are kept per server
  so disconnecting can withdraw them again."
  (:require [integrant.core :as ig]
            [pa.tools.mcp :as mcp]
            [pa.tools.mcp.client :as client]
            [pa.tools.mcp.policy :as policy]
            [taoensso.timbre :as log]))

(def ^:private connect-grace-ms
  "Slack on top of a server's own connect timeout before we stop waiting on its
  connect thread. `client/connect` already bounds the handshake; this only
  catches a thread wedged somewhere else entirely."
  5000)

;; ---------------------------------------------------------------------------
;; Connecting
;; ---------------------------------------------------------------------------

(defn- cached-listing
  "List `capability` if the server declared it, else nothing. A listing that
  fails is logged and treated as empty — a broken resources/list must not cost
  us the server's tools."
  [conn capability list-fn]
  (if (client/supports? conn capability)
    (try
      (vec (list-fn conn))
      (catch Throwable e
        (log/warn "mcp: could not list — continuing without it"
                  {:server (:name conn) :capability capability :error (ex-message e)})
        []))
    []))

(defn- connect-server
  "Connect one server and cache what it offers, as `[name entry]`, or nil if it
  could not be connected."
  [name server]
  (when-let [conn (client/connect name server)]
    [name {:client    conn
           :tools     (mcp/register-tools!
                       name conn (cached-listing conn :tools client/list-tools))
           :resources (cached-listing conn :resources client/list-resources)
           :prompts   (cached-listing conn :prompts client/list-prompts)}]))

(defn- await-connect
  "Collect one server's connect result within its budget. A connect that
  overruns is abandoned — and reaped in the background if it later succeeds, so
  an abandoned server never leaves an orphaned subprocess behind.

  The sentinel matters: connect-server returns nil for a server that failed
  outright, which a nil timeout default would misreport as a timeout (and has
  already been logged, with its actual reason, by the client)."
  [{:keys [name budget fut]}]
  (let [result (deref fut budget ::timeout)]
    (if (= ::timeout result)
      (do (log/warn "mcp: server did not finish connecting in time — skipping"
                    {:server name :budget-ms budget})
          (future (when-let [[_ {:keys [client]}] (deref fut)]
                    (log/warn "mcp: abandoned server connected late — disconnecting"
                              {:server name})
                    (client/close! client)))
          nil)
      result)))

(defn connect-all
  "Connect every enabled server concurrently. Returns `{name -> entry}` for
  those that answered; the rest are logged and skipped."
  [pol]
  (->> (policy/enabled-servers pol)
       (mapv (fn [[name server]]
               {:name   name
                :budget (+ (:connect-timeout-ms server) connect-grace-ms)
                :fut    (future (connect-server name server))}))
       (into {} (keep await-connect))))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defn client
  "The live connection for `name`, or nil if that server isn't connected."
  [registry name]
  (get (:clients registry) name))

(defn connected-servers [registry]
  (set (keys (:clients registry))))

(defn- tagged
  "Cached listings across all servers, each entry tagged with the server it came
  from — provenance the `@`-overlay and command names are built from."
  [registry k]
  (into [] (mapcat (fn [[server entries]] (map #(assoc % :server server) entries)))
        (get registry k)))

(defn registered-tools
  "Every MCP tool name currently registered, across all connected servers."
  [registry]
  (into #{} cat (vals (:tools registry))))

(defn all-resources [registry] (tagged registry :resources))
(defn all-prompts   [registry] (tagged registry :prompts))

;; ---------------------------------------------------------------------------
;; Integrant component
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :mcp/registry [_ {:keys [policy]}]
  (let [entries (connect-all policy)]
    (log/info "mcp registry started"
              {:enabled   (count (policy/enabled-servers policy))
               :connected (sort (keys entries))})
    {:clients   (update-vals entries :client)
     :tools     (update-vals entries :tools)
     :resources (update-vals entries :resources)
     :prompts   (update-vals entries :prompts)}))

(defmethod ig/halt-key! :mcp/registry [_ {:keys [clients tools]}]
  ;; Withdraw the tools first: once the connections are closed, a registration
  ;; left standing would proxy to a dead server.
  (run! mcp/unregister-tools! (vals tools))
  (doseq [[name conn] clients]
    (try
      (client/close! conn)
      (catch Throwable e
        (log/warn e "mcp: error while disconnecting — continuing" {:server name}))))
  (when (seq clients)
    (log/info "mcp registry stopped" {:disconnected (count clients)})))
