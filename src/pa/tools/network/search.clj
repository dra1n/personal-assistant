(ns pa.tools.network.search
  "Web search tool — scrapes DuckDuckGo HTML search (no key required) and
  returns a list of {:title :url :snippet} results.

  Uses html.duckduckgo.com/html/ (the text-browser endpoint) rather than the
  Instant Answer API, which only returns knowledge-base responses and is empty
  for most web queries. Results are parsed with jsoup.

  The tool reads its HTTP client from ctx as :http. In production this is the
  HatoClient wired into the dispatcher's runtime context; tests inject a fake."
  (:require [clojure.string :as str]
            [pa.http :as http]
            [pa.tools.registry :as registry])
  (:import [org.jsoup Jsoup]))

(def ^:private ddg-html-url "https://html.duckduckgo.com/html/")

(def ^:private request-headers
  {"User-Agent" "Mozilla/5.0 (compatible; personal-assistant/1.0)"
   "Accept"     "text/html,application/xhtml+xml"})

(defn- http-of [ctx]
  (or (:http ctx)
      (throw (ex-info "no :http in ctx — is the HTTP client wired?"
                      {:type :tool/misconfigured}))))

(defn- extract-url
  "DDG HTML hrefs are redirect URLs like //duckduckgo.com/l/?uddg=<encoded-url>.
  Extract and decode the actual destination URL, or return the href as-is."
  [href]
  (if (str/includes? href "duckduckgo.com/l/")
    (some-> (re-find #"[?&]uddg=([^&]+)" href)
            second
            java.net.URLDecoder/decode)
    href))

(defn- parse-results
  "Parse DDG HTML search results page into [{:title :url :snippet}]."
  [html]
  (let [doc     (Jsoup/parse html)
        entries (.select doc ".result__body")]
    (into []
          (keep (fn [el]
                  (let [a       (.selectFirst el ".result__a")
                        snippet (.selectFirst el ".result__snippet")]
                    (when a
                      {:title   (str/trim (.text a))
                       :url     (extract-url (.attr a "href"))
                       :snippet (if snippet (str/trim (.text snippet)) "")}))))
          entries)))

(defn web-search
  "Search the web for :query. Returns {:query <str> :results [{:title :url :snippet}]}."
  [{:keys [query]} ctx]
  (let [resp   (http/fetch (http-of ctx) ddg-html-url
                           {:as           :string
                            :headers      request-headers
                            :query-params {:q query}})
        status (:status resp)]
    (when-not (= 200 status)
      (throw (ex-info (str "DuckDuckGo returned HTTP " status)
                      {:type :tool/http-error :status status})))
    {:query   query
     :results (parse-results (:body resp))}))

;; ---------------------------------------------------------------------------
;; Registration

(registry/reg-tool :network/web-search
  {:fn          web-search
   :description "Search the web using DuckDuckGo. Returns a list of results with title, URL, and snippet."
   :schema      {:type       "object"
                 :properties {:query {:type "string" :description "The search query."}}
                 :required   [:query]}})
