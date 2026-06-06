(ns pa.tools.network.youtube
  "YouTube transcript tool.

  Mechanism (mirrors youtube-transcript-api):
    1. POST to YouTube's internal player API (youtubei/v1/player) with the ANDROID
       client — returns captionTracks as clean JSON without session-scoped tokens.
    2. Strip &fmt=srv3 from the track's baseUrl (gives the plain XML format).
    3. GET the transcript XML; parse <text> elements.

  The player API and timedtext endpoints are undocumented; acceptable for a
  personal local-first tool.

  Track kinds:
    kind absent or \"\"  → :manual  (human-created captions)
    kind \"asr\"         → :auto    (Automatic Speech Recognition)

  Error types (thrown as ex-info, surfaced by the :tool/invoke executor):
    :tool/no-transcript  — video exists but has no caption tracks
    :tool/unavailable    — network failure, HTTP error, or parse failure
    :tool/restricted     — HTTP 403 or bot-detection from the player API"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [pa.http :as http]
            [pa.tools.registry :as registry])
)

;; ---------------------------------------------------------------------------
;; Helpers

(defn- http-of [ctx]
  (or (:http ctx)
      (throw (ex-info "no :http in ctx — is the HTTP client wired?"
                      {:type :tool/misconfigured}))))

(def ^:private base-headers
  {"User-Agent"      "Mozilla/5.0 (compatible; personal-assistant/1.0)"
   "Accept-Language" "en-US"})

;; ---------------------------------------------------------------------------
;; Video ID extraction

(defn- extract-video-id [url-or-id]
  (cond
    (re-matches #"[a-zA-Z0-9_-]{11}" url-or-id)
    url-or-id

    (re-find #"youtu\.be/([a-zA-Z0-9_-]{11})" url-or-id)
    (second (re-find #"youtu\.be/([a-zA-Z0-9_-]{11})" url-or-id))

    (re-find #"youtube\.com/(?:shorts|embed)/([a-zA-Z0-9_-]{11})" url-or-id)
    (second (re-find #"youtube\.com/(?:shorts|embed)/([a-zA-Z0-9_-]{11})" url-or-id))

    (re-find #"[?&]v=([a-zA-Z0-9_-]{11})" url-or-id)
    (second (re-find #"[?&]v=([a-zA-Z0-9_-]{11})" url-or-id))

    :else nil))

;; ---------------------------------------------------------------------------
;; Caption track fetch via the YouTube player API (ANDROID client)

;; Public API key used by the YouTube web client — stable but unofficial.
(def ^:private innertube-key "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8")
(def ^:private player-api-url
  (str "https://www.youtube.com/youtubei/v1/player?key=" innertube-key))

(defn- fetch-caption-tracks [http video-id]
  (let [body (json/write-str {:videoId video-id
                              :context {:client {:clientName    "ANDROID"
                                                 :clientVersion "20.10.38"}}})
        resp (http/post http player-api-url
                        {:as      :string
                         :body    body
                         :headers (assoc base-headers "Content-Type" "application/json")})]
    (when (= 403 (:status resp))
      (throw (ex-info "Access restricted (HTTP 403)"
                      {:type :tool/restricted :video-id video-id})))
    (when (not= 200 (:status resp))
      (throw (ex-info (str "Player API HTTP " (:status resp))
                      {:type :tool/unavailable :video-id video-id})))
    (-> (json/read-str (:body resp) :key-fn keyword)
        (get-in [:captions :playerCaptionsTracklistRenderer :captionTracks]))))

;; ---------------------------------------------------------------------------
;; Track selection

(defn- select-track [tracks lang]
  (or (first (filter #(= lang (:languageCode %)) tracks))
      (first tracks)))

(defn- track-kind [track]
  (if (= "asr" (:kind track)) :auto :manual))

;; ---------------------------------------------------------------------------
;; Transcript parsing
;;
;; Strip &fmt=srv3 from the baseUrl to get the default <text>-based XML format.
;; Entities like &amp; and &#39; are decoded by Jsoup's static unescaper.

(defn- caption-url [track]
  (str/replace (:baseUrl track) #"&fmt=srv3" ""))

(defn- strip-tags [s]
  (str/replace s #"<[^>]+>" ""))

(defn- decode-entities [s]
  (-> s
      (str/replace "&amp;"  "&")
      (str/replace "&lt;"   "<")
      (str/replace "&gt;"   ">")
      (str/replace "&quot;" "\"")
      (str/replace "&apos;" "'")
      (str/replace #"&#(\d+);"           #(str (char (Integer/parseInt (second %)))))
      (str/replace #"&#x([0-9a-fA-F]+);" #(str (char (Integer/parseInt (second %) 16))))))

(defn- parse-transcript-xml [body]
  (->> (re-seq #"(?s)<text[^>]*>(.*?)</text>" body)
       (map second)
       (map strip-tags)
       (map decode-entities)
       (map str/trim)
       (remove str/blank?)
       (str/join " ")
       str/trim))

;; ---------------------------------------------------------------------------
;; Tool function

(defn youtube-transcript
  "Fetch the transcript for a YouTube video.
  :url-or-id — a YouTube URL or bare 11-char video ID (required)
  :lang      — BCP 47 language code (optional, default \"en\"); falls back to
               first available track if the requested language is not found"
  [{:keys [url-or-id lang] :or {lang "en"}} ctx]
  (let [video-id (extract-video-id url-or-id)]
    (when-not video-id
      (throw (ex-info (str "Cannot parse video ID from: " url-or-id)
                      {:type :tool/unavailable :input url-or-id})))
    (let [http   (http-of ctx)
          tracks (fetch-caption-tracks http video-id)]
      (when (empty? tracks)
        (throw (ex-info "No captions available for this video"
                        {:type :tool/no-transcript :video-id video-id})))
      (let [track    (select-track tracks lang)
            tid-url  (caption-url track)
            tid-resp (http/fetch http tid-url
                                 {:as      :string
                                  :headers (assoc base-headers
                                                  "Referer"
                                                  (str "https://www.youtube.com/watch?v=" video-id))})]
        (when-not (= 200 (:status tid-resp))
          (throw (ex-info (str "Caption fetch failed with HTTP " (:status tid-resp))
                          {:type :tool/unavailable :video-id video-id})))
        {:video-id        video-id
         :lang            (:languageCode track)
         :transcript/kind (track-kind track)
         :transcript      (parse-transcript-xml (:body tid-resp))}))))

;; ---------------------------------------------------------------------------
;; Registration

(registry/reg-tool :network/youtube-transcript
  {:fn          youtube-transcript
   :description "Fetch the transcript of a YouTube video as plain text. Pass a YouTube URL or bare video ID. Use :lang (e.g. \"en\", \"es\") to request a specific language; falls back to the first available track if not found."
   :schema      {:type       "object"
                 :properties {:url-or-id {:type "string" :description "YouTube URL or bare 11-character video ID."}
                              :lang      {:type "string" :description "BCP 47 language code (e.g. \"en\", \"es\", \"fr\"). Defaults to \"en\"; falls back to first available if not found."}}
                 :required   [:url-or-id]}})
