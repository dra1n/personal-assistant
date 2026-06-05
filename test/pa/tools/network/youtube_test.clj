(ns pa.tools.network.youtube-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.http :as http]
            [pa.runtime.executor :as executor]
            [pa.tools.network.youtube :as youtube]
            [pa.tools.registry :as tools]))

;; ---------------------------------------------------------------------------
;; Fake HTTP client — returns responses in call order (POST player API, GET transcript)

(defn- queue-http [& responses]
  (let [q (atom responses)]
    (reify http/HttpClient
      (post  [_ _ _] (let [r (first @q)] (swap! q rest) r))
      (fetch [_ _ _] (let [r (first @q)] (swap! q rest) r)))))

(defn- ctx [& responses]
  {:http      (apply queue-http responses)
   :dispatch! (fn [_])})

;; ---------------------------------------------------------------------------
;; Fixture helpers

(def ^:private timedtext-url
  "https://www.youtube.com/api/timedtext?v=dQw4w9WgXcY&lang=en")

(def ^:private en-track
  {:baseUrl timedtext-url :languageCode "en" :name {:simpleText "English"}})

(def ^:private en-asr-track
  {:baseUrl timedtext-url :languageCode "en" :kind "asr"
   :name {:simpleText "English (auto-generated)"}})

(def ^:private es-track
  {:baseUrl "https://www.youtube.com/api/timedtext?v=dQw4w9WgXcY&lang=es"
   :languageCode "es"
   :name {:simpleText "Spanish"}})

(defn- player-response
  "Minimal /youtubei/v1/player response body with the given tracks."
  [tracks]
  {:status 200
   :body   (json/write-str {:captions {:playerCaptionsTracklistRenderer
                                       {:captionTracks tracks}}})})

(defn- xml-response [& texts]
  {:status 200
   :body   (str "<?xml version=\"1.0\" encoding=\"utf-8\" ?><transcript>"
                (str/join (map-indexed (fn [i t] (str "<text start=\"" i "\" dur=\"1\">" t "</text>")) texts))
                "</transcript>")})

(def ^:private transcript-ok
  (xml-response "Hello world" "This is a test."))

;; ---------------------------------------------------------------------------
;; Happy path — manual captions

(deftest returns-transcript-text
  (testing "returns plain text transcript"
    (let [{:keys [transcript]}
          (youtube/youtube-transcript {:url-or-id "dQw4w9WgXcY"}
                                      (ctx (player-response [en-track]) transcript-ok))]
      (is (re-find #"Hello world" transcript))
      (is (re-find #"This is a test" transcript)))))

(deftest manual-captions-kind-is-manual
  (testing "manual track produces :transcript/kind :manual"
    (let [{kind :transcript/kind}
          (youtube/youtube-transcript {:url-or-id "dQw4w9WgXcY"}
                                      (ctx (player-response [en-track]) transcript-ok))]
      (is (= :manual kind)))))

(deftest result-includes-video-id-and-lang
  (testing "result echoes :video-id and :lang"
    (let [result (youtube/youtube-transcript {:url-or-id "dQw4w9WgXcY"}
                                             (ctx (player-response [en-track]) transcript-ok))]
      (is (= "dQw4w9WgXcY" (:video-id result)))
      (is (= "en" (:lang result))))))

;; ---------------------------------------------------------------------------
;; Happy path — auto-generated captions

(deftest asr-captions-kind-is-auto
  (testing "asr track (kind \"asr\") produces :transcript/kind :auto"
    (let [{kind :transcript/kind}
          (youtube/youtube-transcript {:url-or-id "dQw4w9WgXcY"}
                                      (ctx (player-response [en-asr-track]) transcript-ok))]
      (is (= :auto kind)))))

;; ---------------------------------------------------------------------------
;; Language selection

(deftest selects-requested-language
  (testing "picks the track matching :lang when available"
    (let [result (youtube/youtube-transcript {:url-or-id "dQw4w9WgXcY" :lang "es"}
                                             (ctx (player-response [en-track es-track]) transcript-ok))]
      (is (= "es" (:lang result))))))

(deftest falls-back-to-first-track-when-lang-not-found
  (testing "falls back to first available track when :lang is absent"
    (let [result (youtube/youtube-transcript {:url-or-id "dQw4w9WgXcY" :lang "fr"}
                                             (ctx (player-response [en-track es-track]) transcript-ok))]
      (is (= "en" (:lang result))))))

;; ---------------------------------------------------------------------------
;; Video ID extraction — URL format variations

(deftest accepts-watch-url
  (testing "extracts ID from youtube.com/watch?v= URL"
    (let [{:keys [video-id]}
          (youtube/youtube-transcript {:url-or-id "https://www.youtube.com/watch?v=dQw4w9WgXcY"}
                                      (ctx (player-response [en-track]) transcript-ok))]
      (is (= "dQw4w9WgXcY" video-id)))))

(deftest accepts-short-url
  (testing "extracts ID from youtu.be/ URL"
    (let [{:keys [video-id]}
          (youtube/youtube-transcript {:url-or-id "https://youtu.be/dQw4w9WgXcY"}
                                      (ctx (player-response [en-track]) transcript-ok))]
      (is (= "dQw4w9WgXcY" video-id)))))

(deftest accepts-shorts-url
  (testing "extracts ID from youtube.com/shorts/ URL"
    (let [{:keys [video-id]}
          (youtube/youtube-transcript {:url-or-id "https://www.youtube.com/shorts/dQw4w9WgXcY"}
                                      (ctx (player-response [en-track]) transcript-ok))]
      (is (= "dQw4w9WgXcY" video-id)))))

(deftest accepts-bare-video-id
  (testing "accepts a bare 11-character video ID directly"
    (let [{:keys [video-id]}
          (youtube/youtube-transcript {:url-or-id "dQw4w9WgXcY"}
                                      (ctx (player-response [en-track]) transcript-ok))]
      (is (= "dQw4w9WgXcY" video-id)))))

;; ---------------------------------------------------------------------------
;; Error cases

(deftest no-captions-throws-no-transcript
  (testing "empty captionTracks throws :tool/no-transcript"
    (let [e (try (youtube/youtube-transcript {:url-or-id "dQw4w9WgXcY"}
                                             (ctx (player-response [])))
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e))
      (is (= :tool/no-transcript (:type (ex-data e)))))))

(deftest http-403-throws-restricted
  (testing "player API HTTP 403 throws :tool/restricted"
    (let [e (try (youtube/youtube-transcript {:url-or-id "dQw4w9WgXcY"}
                                             (ctx {:status 403 :body ""}))
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e))
      (is (= :tool/restricted (:type (ex-data e)))))))

(deftest http-error-throws-unavailable
  (testing "player API non-200/403 throws :tool/unavailable"
    (let [e (try (youtube/youtube-transcript {:url-or-id "dQw4w9WgXcY"}
                                             (ctx {:status 500 :body ""}))
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e))
      (is (= :tool/unavailable (:type (ex-data e)))))))

;; ---------------------------------------------------------------------------
;; Registry and executor integration

(def ^:private dispatched (atom []))

(use-fixtures :each
  (fn [f]
    (let [snap (tools/snapshot)]
      (reset! dispatched [])
      (try (f) (finally (tools/restore! snap))))))

(deftest registered-tool-is-in-registry
  (testing ":network/youtube-transcript is registered with correct shape"
    (let [spec (tools/get-tool :network/youtube-transcript)]
      (is (some? spec))
      (is (ifn? (:fn spec)))
      (is (= [:url-or-id] (get-in spec [:schema :required]))))))

(deftest schema-validation-rejects-missing-url-or-id
  (testing "missing :url-or-id emits :tool/invalid-args"
    (executor/execute-effect :tool/invoke
                             {:tool/name :network/youtube-transcript :tool/args {}}
                             {:dispatch! (fn [ev] (swap! dispatched conj ev))})
    (let [r (first @dispatched)]
      (is (= :error (:tool/status r)))
      (is (= :tool/invalid-args (get-in r [:tool/error :type]))))))

(deftest dry-run-skips-http
  (testing "dry-run returns :dry-run status without making HTTP calls"
    (let [no-http (reify http/HttpClient
                    (post  [_ _ _] (throw (AssertionError. "HTTP must not be called in dry-run")))
                    (fetch [_ _ _] (throw (AssertionError. "HTTP must not be called in dry-run"))))]
      (executor/execute-effect :tool/invoke
                               {:tool/name     :network/youtube-transcript
                                :tool/args     {:url-or-id "dQw4w9WgXcY"}
                                :tool/dry-run? true}
                               {:http no-http :dispatch! (fn [ev] (swap! dispatched conj ev))})
      (is (= :dry-run (:tool/status (first @dispatched)))))))
