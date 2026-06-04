(ns pa.tools.network.search-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.http :as http]
            [pa.runtime.executor :as executor]
            [pa.tools.network.search :as search]
            [pa.tools.registry :as tools]))

;; ---------------------------------------------------------------------------
;; Fake HTTP client

(defrecord FakeHttp [response]
  http/HttpClient
  (post  [_ _url _opts] (throw (UnsupportedOperationException. "not used")))
  (fetch [_ _url _opts] response))

(defn- fake-http [body]
  (->FakeHttp {:status 200 :body body}))

(defn- ctx [body]
  {:http      (fake-http body)
   :dispatch! (fn [_])})

;; ---------------------------------------------------------------------------
;; Fixture DDG HTML response

(def ^:private ddg-html
  "<!DOCTYPE html><html><body>
  <div class='result__body'>
    <h2 class='result__title'>
      <a class='result__a' href='//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa&rut=x'>
        Alpha Result
      </a>
    </h2>
    <a class='result__snippet' href='#'>The first result snippet.</a>
  </div>
  <div class='result__body'>
    <h2 class='result__title'>
      <a class='result__a' href='//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fb&rut=y'>
        Beta Result
      </a>
    </h2>
    <a class='result__snippet' href='#'>The second result snippet.</a>
  </div>
  </body></html>")

(def ^:private ddg-html-no-results
  "<!DOCTYPE html><html><body></body></html>")

;; ---------------------------------------------------------------------------
;; Unit tests: web-search fn

(deftest web-search-returns-results-with-expected-shape
  (testing "results are a vec of {:title :url :snippet} maps"
    (let [{:keys [query results]} (search/web-search {:query "test"} (ctx ddg-html))]
      (is (= "test" query))
      (is (vector? results))
      (is (= 2 (count results)))
      (is (every? #(and (string? (:title %))
                        (string? (:url %))
                        (string? (:snippet %)))
                  results)))))

(deftest web-search-extracts-title-snippet-and-url
  (testing "title, snippet, and URL are extracted correctly"
    (let [{:keys [results]} (search/web-search {:query "q"} (ctx ddg-html))
          first-result (first results)]
      (is (= "Alpha Result" (:title first-result)))
      (is (= "The first result snippet." (:snippet first-result)))
      (is (= "https://example.com/a" (:url first-result))))))

(deftest web-search-decodes-ddg-redirect-urls
  (testing "DDG redirect hrefs are decoded to actual destination URLs"
    (let [{:keys [results]} (search/web-search {:query "q"} (ctx ddg-html))]
      (is (= "https://example.com/a" (:url (first results))))
      (is (= "https://example.com/b" (:url (second results)))))))

(deftest web-search-empty-page-returns-empty-results
  (testing "a page with no result__body elements returns an empty results vec"
    (let [{:keys [results]} (search/web-search {:query "nothing"} (ctx ddg-html-no-results))]
      (is (= [] results)))))

(deftest web-search-throws-on-non-200
  (testing "a non-200 response throws an ex-info with :type :tool/http-error"
    (let [client (->FakeHttp {:status 403 :body ""})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"403"
           (search/web-search {:query "q"} {:http client :dispatch! (fn [_])}))))))

;; ---------------------------------------------------------------------------
;; Integration: registered tool invoked via executor

(def ^:private dispatched (atom []))

(use-fixtures :each
  (fn [f]
    (let [snap (tools/snapshot)]
      (reset! dispatched [])
      (try (f) (finally (tools/restore! snap))))))

(deftest registered-tool-reachable-via-registry
  (testing ":network/web-search is in the registry with the right schema"
    (let [spec (tools/get-tool :network/web-search)]
      (is (some? spec))
      (is (ifn? (:fn spec)))
      (is (= [:query] (get-in spec [:schema :required]))))))

(deftest dry-run-skips-http-call
  (testing "dry-run returns :dry-run status without touching the HTTP client"
    (let [no-http (reify http/HttpClient
                    (fetch [_ _ _] (throw (AssertionError. "HTTP must not be called in dry-run")))
                    (post  [_ _ _] (throw (AssertionError. "HTTP must not be called in dry-run"))))
          ctx {:http no-http :dispatch! (fn [ev] (swap! dispatched conj ev))}]
      (executor/execute-effect :tool/invoke
                               {:tool/name     :network/web-search
                                :tool/args     {:query "test"}
                                :tool/dry-run? true}
                               ctx)
      (is (= :dry-run (:tool/status (first @dispatched)))))))

(deftest schema-validation-rejects-missing-query
  (testing "executor emits :tool/invalid-args when :query is missing"
    (let [ctx {:http      (fake-http ddg-html-no-results)
               :dispatch! (fn [ev] (swap! dispatched conj ev))}]
      (executor/execute-effect :tool/invoke
                               {:tool/name :network/web-search :tool/args {}}
                               ctx))
    (let [r (first @dispatched)]
      (is (= :error (:tool/status r)))
      (is (= :tool/invalid-args (get-in r [:tool/error :type]))))))
