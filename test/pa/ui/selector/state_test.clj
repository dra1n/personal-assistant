(ns pa.ui.selector.state-test
  "The selector state machine, exercised through the slash-command source.
  Every assertion here predates the @-mention work: the machine was
  generalized over a source, and this file is the proof the command overlay
  behaves exactly as it did. Mention-specific behaviour lives in
  pa.ui.selector.sources-test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.commands.registry :as registry]
            [pa.ui.selector.sources :as sources]
            [pa.ui.selector.state :as selector]))

;; A fixed command set in an isolated registry so filtering is deterministic.
(use-fixtures :each
  (fn [f]
    (let [snap (registry/snapshot)]
      (registry/restore! {})
      (doseq [c ["clear" "help" "markdown" "memory"]]
        (registry/reg-command {:command c :description (str "desc " c)
                               :arg-spec {:kind :none} :->event (fn [_] {})}))
      (try (f) (finally (registry/restore! snap))))))

(defn- src [] (sources/command-source))
(defn- names [rows] (mapv (comp :command :value) rows))
(defn- in-phase? [buffer] (boolean (selector/token (src) buffer)))

;; ---------------------------------------------------------------------------
;; the name phase / filter-text

(deftest name-phase-recognises-the-command-name-phase
  (testing "a leading slash with no whitespace is the name phase"
    (is (in-phase? "/"))
    (is (in-phase? "/mar"))
    (is (in-phase? "/markdown")))
  (testing "non-slash, slash+space, and blank are not the name phase"
    (is (not (in-phase? "hello")))
    (is (not (in-phase? "/markdown on")))
    (is (not (in-phase? "/mar kdown")))
    (is (not (in-phase? "")))
    (is (not (in-phase? nil)))))

(deftest a-slash-mid-line-is-not-a-command
  (testing "commands are anchored: only the start of the buffer opens the overlay"
    (is (not (in-phase? "see docs/readme")))
    (is (not (in-phase? "hello /help")))))

(deftest filter-text-is-the-prefix-after-the-slash
  (is (= "" (selector/filter-text (src) "/")))
  (is (= "mar" (selector/filter-text (src) "/mar")))
  (is (nil? (selector/filter-text (src) "/markdown on"))))

;; ---------------------------------------------------------------------------
;; matches (filtering)

(deftest matches-lists-all-on-bare-slash
  (is (= ["clear" "help" "markdown" "memory"] (names (selector/matches (src) "/")))))

(deftest matches-filters-by-prefix
  (is (= ["markdown" "memory"] (names (selector/matches (src) "/m"))))
  (is (= ["markdown"] (names (selector/matches (src) "/mar"))))
  (is (= [] (names (selector/matches (src) "/zzz")))))

(deftest rows-carry-what-the-overlay-renders
  (let [row (first (selector/matches (src) "/help"))]
    (is (= "/help" (:label row)))
    (is (= "desc help" (:help row)))
    (is (= :command (:kind row)))))

;; ---------------------------------------------------------------------------
;; open? and dismissal

(deftest open-in-name-phase-unless-dismissed
  (is (selector/open? (src) selector/initial "/mar"))
  (is (not (selector/open? (src) selector/initial "markdown on")))
  (is (not (selector/open? (src) (selector/dismiss selector/initial) "/mar"))
      "Esc dismisses while still in the name phase"))

(deftest dismissal-clears-on-leaving-name-phase
  (let [dismissed (selector/dismiss selector/initial)]
    (is (not (selector/open? (src) dismissed "/mar")))
    (let [reset (selector/sync-state (src) dismissed "hello")]
      (is (= selector/initial reset))
      (is (selector/open? (src) (selector/sync-state (src) reset "/mar") "/mar")
          "a fresh slash after leaving the name phase reopens"))))

;; ---------------------------------------------------------------------------
;; move (highlight) with wraparound

(deftest move-wraps-highlight-over-matches
  (let [buf "/m"                             ; matches: markdown, memory (n=2)
        s0  selector/initial
        s1  (selector/move (src) s0 buf 1)
        s2  (selector/move (src) s1 buf 1)
        sm1 (selector/move (src) s0 buf -1)
        cmd #(:command (:value (selector/highlighted (src) % buf)))]
    (is (= "markdown" (cmd s0)))
    (is (= "memory"   (cmd s1)))
    (is (= "markdown" (cmd s2)) "wraps forward")
    (is (= "memory"   (cmd sm1)) "wraps backward")))

(deftest move-is-a-noop-with-no-matches
  (is (= selector/initial (selector/move (src) selector/initial "/zzz" 1))))

;; ---------------------------------------------------------------------------
;; sync-state clamps the highlight when matches shrink

(deftest sync-clamps-highlight-when-matches-shrink
  (let [buf "/m"
        s   (selector/move (src) selector/initial buf 1)]   ; index 1 (memory)
    (is (= 1 (:selector/index s)))
    (let [s' (selector/sync-state (src) s "/mar")]          ; now only markdown (n=1)
      (is (= 0 (:selector/index s')) "index clamped into range"))))

(deftest sync-with-no-active-source-resets
  (is (= selector/initial (selector/sync-state nil {:selector/index 3} "/mar"))))

(deftest highlighted-nil-when-closed
  (is (nil? (selector/highlighted (src) (selector/dismiss selector/initial) "/mar")))
  (is (nil? (selector/highlighted (src) selector/initial "not a command"))))
