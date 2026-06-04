(ns pa.tools.network.search-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
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
  (->FakeHttp {:status 200 :body (json/write-str body)}))

(defn- ctx [body]
  {:http           (fake-http body)
   :dispatch! (fn [_])})

;; ---------------------------------------------------------------------------
;; Fixture DDG response bodies

(def ^:private ddg-with-results
  {:Abstract     ""
   :RelatedTopics [{:FirstURL "https://example.com/a"
                    :Text     "Alpha — The first result snippet."}
                   {:FirstURL "https://example.com/b"
                    :Text     "Beta — The second result snippet."}
                   {:Topics [{:FirstURL "https://example.com/c" :Text "Nested"}]
                    :Name   "More at..."}]
   :Results      [{:FirstURL "https://direct.com/x"
                   :Text     "Direct — A direct result."}]})

(def ^:private ddg-empty
  {:Abstract "" :RelatedTopics [] :Results []})

;; ---------------------------------------------------------------------------
;; Unit tests: web-search fn

(deftest web-search-returns-results-with-expected-shape
  (testing "results are a vec of {:title :url :snippet} maps"
    (let [{:keys [query results]} (search/web-search {:query "test"} (ctx ddg-with-results))]
      (is (= "test" query))
      (is (vector? results))
      (is (every? #(and (string? (:title %))
                        (string? (:url %))
                        (string? (:snippet %)))
                  results)))))

(deftest web-search-extracts-title-and-snippet
  (testing "text before \" — \" is title; text after is snippet"
    (let [{:keys [results]} (search/web-search {:query "q"} (ctx ddg-with-results))
          by-url (into {} (map (juxt :url identity) results))]
      (is (= "Alpha" (:title (by-url "https://example.com/a"))))
      (is (= "The first result snippet." (:snippet (by-url "https://example.com/a"))))
      (is (= "Direct" (:title (by-url "https://direct.com/x"))))
      (is (= "A direct result." (:snippet (by-url "https://direct.com/x")))))))

(deftest web-search-skips-nested-topic-groups
  (testing "entries with :Topics instead of :FirstURL are dropped"
    (let [{:keys [results]} (search/web-search {:query "q"} (ctx ddg-with-results))
          urls (map :url results)]
      (is (not-any? #(= "https://example.com/c" %) urls)))))

(deftest web-search-combines-results-and-related-topics
  (testing ":Results entries appear alongside :RelatedTopics entries"
    (let [{:keys [results]} (search/web-search {:query "q"} (ctx ddg-with-results))
          urls (set (map :url results))]
      (is (contains? urls "https://direct.com/x"))
      (is (contains? urls "https://example.com/a")))))

(deftest web-search-empty-response-returns-empty-results
  (testing "an empty DDG response returns an empty results vec"
    (let [{:keys [results]} (search/web-search {:query "nothing"} (ctx ddg-empty))]
      (is (= [] results)))))

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

(deftest schema-validation-rejects-missing-query
  (testing "executor emits :tool/invalid-args when :query is missing"
    (let [ctx {:http      (fake-http ddg-empty)
               :dispatch! (fn [ev] (swap! dispatched conj ev))}]
      (executor/execute-effect :tool/invoke
                               {:tool/name :network/web-search :tool/args {}}
                               ctx))
    (let [r (first @dispatched)]
      (is (= :error (:tool/status r)))
      (is (= :tool/invalid-args (get-in r [:tool/error :type]))))))
