(ns pa.tools.mcp-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.runtime.executor :as executor]
            [pa.tools.mcp :as mcp]
            [pa.tools.mcp.client :as client]
            [pa.tools.registry :as tools]
            [taoensso.timbre :as log]))

(def ^:private dispatched (atom []))

(use-fixtures :each
  (fn [f]
    (let [snap (tools/snapshot)]
      (reset! dispatched [])
      (log/with-min-level :error
        (try (f) (finally (tools/restore! snap)))))))

(defn- ctx [] {:dispatch! (fn [e] (swap! dispatched conj e))})

(defn- invoke!
  "Drive a registered tool through the real :tool/invoke effect and return the
  dispatched :tool/result — the same path a native tool takes."
  [tool-name args]
  (executor/execute-effect :tool/invoke
                           {:tool/name tool-name :tool/args args}
                           (ctx))
  (first @dispatched))

(def ^:private conn {:name :playwright})

(def ^:private navigate
  ;; Exactly what the transport yields for a real tools/list entry: keys
  ;; keywordized, but the strings *inside* :required left as JSON wrote them.
  {:name        "browser_navigate"
   :description "Navigate to a URL"
   :inputSchema {:type       "object"
                 :properties {:url {:type "string"}}
                 :required   ["url"]}})

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(deftest registers-tools-namespaced-by-server
  (let [registered (mcp/register-tools! :playwright conn [navigate {:name "browser_click"}])]
    (is (= [:mcp-playwright/browser_navigate :mcp-playwright/browser_click] registered))
    (is (contains? (tools/registered-tools) :mcp-playwright/browser_navigate))))

(deftest input-schema-is-carried-through-verbatim
  (testing "JSON Schema is the tool's schema — no translation layer"
    (mcp/register-tools! :playwright conn [navigate])
    (is (= (:inputSchema navigate)
           (:schema (tools/get-tool :mcp-playwright/browser_navigate))))
    (testing "and it is what validate-args enforces"
      (is (nil? (tools/validate-args (:inputSchema navigate) {:url "https://example.com"})))
      (is (some? (tools/validate-args (:inputSchema navigate) {}))))))

(deftest a-tool-without-a-description-still-registers
  (mcp/register-tools! :playwright conn [{:name "browser_snapshot"}])
  (let [{:keys [description schema]} (tools/get-tool :mcp-playwright/browser_snapshot)]
    (is (string? description) "reg-tool requires one, so we synthesize a useful default")
    (is (re-find #"playwright" description))
    (is (= {} schema) "no inputSchema means no declared arguments")))

(deftest tools-without-a-provider-safe-name-are-skipped
  (testing "one unusable name would be rejected with the whole request, so drop it"
    (let [registered (mcp/register-tools! :s conn [{:description "nameless"}
                                                   {:name ""}
                                                   {:name "has spaces"}
                                                   {:name "dotted.name"}
                                                   {:name "good"}])]
      (is (= [:mcp-s/good] registered)))))

(deftest tool-keys-are-safe-for-provider-apis
  (testing "both halves of the name survive encoding into a function name"
    (doseq [[server tool] [[:playwright "browser_click"]
                           [:my.server "some-tool"]
                           [:with-dash "t"]]]
      (let [k (mcp/tool-key server tool)]
        (is (re-matches mcp/provider-safe-name (namespace k)) (str "namespace of " k))
        (is (re-matches mcp/provider-safe-name (name k)) (str "name of " k))))))

(deftest servers-with-the-same-tool-name-do-not-collide
  (mcp/register-tools! :one conn [{:name "search"}])
  (mcp/register-tools! :two conn [{:name "search"}])
  (is (= #{:mcp-one/search :mcp-two/search}
         (set (filter #(= "search" (name %)) (tools/registered-tools))))))

(deftest unregister-withdraws-them
  (let [registered (mcp/register-tools! :playwright conn [navigate])]
    (mcp/unregister-tools! registered)
    (is (nil? (tools/get-tool :mcp-playwright/browser_navigate)))))

(deftest mcp-tools-are-advertised-to-the-llm-like-any-other
  (testing "no MCP-specific wiring: advertise enumerates the whole registry"
    (mcp/register-tools! :playwright conn [navigate])
    (let [ad (first (filter #(= :mcp-playwright/browser_navigate (:name %)) (tools/advertise)))]
      (is (some? ad))
      (is (= "Navigate to a URL" (:description ad)))
      (is (= (:inputSchema navigate) (:parameters ad))))))

;; ---------------------------------------------------------------------------
;; Invocation — through :tool/invoke, exactly like a native tool
;; ---------------------------------------------------------------------------

(deftest a-successful-call-produces-an-ok-result
  (mcp/register-tools! :playwright conn [navigate])
  (with-redefs [client/call-tool (fn [_ tool args]
                                   (is (= "browser_navigate" tool))
                                   (is (= {:url "https://example.com"} args))
                                   {:content [{:type "text" :text "Navigated"}] :isError false})]
    (let [r (invoke! :mcp-playwright/browser_navigate {:url "https://example.com"})]
      (is (= :tool/result (:event/type r)))
      (is (= :ok (:tool/status r)))
      (is (= {:content [{:type "text" :text "Navigated"}] :isError false} (:tool/output r)))
      (is (nat-int? (:tool/duration-ms r))))))

(deftest argument-validation-happens-before-the-server-is-called
  (testing "a missing required argument fails locally, like any native tool"
    (mcp/register-tools! :playwright conn [navigate])
    (let [called (atom false)]
      (with-redefs [client/call-tool (fn [& _] (reset! called true) {})]
        (let [r (invoke! :mcp-playwright/browser_navigate {})]
          (is (= :error (:tool/status r)))
          (is (= :tool/invalid-args (get-in r [:tool/error :type])))
          (is (false? @called) "the server is never bothered with invalid arguments"))))))

(deftest a-tool-level-failure-becomes-an-error-result
  (testing ":isError comes back on a *successful* response and must still fail the tool"
    (mcp/register-tools! :playwright conn [{:name "browser_click"}])
    (with-redefs [client/call-tool (fn [& _]
                                     {:content [{:type "text" :text "no such element"}]
                                      :isError true})]
      (let [r (invoke! :mcp-playwright/browser_click {})]
        (is (= :error (:tool/status r)))
        (is (= :mcp/tool-error (get-in r [:tool/error :type])))
        (is (= :mcp/is-error (get-in r [:tool/error :mcp/cause])))
        (is (= :playwright (get-in r [:tool/error :mcp/server])))
        (is (= "no such element" (get-in r [:tool/error :message]))
            "the server's own text is the message")))))

(deftest a-protocol-failure-becomes-an-error-result
  (mcp/register-tools! :playwright conn [{:name "browser_click"}])
  (with-redefs [client/call-tool (fn [& _]
                                   (throw (ex-info "unknown tool"
                                                   {:type :mcp/rpc-error
                                                    :error {:code -32602}})))]
    (let [r (invoke! :mcp-playwright/browser_click {})]
      (is (= :error (:tool/status r)))
      (is (= :mcp/tool-error (get-in r [:tool/error :type])))
      (is (= :mcp/rpc-error (get-in r [:tool/error :mcp/cause]))
          "the originating channel is preserved for diagnosis"))))

(deftest a-dead-connection-becomes-an-error-result
  (testing "a server that went away fails its tools rather than hanging or crashing"
    (mcp/register-tools! :playwright conn [{:name "browser_click"}])
    (with-redefs [client/call-tool (fn [& _]
                                     (throw (ex-info "mcp: connection is closed"
                                                     {:type :mcp/closed})))]
      (let [r (invoke! :mcp-playwright/browser_click {})]
        (is (= :error (:tool/status r)))
        (is (= :mcp/closed (get-in r [:tool/error :mcp/cause])))))))

(deftest a-dry-run-never-reaches-the-server
  (testing "MCP tools honour dry-run like native ones — no hidden execution path"
    (mcp/register-tools! :playwright conn [navigate])
    (let [called (atom false)]
      (with-redefs [client/call-tool (fn [& _] (reset! called true) {})]
        (executor/execute-effect :tool/invoke
                                 {:tool/name     :mcp-playwright/browser_navigate
                                  :tool/args     {:url "https://example.com"}
                                  :tool/dry-run? true}
                                 (ctx))
        (is (= :dry-run (:tool/status (first @dispatched))))
        (is (false? @called))))))
