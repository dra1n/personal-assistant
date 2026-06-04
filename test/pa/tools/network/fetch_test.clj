(ns pa.tools.network.fetch-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.http :as http]
            [pa.runtime.executor :as executor]
            [pa.tools.network.fetch :as fetch]
            [pa.tools.registry :as tools]))

;; ---------------------------------------------------------------------------
;; Fake HTTP client

(defrecord FakeHttp [response]
  http/HttpClient
  (post  [_ _url _opts] (throw (UnsupportedOperationException. "not used")))
  (fetch [_ _url _opts] response))

(defn- fake-http [body]
  (->FakeHttp {:status 200 :body body}))

;; SSRF passthrough: (constantly nil) means every URL is safe.
(def ^:private no-ssrf (constantly nil))

(defn- ctx [body]
  {:http       (fake-http body)
   :ssrf-check no-ssrf
   :dispatch!  (fn [_])})

;; ---------------------------------------------------------------------------
;; Fixture HTML

(def ^:private fixture-html
  "<!DOCTYPE html>
  <html>
    <head><title>Test Page</title></head>
    <body>
      <script>alert('xss');</script>
      <style>body { color: red; }</style>
      <noscript>Enable JavaScript.</noscript>
      <h1>Hello World</h1>
      <p>Some text content.</p>
      <p>Another paragraph.</p>
    </body>
  </html>")

;; ---------------------------------------------------------------------------
;; Text extraction

(deftest fetch-page-text-strips-script-tags
  (testing ":text format removes <script> content"
    (let [{:keys [content]} (fetch/fetch-page {:url "https://example.com"} (ctx fixture-html))]
      (is (not (re-find #"alert" content))))))

(deftest fetch-page-text-strips-style-tags
  (testing ":text format removes <style> content"
    (let [{:keys [content]} (fetch/fetch-page {:url "https://example.com"} (ctx fixture-html))]
      (is (not (re-find #"color: red" content))))))

(deftest fetch-page-text-preserves-body-text
  (testing ":text format retains meaningful body text"
    (let [{:keys [content]} (fetch/fetch-page {:url "https://example.com"} (ctx fixture-html))]
      (is (re-find #"Hello World" content))
      (is (re-find #"Some text content" content))
      (is (re-find #"Another paragraph" content)))))

(deftest fetch-page-default-format-is-text
  (testing "omitting :format defaults to text"
    (let [{:keys [format]} (fetch/fetch-page {:url "https://example.com"} (ctx fixture-html))]
      (is (= "text" format)))))

;; ---------------------------------------------------------------------------
;; Raw format

(deftest fetch-page-raw-returns-original-html
  (testing ":format \"raw\" returns the original HTML body unchanged"
    (let [{:keys [content format]} (fetch/fetch-page {:url "https://example.com" :format "raw"}
                                                     (ctx fixture-html))]
      (is (= "raw" format))
      (is (= fixture-html content)))))

;; ---------------------------------------------------------------------------
;; Result map shape

(deftest fetch-page-result-echoes-url
  (testing "result map includes the requested :url"
    (let [{:keys [url]} (fetch/fetch-page {:url "https://example.com"} (ctx fixture-html))]
      (is (= "https://example.com" url)))))

(deftest fetch-page-result-includes-format
  (testing "result map includes :format matching what was requested"
    (let [{:keys [format]} (fetch/fetch-page {:url "https://example.com" :format "raw"}
                                             (ctx fixture-html))]
      (is (= "raw" format)))))

;; ---------------------------------------------------------------------------
;; Error cases

(deftest fetch-page-throws-on-non-200
  (testing "non-200 response throws ex-info with :type :tool/http-error"
    (let [client (->FakeHttp {:status 404 :body "Not Found"})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"404"
           (fetch/fetch-page {:url "https://example.com"}
                             {:http client :ssrf-check no-ssrf :dispatch! (fn [_])}))))))

(deftest fetch-page-throws-on-ssrf-blocked
  (testing "blocked SSRF check throws ex-info with :type :tool/ssrf-blocked"
    (let [blocking-check (constantly "Blocked: private IP")
          ctx            {:http (fake-http fixture-html) :ssrf-check blocking-check :dispatch! (fn [_])}]
      (let [e (try (fetch/fetch-page {:url "http://192.168.1.1/"} ctx)
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e))
        (is (= :tool/ssrf-blocked (:type (ex-data e))))))))

;; ---------------------------------------------------------------------------
;; Registry integration

(def ^:private dispatched (atom []))

(use-fixtures :each
  (fn [f]
    (let [snap (tools/snapshot)]
      (reset! dispatched [])
      (try (f) (finally (tools/restore! snap))))))

(deftest registered-tool-reachable-via-registry
  (testing ":network/fetch-page is in the registry with the right shape"
    (let [spec (tools/get-tool :network/fetch-page)]
      (is (some? spec))
      (is (ifn? (:fn spec)))
      (is (= [:url] (get-in spec [:schema :required]))))))

(deftest schema-validation-rejects-missing-url
  (testing "executor emits :tool/invalid-args when :url is missing"
    (let [ctx {:http       (fake-http fixture-html)
               :ssrf-check no-ssrf
               :dispatch!  (fn [ev] (swap! dispatched conj ev))}]
      (executor/execute-effect :tool/invoke
                               {:tool/name :network/fetch-page :tool/args {}}
                               ctx))
    (let [r (first @dispatched)]
      (is (= :error (:tool/status r)))
      (is (= :tool/invalid-args (get-in r [:tool/error :type]))))))
