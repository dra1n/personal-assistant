(ns pa.ui.selector-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.commands.registry :as registry]
            [pa.ui.selector :as selector]))

;; A fixed command set in an isolated registry so filtering is deterministic.
(use-fixtures :each
  (fn [f]
    (let [snap (registry/snapshot)]
      (registry/restore! {})
      (doseq [c ["clear" "help" "markdown" "memory"]]
        (registry/reg-command {:command c :description (str "desc " c)
                               :arg-spec {:kind :none} :->event (fn [_] {})}))
      (try (f) (finally (registry/restore! snap))))))

(defn- names [specs] (mapv :command specs))

;; ---------------------------------------------------------------------------
;; name-phase? / filter-text

(deftest name-phase-recognises-the-command-name-phase
  (testing "a leading slash with no whitespace is the name phase"
    (is (selector/name-phase? "/"))
    (is (selector/name-phase? "/mar"))
    (is (selector/name-phase? "/markdown")))
  (testing "non-slash, slash+space, and blank are not the name phase"
    (is (not (selector/name-phase? "hello")))
    (is (not (selector/name-phase? "/markdown on")))
    (is (not (selector/name-phase? "/mar kdown")))
    (is (not (selector/name-phase? "")))
    (is (not (selector/name-phase? nil)))))

(deftest filter-text-is-the-prefix-after-the-slash
  (is (= "" (selector/filter-text "/")))
  (is (= "mar" (selector/filter-text "/mar")))
  (is (nil? (selector/filter-text "/markdown on"))))

;; ---------------------------------------------------------------------------
;; matches (filtering)

(deftest matches-lists-all-on-bare-slash
  (is (= ["clear" "help" "markdown" "memory"] (names (selector/matches "/")))))

(deftest matches-filters-by-prefix
  (is (= ["markdown" "memory"] (names (selector/matches "/m"))))
  (is (= ["markdown"] (names (selector/matches "/mar"))))
  (is (= [] (names (selector/matches "/zzz")))))

;; ---------------------------------------------------------------------------
;; open? and dismissal

(deftest open-in-name-phase-unless-dismissed
  (is (selector/open? selector/initial "/mar"))
  (is (not (selector/open? selector/initial "markdown on")))
  (is (not (selector/open? (selector/dismiss selector/initial) "/mar"))
      "Esc dismisses while still in the name phase"))

(deftest dismissal-clears-on-leaving-name-phase
  (let [dismissed (selector/dismiss selector/initial)]
    (is (not (selector/open? dismissed "/mar")))
    (let [reset (selector/sync-state dismissed "hello")]
      (is (= selector/initial reset))
      (is (selector/open? (selector/sync-state reset "/mar") "/mar")
          "a fresh slash after leaving the name phase reopens"))))

;; ---------------------------------------------------------------------------
;; move (highlight) with wraparound

(deftest move-wraps-highlight-over-matches
  (let [buf "/m"                             ; matches: markdown, memory (n=2)
        s0  selector/initial
        s1  (selector/move s0 buf 1)
        s2  (selector/move s1 buf 1)
        sm1 (selector/move s0 buf -1)]
    (is (= "markdown" (:command (selector/highlighted s0 buf))))
    (is (= "memory"   (:command (selector/highlighted s1 buf))))
    (is (= "markdown" (:command (selector/highlighted s2 buf))) "wraps forward")
    (is (= "memory"   (:command (selector/highlighted sm1 buf))) "wraps backward")))

(deftest move-is-a-noop-with-no-matches
  (is (= selector/initial (selector/move selector/initial "/zzz" 1))))

;; ---------------------------------------------------------------------------
;; sync-state clamps the highlight when matches shrink

(deftest sync-clamps-highlight-when-matches-shrink
  (let [buf "/m"
        s   (selector/move selector/initial buf 1)]   ; index 1 (memory)
    (is (= 1 (:selector/index s)))
    (let [s' (selector/sync-state s "/mar")]          ; now only markdown (n=1)
      (is (= 0 (:selector/index s')) "index clamped into range"))))

(deftest highlighted-nil-when-closed
  (is (nil? (selector/highlighted (selector/dismiss selector/initial) "/mar")))
  (is (nil? (selector/highlighted selector/initial "not a command"))))
