(ns pa.tools.network.fetch
  "Webpage retrieval tool (fetch-page).

  Fetches a URL, strips scripts/styles/markup via jsoup, and returns clean text.
  Runs every URL through the SSRF guard (pa.tools.network.ssrf) before any HTTP
  connection is opened, rejecting private or reserved IP addresses.

  The tool reads its HTTP client from ctx as :http. In production this is the
  HatoClient wired into the dispatcher's runtime context; tests inject a fake.
  Tests may also inject :ssrf-check (a fn of url-str → error-string-or-nil) to
  bypass DNS resolution."
  (:require [clojure.string :as str]
            [pa.http :as http]
            [pa.tools.network.ssrf :as ssrf]
            [pa.tools.registry :as registry])
  (:import [org.jsoup Jsoup]))

(def ^:private request-headers
  {"User-Agent" "Mozilla/5.0 (compatible; personal-assistant/1.0)"
   "Accept"     "text/html,application/xhtml+xml"})

(defn- http-of [ctx]
  (or (:http ctx)
      (throw (ex-info "no :http in ctx — is the HTTP client wired?"
                      {:type :tool/misconfigured}))))

(defn- extract-text
  "Parse html and return trimmed plain text with scripts and styles removed."
  [html]
  (let [doc (Jsoup/parse html)]
    (.remove (.select doc "script, style, noscript"))
    (str/trim (.text (.body doc)))))

(defn fetch-page
  "Fetch a webpage at :url and return its content.
  :format is \"text\" (default, strips markup) or \"raw\" (original HTML)."
  [{:keys [url format] :or {format "text"}} ctx]
  (let [ssrf-check (get ctx :ssrf-check ssrf/check-url)
        ssrf-err   (ssrf-check url)]
    (when ssrf-err
      (throw (ex-info ssrf-err {:type :tool/ssrf-blocked :url url}))))
  (let [resp   (http/fetch (http-of ctx) url
                           {:as      :string
                            :headers request-headers})
        status (:status resp)]
    (when-not (= 200 status)
      (throw (ex-info (str "HTTP " status) {:type :tool/http-error :status status})))
    {:url     url
     :format  format
     :content (if (= "raw" format)
                (:body resp)
                (extract-text (:body resp)))}))

;; ---------------------------------------------------------------------------
;; Registration

(registry/reg-tool :network/fetch-page
  {:fn          fetch-page
   :description "Fetch a webpage and return its text content (scripts and styles stripped). Pass :format \"raw\" to get the original HTML instead."
   :schema      {:type       "object"
                 :properties {:url    {:type "string" :description "The URL to fetch."}
                              :format {:type "string" :description "Output format: \"text\" (default) strips markup; \"raw\" returns original HTML."}}
                 :required   [:url]}})
