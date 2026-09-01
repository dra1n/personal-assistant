(ns pa.tools.mcp.client
  "JSON-RPC 2.0 over stdio — the transport half of MCP support.

  A connection is a plain map holding the two streams, a pending-request table,
  and the reader thread that drains them. `open` wraps an already-open stdio
  pair (which is how tests drive it, against a piped pair rather than a real
  subprocess); `spawn` builds one around a `ProcessBuilder` subprocess; and
  `connect` is the pair of those plus the MCP `initialize` handshake.

  Concurrency mirrors `pa.runtime.executor`'s async hop rather than inventing a
  second idiom: a request registers a promise under its numeric id, writes the
  message, and derefs with a timeout, while one dedicated reader thread demuxes
  arriving responses back onto those promises. Writes are serialized on the
  output stream so two threads can share one connection safely.

  Everything here degrades rather than crashes. A server that dies, never
  answers, or was never startable fails its in-flight requests with a typed
  ex-info and leaves the connection closed — it is the caller's job (see
  `:mcp/registry`) to carry on without that server's tools."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.io BufferedReader BufferedWriter InputStream OutputStream]
           [java.util.concurrent TimeUnit]))

(def protocol-version
  "The MCP protocol version we advertise in `initialize`. Servers may negotiate
  a different one in their response; we log that and carry on, since the
  request/response shapes we use are stable across these revisions."
  "2025-06-18")

(def client-info
  {:name "personal-assistant" :version "0.1.0"})

(def ^:private close-timeout-ms
  "How long a server gets to exit after its stdin is closed before we stop
  being polite about it."
  2000)

(def ^:private stderr-tail-lines
  "How many recent stderr lines to keep for failure diagnostics. A server that
  won't start usually explains why on stderr, and that explanation is the
  single most useful thing to put in the warning."
  20)

;; ---------------------------------------------------------------------------
;; Framing — newline-delimited JSON, one message per line
;; ---------------------------------------------------------------------------

(defn- write-message!
  "Write one JSON-RPC message and flush. Serialized on the output stream: any
  thread may issue a request on a shared connection."
  [{:keys [^BufferedWriter out]} msg]
  (locking out
    (.write out ^String (json/write-str msg))
    (.write out "\n")
    (.flush out)))

(defn- fail-pending!
  "Hand every in-flight request the same sentinel, so callers fail fast instead
  of blocking until their individual timeouts when the server goes away."
  [{:keys [pending]} sentinel]
  (doseq [[_ p] (first (swap-vals! pending empty))]
    (deliver p sentinel)))

;; ---------------------------------------------------------------------------
;; Reader thread
;; ---------------------------------------------------------------------------

(defn- deliver-response! [{:keys [name pending]} {:keys [id] :as msg}]
  (if-let [p (get @pending id)]
    (do (swap! pending dissoc id)
        (deliver p msg))
    (log/warn "mcp: response for an unknown request id — ignoring"
              {:server name :id id})))

(defn- refuse-request!
  "The server asked *us* to do something (sampling, roots, elicitation). We
  advertise no such capabilities, so answer method-not-found rather than
  leaving the server waiting on a reply that will never come."
  [{:keys [name] :as conn} id method]
  (log/debug "mcp: refusing server-initiated request" {:server name :method method})
  (try
    (write-message! conn {:jsonrpc "2.0" :id id
                          :error   {:code -32601 :message (str "unsupported method: " method)}})
    (catch Throwable e
      (log/debug e "mcp: could not answer server-initiated request" {:server name}))))

(defn- handle-line! [{:keys [name] :as conn} line]
  (let [msg (try (json/read-str line :key-fn keyword)
                 (catch Throwable _
                   (log/warn "mcp: dropping unparseable message" {:server name :line line})
                   nil))
        {:keys [id method]} msg]
    (cond
      (nil? msg)      nil
      (and id method) (refuse-request! conn id method)
      id              (deliver-response! conn msg)
      method          (log/debug "mcp: server notification" {:server name :method method})
      :else           (log/warn "mcp: unrecognized message" {:server name :message msg}))))

(defn- reader-loop [{:keys [name closed?] :as conn} ^BufferedReader rdr]
  (try
    (loop []
      (when-let [line (.readLine rdr)]
        (when-not (str/blank? line)
          (handle-line! conn line))
        (recur)))
    (catch Throwable e
      (when-not @closed?
        (log/warn e "mcp: reader thread stopped" {:server name})))
    (finally
      ;; EOF or error: the server is gone, so nothing in flight will ever be
      ;; answered. Closing the reader is this thread's job — see close!.
      (try (.close rdr) (catch Throwable _ nil))
      (fail-pending! conn ::closed))))

(defn- start-thread! [nm f]
  (doto (Thread. ^Runnable f (str "mcp-" nm))
    (.setDaemon true)
    (.start)))

(defn- drain-stderr!
  "Log the server's stderr and keep the tail for failure diagnostics."
  [{:keys [name stderr]} ^InputStream err]
  (start-thread!
   (str (clojure.core/name name) "-stderr")
   (fn []
     (try
       (with-open [rdr (io/reader err)]
         (loop []
           (when-let [line (.readLine ^BufferedReader rdr)]
             (log/debug "mcp server stderr" {:server name :line line})
             (swap! stderr #(vec (take-last stderr-tail-lines (conj % line))))
             (recur))))
       (catch Throwable _ nil)))))

;; ---------------------------------------------------------------------------
;; Connections
;; ---------------------------------------------------------------------------

(defn open
  "Wrap an open stdio pair as a connection and start its reader thread. `in` is
  the stream messages arrive on (the server's stdout); `out` is the stream we
  write to (the server's stdin). `opts` may carry the `:process` this pair
  belongs to, so `close!` can reap it."
  ([name ^InputStream in ^OutputStream out] (open name in out {}))
  ([name ^InputStream in ^OutputStream out {:keys [process]}]
   (let [rdr  (io/reader in)
         conn {:name         name
               :in           rdr
               :out          (io/writer out)
               :process      process
               :pending      (atom {})
               :next-id      (atom 0)
               :closed?      (atom false)
               :capabilities (atom {})
               :server-info  (atom nil)
               :stderr       (atom [])}]
     (assoc conn :reader (start-thread! (clojure.core/name name)
                                        #(reader-loop conn rdr))))))

(defn connected? [conn]
  (boolean (and conn (not @(:closed? conn)))))

(defn stderr-tail
  "The server's most recent stderr lines — what to show when it misbehaves."
  [conn]
  (some-> conn :stderr deref))

(defn close!
  "Disconnect: close stdin so the server sees EOF, give it a moment to exit on
  its own, then destroy it forcibly. Idempotent."
  [{:keys [name ^Process process closed? ^Thread reader ^BufferedWriter out] :as conn}]
  (when (and conn (compare-and-set! closed? false true))
    (try (.close out) (catch Throwable _ nil))
    (when process
      (when-not (.waitFor process close-timeout-ms TimeUnit/MILLISECONDS)
        (log/warn "mcp: server did not exit after EOF — destroying" {:server name})
        (.destroyForcibly process)))
    ;; Never close the input stream from here: the reader thread is parked
    ;; inside readLine holding that reader's monitor, and close would block on
    ;; it forever. A dead process EOFs its stdout on its own; interrupting
    ;; covers the streams that don't (a pipe, in tests). Either way the reader
    ;; thread closes its own stream on the way out.
    (when reader (.interrupt reader))
    (fail-pending! conn ::closed)
    (log/debug "mcp: disconnected" {:server name}))
  nil)

;; ---------------------------------------------------------------------------
;; Requests
;; ---------------------------------------------------------------------------

(defn notify!
  "Send a notification — no id, no response expected."
  [conn method params]
  (write-message! conn {:jsonrpc "2.0" :method method :params (or params {})})
  nil)

(defn request!
  "Send a request and block for its response, up to `timeout-ms`. Returns the
  JSON-RPC `result`, keywordized. Throws ex-info with a `:type` of
  `:mcp/closed`, `:mcp/timeout`, `:mcp/transport-error`, or `:mcp/rpc-error`
  (carrying the server's `:error` map) — callers turn these into a failed tool
  result rather than letting them escape."
  [{:keys [name pending next-id closed?] :as conn} method params timeout-ms]
  (when @closed?
    (throw (ex-info "mcp: connection is closed"
                    {:type :mcp/closed :server name :method method})))
  (let [id (swap! next-id inc)
        p  (promise)]
    (swap! pending assoc id p)
    (try
      (write-message! conn {:jsonrpc "2.0" :id id :method method :params (or params {})})
      (catch Throwable e
        (swap! pending dissoc id)
        (throw (ex-info "mcp: could not send request"
                        {:type :mcp/transport-error :server name :method method} e))))
    (let [resp (deref p timeout-ms ::timeout)]
      (cond
        (= ::timeout resp)
        (do (swap! pending dissoc id)
            (throw (ex-info (str "mcp: " method " timed out after " timeout-ms "ms")
                            {:type :mcp/timeout :server name :method method
                             :timeout-ms timeout-ms})))

        (= ::closed resp)
        (throw (ex-info (str "mcp: connection closed while awaiting " method)
                        {:type :mcp/closed :server name :method method}))

        (:error resp)
        (throw (ex-info (or (:message (:error resp)) (str "mcp: " method " failed"))
                        {:type :mcp/rpc-error :server name :method method
                         :error (:error resp)}))

        :else (:result resp)))))

;; ---------------------------------------------------------------------------
;; Handshake
;; ---------------------------------------------------------------------------

(defn handshake!
  "Run the MCP `initialize` exchange, capture the server's declared
  capabilities, and send `notifications/initialized`. Returns the initialize
  result; throws if the server errors or does not answer in time."
  [{:keys [name capabilities server-info] :as conn} timeout-ms]
  (let [result (request! conn "initialize"
                         {:protocolVersion protocol-version
                          :clientInfo      client-info
                          ;; We implement no client-side capabilities — see
                          ;; refuse-request!.
                          :capabilities    {}}
                         timeout-ms)]
    (reset! capabilities (or (:capabilities result) {}))
    (reset! server-info (:serverInfo result))
    (when-let [negotiated (:protocolVersion result)]
      (when (not= negotiated protocol-version)
        (log/info "mcp: server negotiated a different protocol version"
                  {:server name :requested protocol-version :negotiated negotiated})))
    (notify! conn "notifications/initialized" {})
    result))

(defn supports?
  "True if the connected server declared the `capability` (:tools, :resources,
  :prompts). Callers skip listing what a server never offered."
  [conn capability]
  (boolean (some-> conn :capabilities deref (get capability))))

;; ---------------------------------------------------------------------------
;; Connecting
;; ---------------------------------------------------------------------------

(defn spawn
  "Start the server subprocess and wrap its stdio as a connection. Throws if the
  process cannot be started (missing binary, bad working directory)."
  [name {:keys [command args env]}]
  (let [pb   (ProcessBuilder. ^java.util.List (into [command] args))
        _    (doseq [[k v] env] (.put (.environment pb) k v))
        proc (.start pb)
        conn (open name (.getInputStream proc) (.getOutputStream proc) {:process proc})]
    (drain-stderr! conn (.getErrorStream proc))
    conn))

(defn initialize!
  "Complete the handshake on an open connection. Returns the connection on
  success, or nil after closing it — a server that cannot complete the
  handshake contributes nothing rather than taking anything down."
  [{:keys [name] :as conn} timeout-ms]
  (try
    (let [result (handshake! conn timeout-ms)]
      (log/info "mcp: connected"
                {:server name
                 :server-info  (:serverInfo result)
                 :capabilities (keys (:capabilities result))})
      conn)
    (catch Throwable e
      (log/warn "mcp: handshake failed — server disconnected"
                {:server name :error (ex-message e) :stderr (stderr-tail conn)})
      (close! conn)
      nil)))

(defn connect
  "Spawn `server-config`'s subprocess and complete the MCP handshake. Returns a
  connected client, or nil if the server could not be started or did not answer
  within its `:connect-timeout-ms`. Never throws: one unreachable server must
  not cost the app its startup or its other servers."
  [name {:keys [command connect-timeout-ms] :as server-config}]
  (if-let [conn (try
                  (spawn name server-config)
                  (catch Throwable e
                    (log/warn "mcp: could not start server"
                              {:server name :command command :error (ex-message e)})
                    nil))]
    (initialize! conn connect-timeout-ms)
    nil))
