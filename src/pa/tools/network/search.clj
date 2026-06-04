(ns pa.tools.network.search
  "Web search tool — queries DuckDuckGo Instant Answer API (no key required)
  and returns a list of {:title :url :snippet} results.

  The tool reads its HTTP client from ctx as :http. In production this is the
  HatoClient wired into the dispatcher's runtime context; tests inject a fake."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pa.http :as http]
            [pa.tools.registry :as registry]))

(def ^:private ddg-url "https://api.duckduckgo.com/")

(defn- http-of [ctx]
  (or (:http ctx)
      (throw (ex-info "no :http in ctx — is the HTTP client wired?"
                      {:type :tool/misconfigured}))))

(defn- parse-entry
  "Extract {:title :url :snippet} from a RelatedTopics / Results entry.
  Returns nil for entries without a FirstURL (nested topic groups)."
  [entry]
  (let [url  (not-empty (:FirstURL entry))
        text (str/trim (or (:Text entry) ""))]
    (when url
      (let [[title snippet] (str/split text #" — " 2)]
        {:title   (str/trim (or title ""))
         :snippet (str/trim (or snippet title ""))
         :url     url}))))

(def ^:private request-headers
  {"User-Agent" "Mozilla/5.0 (compatible; personal-assistant/1.0)"
   "Accept"     "application/json"})

(defn web-search
  "Search the web for :query. Returns {:query <str> :results [{:title :url :snippet}]}."
  [{:keys [query]} ctx]
  (let [resp   (http/fetch (http-of ctx) ddg-url
                           {:as           :string
                            :headers      request-headers
                            :query-params {:q             query
                                           :format        "json"
                                           :no_html       1
                                           :skip_disambig 1}})
        status (:status resp)]
    (when-not (= 200 status)
      (throw (ex-info (str "DuckDuckGo returned HTTP " status)
                      {:type :tool/http-error :status status})))
    (let [body    (json/read-str (:body resp) :key-fn keyword)
          topics  (filter :FirstURL (:RelatedTopics body []))
          results (concat (:Results body []) topics)
          parsed  (into [] (keep parse-entry) results)]
      {:query query :results parsed})))

;; ---------------------------------------------------------------------------
;; Registration

(registry/reg-tool :network/web-search
  {:fn          web-search
   :description "Search the web using DuckDuckGo. Returns a list of results with title, URL, and snippet."
   :schema      {:type       "object"
                 :properties {:query {:type "string" :description "The search query."}}
                 :required   [:query]}})
