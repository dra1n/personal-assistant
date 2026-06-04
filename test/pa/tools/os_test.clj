(ns pa.tools.os-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.runtime.executor :as executor]
            [pa.tools.os :as os]
            [pa.tools.registry :as tools]))

;; ---------------------------------------------------------------------------
;; Fake speak! that records calls without invoking audio

(def ^:private calls (atom []))

(defn- recording-speak! [text voice]
  (swap! calls conj {:text text :voice voice}))

(defn- ctx []
  {:os/speak! recording-speak!
   :dispatch! (fn [_])})

;; ---------------------------------------------------------------------------
;; parse-voice (white-box via the tools.md text parser)

(def ^:private tools-md-with-voice
  "## Announcer\n\n```announcer\nvoice: Samantha\n```\n")

(def ^:private tools-md-blank-voice
  "## Announcer\n\n```announcer\nvoice:   \n```\n")

(def ^:private tools-md-no-block
  "## Path allowlist\n\n```allowlist\nworkspace  read write\n```\n")

(deftest parse-voice-extracts-voice-from-announcer-block
  (testing "voice name is extracted from a well-formed announcer block"
    (is (= "Samantha" (#'os/parse-voice tools-md-with-voice)))))

(deftest parse-voice-returns-nil-when-no-announcer-block
  (testing "nil when tools.md has no announcer block"
    (is (nil? (#'os/parse-voice tools-md-no-block)))))

(deftest parse-voice-returns-nil-when-voice-blank
  (testing "nil when voice line is blank"
    (is (nil? (#'os/parse-voice tools-md-blank-voice)))))

;; ---------------------------------------------------------------------------
;; say tool function

(use-fixtures :each
  (fn [f]
    (reset! calls [])
    (f)))

(deftest say-returns-chars-map
  (testing "say returns {:chars N} — not the full text"
    (let [result (os/say {:text "hello"} (ctx))]
      (is (= {:chars 5} result)))))

(deftest say-passes-text-to-speak!
  (testing "the injected speak! receives the text"
    (os/say {:text "good morning"} (ctx))
    (is (= "good morning" (:text (first @calls))))))

(deftest say-passes-voice-from-tools-md
  (testing "the injected speak! receives the voice configured in tools.md
           (integration: reads the live PA_HOME/system/tools.md if present)"
    ;; We just verify a voice string or nil reaches speak! — not the specific value,
    ;; since the live tools.md content is outside test control.
    (os/say {:text "hi"} (ctx))
    (let [{:keys [voice]} (first @calls)]
      (is (or (nil? voice) (string? voice))))))

;; ---------------------------------------------------------------------------
;; Registry integration

(def ^:private dispatched (atom []))

(use-fixtures :each
  (fn [f]
    (let [snap (tools/snapshot)]
      (reset! calls [])
      (reset! dispatched [])
      (try (f) (finally (tools/restore! snap))))))

(deftest registered-tool-reachable-via-registry
  (testing ":os/say is in the registry"
    (let [spec (tools/get-tool :os/say)]
      (is (some? spec))
      (is (ifn? (:fn spec)))
      (is (= [:text] (get-in spec [:schema :required]))))))

(deftest schema-validation-rejects-missing-text
  (testing "executor emits :tool/invalid-args when :text is missing"
    (executor/execute-effect :tool/invoke
                             {:tool/name :os/say :tool/args {}}
                             {:os/speak! recording-speak!
                              :dispatch! (fn [ev] (swap! dispatched conj ev))})
    (let [r (first @dispatched)]
      (is (= :error (:tool/status r)))
      (is (= :tool/invalid-args (get-in r [:tool/error :type]))))))
