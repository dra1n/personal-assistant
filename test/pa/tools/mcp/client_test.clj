(ns pa.tools.mcp.client-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.tools.mcp.client :as client]
            [taoensso.timbre :as log])
  (:import [java.io BufferedReader BufferedWriter PipedInputStream PipedOutputStream]))

;; Failure-path tests warn by design; keep the test output readable.
(use-fixtures :each (fn [f] (log/with-min-level :error (f))))

;; ---------------------------------------------------------------------------
;; A fake server: two pipes standing in for a subprocess's stdio, so the whole
;; transport is exercised without ever spawning a process.
;; ---------------------------------------------------------------------------

(def ^:private pipe-size 65536)

(defn- fake-server []
  (let [c->s-out (PipedOutputStream.)
        c->s-in  (PipedInputStream. c->s-out pipe-size)
        s->c-out (PipedOutputStream.)
        s->c-in  (PipedInputStream. s->c-out pipe-size)]
    {:conn   (client/open :fake s->c-in c->s-out)
     :reads  (io/reader c->s-in)      ; what the client sent us
     :writes (io/writer s->c-out)     ; what we send the client
     :raw    s->c-out}))

(defn- take-message!
  "Block for the next message the client wrote."
  [{:keys [reads]}]
  (json/read-str (.readLine ^BufferedReader reads) :key-fn keyword))

(defn- send!
  "Write one message to the client, as a server would."
  [{:keys [writes]} msg]
  (doto ^BufferedWriter writes
    (.write (json/write-str msg))
    (.write "\n")
    (.flush)))

(defn- send-raw! [{:keys [writes]} ^String line]
  (doto ^BufferedWriter writes (.write line) (.write "\n") (.flush)))

(defmacro ^:private with-server
  "Like with-open, for a fake server: binds it and always disconnects after."
  [[sym init] & body]
  `(let [~sym ~init]
     (try ~@body (finally (client/close! (:conn ~sym))))))

(defn- await! [fut] (deref fut 2000 ::timed-out))

;; ---------------------------------------------------------------------------
;; Framing & correlation
;; ---------------------------------------------------------------------------

(deftest request-response-round-trip
  (with-server [srv (fake-server)]
    (let [result (future (client/request! (:conn srv) "tools/list" {} 2000))
          req    (take-message! srv)]
      (testing "the request is well-formed JSON-RPC 2.0 with a numeric id"
        (is (= "2.0" (:jsonrpc req)))
        (is (= "tools/list" (:method req)))
        (is (int? (:id req)))
        (is (= {} (:params req))))
      (send! srv {:jsonrpc "2.0" :id (:id req)
                  :result  {:tools [{:name "browser_navigate"}]}})
      (testing "the response's result comes back keywordized"
        (is (= {:tools [{:name "browser_navigate"}]} (await! result)))))))

(deftest params-are-passed-through
  (with-server [srv (fake-server)]
    (let [result (future (client/request! (:conn srv) "tools/call"
                                          {:name "browser_navigate"
                                           :arguments {:url "https://example.com"}}
                                          2000))
          req    (take-message! srv)]
      (is (= {:name "browser_navigate" :arguments {:url "https://example.com"}}
             (:params req)))
      (send! srv {:jsonrpc "2.0" :id (:id req) :result {:content []}})
      (is (= {:content []} (await! result))))))

(deftest responses-are-correlated-by-id
  (testing "two in-flight requests answered out of order each get their own result"
    (with-server [srv (fake-server)]
      (let [a    (future (client/request! (:conn srv) "first" {} 2000))
            b    (future (client/request! (:conn srv) "second" {} 2000))
            reqs (into {} (map (juxt :method :id)) [(take-message! srv) (take-message! srv)])]
        (is (= #{"first" "second"} (set (keys reqs))))
        (is (not= (get reqs "first") (get reqs "second")))
        ;; Answer in reverse order — correlation is by id, not arrival order.
        (send! srv {:jsonrpc "2.0" :id (get reqs "second") :result {:which "b"}})
        (send! srv {:jsonrpc "2.0" :id (get reqs "first")  :result {:which "a"}})
        (is (= {:which "a"} (await! a)))
        (is (= {:which "b"} (await! b)))))))

(deftest notifications-carry-no-id
  (with-server [srv (fake-server)]
    (client/notify! (:conn srv) "notifications/initialized" {})
    (let [msg (take-message! srv)]
      (is (= "notifications/initialized" (:method msg)))
      (is (not (contains? msg :id))))))

;; ---------------------------------------------------------------------------
;; Failure modes — each is a typed ex-info, never a hang
;; ---------------------------------------------------------------------------

(defn- ex-type [f]
  (try (f) ::no-throw
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest error-response-throws-rpc-error
  (with-server [srv (fake-server)]
    (let [result (future (ex-type #(client/request! (:conn srv) "tools/call" {} 2000)))
          req    (take-message! srv)]
      (send! srv {:jsonrpc "2.0" :id (:id req)
                  :error   {:code -32602 :message "unknown tool"}})
      (is (= :mcp/rpc-error (await! result))))))

(deftest error-response-carries-the-servers-error
  (with-server [srv (fake-server)]
    (let [result (future (try (client/request! (:conn srv) "tools/call" {} 2000)
                              (catch clojure.lang.ExceptionInfo e e)))
          req    (take-message! srv)]
      (send! srv {:jsonrpc "2.0" :id (:id req)
                  :error   {:code -32602 :message "unknown tool"}})
      (let [e (await! result)]
        (is (= "unknown tool" (ex-message e)))
        (is (= {:code -32602 :message "unknown tool"} (:error (ex-data e))))))))

(deftest request-times-out
  (with-server [srv (fake-server)]
    (let [result (future (ex-type #(client/request! (:conn srv) "tools/list" {} 100)))]
      (take-message! srv)                                   ; received, never answered
      (is (= :mcp/timeout (await! result))))))

(deftest server-eof-fails-pending-requests-immediately
  (testing "a dead server fails in-flight requests rather than making them wait out the timeout"
    (with-server [srv (fake-server)]
      (let [result (future (ex-type #(client/request! (:conn srv) "tools/list" {} 30000)))]
        (take-message! srv)
        (.close ^PipedOutputStream (:raw srv))              ; server exits
        (is (= :mcp/closed (await! result)))))))

(deftest requests-on-a-closed-connection-are-refused
  (let [srv (fake-server)]
    (client/close! (:conn srv))
    (is (= :mcp/closed (ex-type #(client/request! (:conn srv) "tools/list" {} 100))))))

(deftest unparseable-lines-do-not-kill-the-connection
  (with-server [srv (fake-server)]
    (send-raw! srv "not json at all")
    (send-raw! srv "{\"jsonrpc\": broken")
    (let [result (future (client/request! (:conn srv) "tools/list" {} 2000))
          req    (take-message! srv)]
      (send! srv {:jsonrpc "2.0" :id (:id req) :result {:tools []}})
      (is (= {:tools []} (await! result))))))

(deftest server-initiated-requests-are-refused-not-ignored
  (testing "we advertise no client capabilities, so the server gets method-not-found"
    (with-server [srv (fake-server)]
      (send! srv {:jsonrpc "2.0" :id 99 :method "sampling/createMessage" :params {}})
      (let [reply (take-message! srv)]
        (is (= 99 (:id reply)))
        (is (= -32601 (get-in reply [:error :code])))))))

;; ---------------------------------------------------------------------------
;; Handshake
;; ---------------------------------------------------------------------------

(def ^:private initialize-result
  {:protocolVersion client/protocol-version
   :serverInfo      {:name "playwright" :version "1.0.0"}
   :capabilities    {:tools {} :resources {:subscribe false}}})

(deftest handshake-negotiates-and-captures-capabilities
  (with-server [srv (fake-server)]
    (let [result (future (client/handshake! (:conn srv) 2000))
          req    (take-message! srv)]
      (testing "initialize advertises our protocol version and client info"
        (is (= "initialize" (:method req)))
        (is (= client/protocol-version (get-in req [:params :protocolVersion])))
        (is (= "personal-assistant" (get-in req [:params :clientInfo :name])))
        (is (= {} (get-in req [:params :capabilities]))))
      (send! srv {:jsonrpc "2.0" :id (:id req) :result initialize-result})
      (is (= initialize-result (await! result)))
      (testing "the handshake completes with notifications/initialized"
        (is (= "notifications/initialized" (:method (take-message! srv)))))
      (testing "declared capabilities gate what we later list"
        (is (client/supports? (:conn srv) :tools))
        (is (client/supports? (:conn srv) :resources))
        (is (not (client/supports? (:conn srv) :prompts))))
      (is (= {:name "playwright" :version "1.0.0"} @(:server-info (:conn srv)))))))

(deftest handshake-tolerates-a-different-negotiated-version
  (with-server [srv (fake-server)]
    (let [result (future (client/handshake! (:conn srv) 2000))
          req    (take-message! srv)]
      (send! srv {:jsonrpc "2.0" :id (:id req)
                  :result  (assoc initialize-result :protocolVersion "2024-11-05")})
      (is (= "2024-11-05" (:protocolVersion (await! result))))
      (is (client/supports? (:conn srv) :tools)))))

(deftest initialize-returns-nil-on-a-timed-out-handshake
  (testing "a silent server leaves us disconnected — no throw, nothing to clean up later"
    (with-server [srv (fake-server)]
      (let [result (future (client/initialize! (:conn srv) 100))]
        (take-message! srv)                                 ; initialize, never answered
        (is (nil? (await! result)))
        (is (not (client/connected? (:conn srv))))))))

(deftest initialize-returns-nil-when-the-server-errors
  (with-server [srv (fake-server)]
    (let [result (future (client/initialize! (:conn srv) 2000))
          req    (take-message! srv)]
      (send! srv {:jsonrpc "2.0" :id (:id req)
                  :error   {:code -32603 :message "internal error"}})
      (is (nil? (await! result)))
      (is (not (client/connected? (:conn srv)))))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(deftest close-is-idempotent
  (let [srv (fake-server)]
    (is (client/connected? (:conn srv)))
    (is (nil? (client/close! (:conn srv))))
    (is (nil? (client/close! (:conn srv))))
    (is (not (client/connected? (:conn srv))))))

(deftest connect-with-an-unstartable-command-returns-nil
  (testing "a missing binary is a warning and no client — never an exception"
    (is (nil? (client/connect :ghost {:command            "pa-no-such-binary-exists"
                                      :args               []
                                      :env                {}
                                      :connect-timeout-ms 1000})))))

;; ---------------------------------------------------------------------------
;; Protocol wrappers — request shape out, decoded payload back
;; ---------------------------------------------------------------------------

(defn- answering
  "Run `call` against the fake server, replying to its one request with
  `result`. Returns [request-sent, value-returned]."
  [srv call result]
  (let [ret (future (call))
        req (take-message! srv)]
    (send! srv {:jsonrpc "2.0" :id (:id req) :result result})
    [req (await! ret)]))

(def ^:private a-tool
  {:name        "browser_navigate"
   :description "Navigate to a URL"
   :inputSchema {:type "object"
                 :properties {:url {:type "string"}}
                 :required ["url"]}})

(deftest list-tools-unwraps-the-tools-key
  (with-server [srv (fake-server)]
    (let [[req tools] (answering srv #(client/list-tools (:conn srv) 2000) {:tools [a-tool]})]
      (is (= "tools/list" (:method req)))
      (is (= {} (:params req)))
      (testing "the JSON Schema survives keywordized, ready to use as a tool :schema"
        (is (= [a-tool] tools))))))

(deftest call-tool-sends-name-and-arguments
  (with-server [srv (fake-server)]
    (let [[req result] (answering srv
                                  #(client/call-tool (:conn srv) "browser_navigate"
                                                     {:url "https://example.com"} 2000)
                                  {:content [{:type "text" :text "ok"}] :isError false})]
      (is (= "tools/call" (:method req)))
      (is (= {:name "browser_navigate" :arguments {:url "https://example.com"}} (:params req)))
      (is (= {:content [{:type "text" :text "ok"}] :isError false} result)))))

(deftest call-tool-defaults-nil-arguments-to-an-empty-object
  (with-server [srv (fake-server)]
    (let [[req _] (answering srv #(client/call-tool (:conn srv) "browser_snapshot" nil 2000)
                             {:content []})]
      (is (= {} (get-in req [:params :arguments]))))))

(deftest call-tool-returns-tool-level-errors-rather-than-throwing
  (testing ":isError is a successful response — the caller decides what it means"
    (with-server [srv (fake-server)]
      (let [[_ result] (answering srv #(client/call-tool (:conn srv) "browser_click" {} 2000)
                                  {:content [{:type "text" :text "no such element"}]
                                   :isError true})]
        (is (true? (:isError result)))))))

(deftest list-resources-unwraps-the-resources-key
  (with-server [srv (fake-server)]
    (let [res {:uri "file:///notes.md" :name "notes" :mimeType "text/markdown"}
          [req resources] (answering srv #(client/list-resources (:conn srv) 2000)
                                     {:resources [res]})]
      (is (= "resources/list" (:method req)))
      (is (= [res] resources)))))

(deftest read-resource-sends-the-uri
  (with-server [srv (fake-server)]
    (let [[req result] (answering srv #(client/read-resource (:conn srv) "file:///notes.md" 2000)
                                  {:contents [{:uri "file:///notes.md"
                                               :mimeType "text/markdown"
                                               :text "# Notes"}]})]
      (is (= "resources/read" (:method req)))
      (is (= {:uri "file:///notes.md"} (:params req)))
      (is (= "# Notes" (-> result :contents first :text))))))

(deftest list-prompts-unwraps-the-prompts-key
  (with-server [srv (fake-server)]
    (let [prompt {:name "summarize" :description "Summarize a page"
                  :arguments [{:name "url" :required true}]}
          [req prompts] (answering srv #(client/list-prompts (:conn srv) 2000)
                                   {:prompts [prompt]})]
      (is (= "prompts/list" (:method req)))
      (is (= [prompt] prompts)))))

(deftest get-prompt-sends-name-and-arguments
  (with-server [srv (fake-server)]
    (let [[req result] (answering srv
                                  #(client/get-prompt (:conn srv) "summarize"
                                                      {:url "https://example.com"} 2000)
                                  {:description "Summarize a page"
                                   :messages [{:role "user"
                                               :content {:type "text" :text "Summarize…"}}]})]
      (is (= "prompts/get" (:method req)))
      (is (= {:name "summarize" :arguments {:url "https://example.com"}} (:params req)))
      (is (= "user" (-> result :messages first :role))))))

(deftest list-methods-follow-pagination-cursors
  (testing "all pages are drained — a paged server must not silently lose tools"
    (with-server [srv (fake-server)]
      (let [tools (future (client/list-tools (:conn srv) 2000))
            first-req (take-message! srv)]
        (is (nil? (get-in first-req [:params :cursor])))
        (send! srv {:jsonrpc "2.0" :id (:id first-req)
                    :result  {:tools [{:name "a"}] :nextCursor "page-2"}})
        (let [second-req (take-message! srv)]
          (is (= "page-2" (get-in second-req [:params :cursor])))
          (send! srv {:jsonrpc "2.0" :id (:id second-req)
                      :result  {:tools [{:name "b"}]}})
          (is (= [{:name "a"} {:name "b"}] (await! tools))))))))

(deftest list-methods-stop-on-a-repeated-cursor
  (testing "a server that hands back the same cursor forever does not spin us"
    ;; If the guard failed, the loop would ask for a third page and this future
    ;; would never deliver — so await! timing out is the real assertion here.
    (with-server [srv (fake-server)]
      (let [tools (future (client/list-tools (:conn srv) 2000))
            r1    (take-message! srv)]
        (send! srv {:jsonrpc "2.0" :id (:id r1)
                    :result  {:tools [{:name "a"}] :nextCursor "stuck"}})
        (let [r2 (take-message! srv)]
          (is (= "stuck" (get-in r2 [:params :cursor])))
          (send! srv {:jsonrpc "2.0" :id (:id r2)
                      :result  {:tools [{:name "b"}] :nextCursor "stuck"}})
          (is (= [{:name "a"} {:name "b"}] (await! tools))))))))

(deftest wrappers-use-the-connections-default-timeout
  (testing "the 2-arity needs no timeout argument at the call site"
    (with-server [srv (fake-server)]
      (let [[req tools] (answering srv #(client/list-tools (:conn srv)) {:tools []})]
        (is (= "tools/list" (:method req)))
        (is (= [] tools))))))
