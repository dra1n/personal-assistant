(ns pa.tools.os
  "macOS OS-level tools.

  Currently exposes one tool:
    :os/say — speak text aloud using the macOS `say` command.

  The voice is read at call time from the `announcer` config block in
  <PA_HOME>/system/tools.md. The subprocess launcher is injectable via
  :os/speak! in ctx so tests can record calls without invoking audio."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pa.storage.fs :as fs]
            [pa.tools.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Announcer config

(defn- parse-voice
  "Extract the `voice:` value from a tools.md text that contains an
  ```announcer block. Returns nil if absent or blank."
  [text]
  (let [lines (str/split-lines text)
        after (->> lines
                   (drop-while #(not (re-matches #"\s*```announcer\s*" %)))
                   rest)
        block (take-while #(not (re-matches #"\s*```\s*" %)) after)]
    (some (fn [line]
            (when-let [[_ v] (re-matches #"\s*voice:\s*(.+)" line)]
              (let [trimmed (str/trim v)]
                (when (seq trimmed) trimmed))))
          block)))

(defn- read-voice
  "Read the configured voice from the live tools.md, or nil for system default."
  []
  (let [f (io/file (fs/pa-home) "system" "tools.md")]
    (when (.exists f)
      (parse-voice (slurp f)))))

;; ---------------------------------------------------------------------------
;; Subprocess launcher

(defn- default-speak!
  "Start `say [-v voice]`, write text to its stdin on a background thread,
  and return immediately. Using stdin avoids argv size limits for long text."
  [text voice]
  (let [cmd (cond-> ["say"] (seq voice) (into ["-v" voice]))
        p   (-> (ProcessBuilder. ^java.util.List cmd) .start)]
    (future
      (with-open [w (io/writer (.getOutputStream p))]
        (.write w ^String text)))
    p))

;; ---------------------------------------------------------------------------
;; Tool

(defn say
  "Speak :text aloud using the macOS say command.
  Uses the voice configured in tools.md; falls back to the system default.
  Returns {:chars N} immediately — speech plays asynchronously."
  [{:keys [text]} ctx]
  (let [speak! (get ctx :os/speak! default-speak!)
        voice  (read-voice)]
    (speak! text voice))
  {:chars (count text)})

;; ---------------------------------------------------------------------------
;; Registration

(registry/reg-tool :os/say
  {:fn          say
   :description "Speak text aloud using the macOS say command. Plays asynchronously; returns {:chars N} (character count sent) immediately."
   :schema      {:type       "object"
                 :properties {:text {:type "string" :description "The text to speak."}}
                 :required   [:text]}})
