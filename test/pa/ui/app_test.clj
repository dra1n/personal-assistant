(ns pa.ui.app-test
  (:require [charm.components.viewport :as vp]
            [charm.message :as msg]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pa.ui.app :as app]
            [pa.ui.view :as view]))

(defn- model-with-turns
  "An initialised model sized to a terminal with `n` turns of content, so the
  conversation overflows the viewport."
  [n]
  (let [[m0 _] ((app/init {:db-ch nil :watch-cmd nil :dispatch! identity}))
        [m1 _] (app/update-model m0 {:type :window-size :width 40 :height 30})
        turns  (vec (for [i (range n) role [:user :assistant]]
                      {:role role :content (str (name role) " " i)}))
        [m2 _] (app/update-model m1 {:type :runtime/db-updated :db {:conversation turns}})]
    m2))

(deftest typing-appends-runes-to-input
  (testing "printable characters accumulate in the input buffer"
    (let [[m1 _] (app/update-model {:input ""} (msg/key-press "h"))
          [m2 _] (app/update-model m1 (msg/key-press "i"))]
      (is (= "hi" (:input m2))))))

(deftest space-key-appends-a-space
  (testing "space is a special key, not a runes string"
    (let [[m _] (app/update-model {:input "a"} (msg/key-press :space))]
      (is (= "a " (:input m))))))

(deftest backspace-removes-last-char
  (let [[m _] (app/update-model {:input "abc"} (msg/key-press :backspace))]
    (is (= "ab" (:input m))))
  (testing "backspace on empty buffer is a no-op"
    (let [[m _] (app/update-model {:input ""} (msg/key-press :backspace))]
      (is (= "" (:input m))))))

(deftest modified-chords-do-not-append
  (testing "ctrl/alt chords are not treated as text input"
    (let [[m cmd] (app/update-model {:input "x"} (msg/key-press "c" :ctrl true))]
      (is (= "x" (:input m)) "ctrl+c does not append")
      (is (some? cmd) "ctrl+c yields the quit command"))
    (let [[m _] (app/update-model {:input "x"} (msg/key-press "a" :alt true))]
      (is (= "x" (:input m)) "alt+a does not append"))))

(deftest enter-dispatches-user-message-and-clears-buffer
  (testing "Enter dispatches a trimmed :user/message and clears the input"
    (let [events  (atom [])
          model   {:input "  hello world  " :dispatch! #(swap! events conj %)}
          [m cmd] (app/update-model model (msg/key-press :enter))]
      (is (= "" (:input m)) "buffer cleared")
      (is (some? cmd) "a dispatch command is returned")
      ((:fn cmd))  ; run the command's side effect
      (is (= [{:event/type :user/message :content "hello world"}] @events)))))

(deftest enter-on-blank-input-is-a-no-op
  (testing "blank/whitespace input is neither dispatched nor cleared oddly"
    (let [events  (atom [])
          [m cmd] (app/update-model {:input "   " :dispatch! #(swap! events conj %)}
                                    (msg/key-press :enter))]
      (is (nil? cmd))
      (is (empty? @events))
      (is (= "   " (:input m))))))

(deftest db-update-preserves-input-buffer
  (testing "an incoming runtime snapshot does not clobber in-progress typing"
    (let [model  {:input "half-typed" :db-ch nil :db {}}
          [m _]  (app/update-model model {:type :runtime/db-updated :db {:conversation [:x]}})]
      (is (= "half-typed" (:input m)))
      (is (= {:conversation [:x]} (:db m))))))

;; ---------------------------------------------------------------------------
;; Streaming
;; ---------------------------------------------------------------------------

(deftest llm-delta-grows-the-streaming-buffer
  (testing ":llm/delta accumulates into :streaming while the stream is open"
    (let [m0     {:streaming "" :streaming-open? true :delta-ch nil :db {} :width 40}
          [m1 c] (app/update-model m0 {:type :llm/delta :delta "Hel"})
          [m2 _] (app/update-model m1 {:type :llm/delta :delta "lo"})]
      (is (= "Hello" (:streaming m2)))
      (is (some? c) "watch-delta-cmd rescheduled"))))

(deftest db-update-clears-the-streaming-buffer
  (testing "a committed snapshot clears the in-progress stream"
    (let [[m _] (app/update-model
                 {:streaming "partial reply" :streaming-open? true :db-ch nil :db {} :width 40}
                 {:type :runtime/db-updated
                  :db {:conversation [{:role :assistant :content "partial reply"}]}})]
      (is (= "" (:streaming m))))))

(deftest deltas-after-commit-do-not-resurrect-a-ghost-turn
  (testing "a straggler delta arriving after the assistant turn commits is dropped"
    (let [open   {:streaming "" :streaming-open? true :db {} :width 40 :delta-ch nil :db-ch nil}
          [m1 _] (app/update-model open {:type :llm/delta :delta "Hello there"})
          [m2 _] (app/update-model m1 {:type :runtime/db-updated
                                       :db {:conversation [{:role :user :content "hi"}
                                                           {:role :assistant :content "Hello there"}]}})
          [m3 _] (app/update-model m2 {:type :llm/delta :delta " there"})]
      (is (= "Hello there" (:streaming m1)) "deltas accumulate while open")
      (is (= "" (:streaming m2)) "the commit clears the buffer")
      (is (false? (:streaming-open? m2)) "the commit closes the stream")
      (is (= "" (:streaming m3)) "the straggler does not re-grow a ghost turn"))))

(deftest tool-call-turn-keeps-the-stream-open
  (testing "an assistant tool-call turn is an intermediate hop, not a commit"
    (let [open   {:streaming "commentary" :streaming-open? true :db {} :width 40
                  :delta-ch nil :db-ch nil}
          [m1 _] (app/update-model open
                                   {:type :runtime/db-updated
                                    :db {:conversation
                                         [{:role :user :content "hi"}
                                          {:role :assistant :content ""
                                           :tool-calls [{:id "c1" :name :fs/read-file
                                                         :arguments {:path "a.txt"}}]}]}})
          [m2 _] (app/update-model m1 {:type :llm/delta :delta "Done!"})]
      (is (true? (:streaming-open? m1)) "the stream stays open through the hop")
      (is (= "" (:streaming m1)) "the preview clears — its text committed with the turn")
      (is (= "Done!" (:streaming m2)) "the follow-up response still streams live"))))

(deftest non-conversation-db-update-preserves-stream-preview
  (testing "a snapshot that didn't touch the conversation keeps the live preview"
    (let [conv  [{:role :user :content "hi"}]
          m0    {:streaming "partial reply" :streaming-open? true
                 :db {:conversation conv} :db-ch nil :width 40}
          [m _] (app/update-model m0
                                  {:type :runtime/db-updated
                                   :db {:conversation conv
                                        :ui/notifications [{:id "n1" :type :reminder
                                                            :payload {:text "stretch"}}]}})]
      (is (= "partial reply" (:streaming m))
          "a reminder firing mid-stream does not wipe the preview"))))

(deftest user-turn-commit-keeps-the-stream-open
  (testing "committing the user turn leaves the stream open for the assistant's deltas"
    (let [open   {:streaming "" :streaming-open? true :db {} :width 40 :delta-ch nil :db-ch nil}
          [m1 _] (app/update-model open {:type :runtime/db-updated
                                         :db {:conversation [{:role :user :content "hi"}]}})
          [m2 _] (app/update-model m1 {:type :llm/delta :delta "Hi!"})]
      (is (true? (:streaming-open? m1)) "a user-turn commit does not close the stream")
      (is (= "Hi!" (:streaming m2)) "subsequent deltas are still accepted"))))

;; ---------------------------------------------------------------------------
;; Conversation viewport
;; ---------------------------------------------------------------------------

(deftest new-turns-pin-conversation-to-bottom
  (testing "after a db update the viewport shows the latest turns"
    (is (vp/viewport-at-bottom? (:viewport (model-with-turns 20))))))

(deftest frame-fills-the-terminal-height
  (testing "the rendered frame is always exactly the terminal height (fluid layout)"
    ;; model-with-turns sizes the terminal to 40×30.
    (doseq [[label n] [["empty" 0] ["sparse" 1] ["overflowing" 30]]]
      (let [m (model-with-turns n)]
        (is (= 30 (count (str/split-lines (view/view m))))
            (str label " conversation fills the terminal height, input pinned"))))))

(deftest typing-does-not-scroll-the-viewport
  (testing "j/k go to the input buffer, not the viewport (no keymap conflict)"
    (let [m      (model-with-turns 20)
          [m' _] (app/update-model m (msg/key-press "j"))]
      (is (= "j" (:input m')) "j is typed, not consumed as a scroll key")
      (is (= (get-in m [:viewport :y-offset]) (get-in m' [:viewport :y-offset]))
          "viewport offset unchanged by typing"))))

;; ---------------------------------------------------------------------------
;; Log panel
;; ---------------------------------------------------------------------------

(deftest log-appended-buffers-entries
  (testing ":log/appended conj's the entry and reschedules the watch command"
    (let [[m cmd] (app/update-model {:logs [] :log-ch nil}
                                    {:type :log/appended
                                     :entry {:level :info :msg "hello"}})]
      (is (= [{:level :info :msg "hello"}] (:logs m)))
      (is (some? cmd) "watch-log-cmd rescheduled"))))

(deftest log-buffer-is-bounded
  (testing "the ring buffer keeps only the most recent entries"
    (let [m (reduce (fn [model i]
                      (first (app/update-model model
                                               {:type :log/appended
                                                :entry {:level :info :msg (str "m" i)}})))
                    {:logs [] :log-ch nil}
                    (range 250))]
      (is (= 200 (count (:logs m))) "capped at log-buffer-size")
      (is (= "m249" (:msg (last (:logs m)))) "newest retained")
      (is (= "m50" (:msg (first (:logs m)))) "oldest dropped"))))

(deftest ctrl-l-toggles-log-panel-and-focus
  (testing "Ctrl+L flips the panel, resizes the conversation, and moves focus"
    (let [m0       (model-with-turns 20)
          h0       (get-in m0 [:viewport :height])
          [m1 _]   (app/update-model m0 (msg/key-press "l" :ctrl true))
          [m2 _]   (app/update-model m1 (msg/key-press "l" :ctrl true))]
      (is (true? (:logs-open? m1)))
      (is (= :logs (:focus m1)) "opening focuses the log panel")
      (is (< (get-in m1 [:viewport :height]) h0) "expanded panel shrinks the conversation")
      (is (false? (:logs-open? m2)))
      (is (= :input (:focus m2)) "closing returns focus to the input")
      (is (= h0 (get-in m2 [:viewport :height])) "collapsing restores the height"))))

(deftest tab-cycles-focus-across-regions
  (testing "while collapsed Tab cycles input ↔ conversation"
    (let [m0     (model-with-turns 20)
          [m1 _] (app/update-model m0 (msg/key-press :tab))
          [m2 _] (app/update-model m1 (msg/key-press :tab))]
      (is (= :conversation (:focus m1)) "Tab moves from input to conversation")
      (is (= :input (:focus m2)) "Tab wraps back to input")))
  (testing "while expanded Tab cycles input → conversation → logs → input"
    (let [open   (first (app/update-model (model-with-turns 20) (msg/key-press "l" :ctrl true)))]
      (is (= :logs (:focus open)) "precondition: opening focuses logs")
      (let [[m1 _] (app/update-model open (msg/key-press :tab))
            [m2 _] (app/update-model m1 (msg/key-press :tab))
            [m3 _] (app/update-model m2 (msg/key-press :tab))]
        (is (= :input (:focus m1)) "logs → input")
        (is (= :conversation (:focus m2)) "input → conversation")
        (is (= :logs (:focus m3)) "conversation → logs")))))

(deftest tab-skips-the-empty-conversation
  (testing "while empty the conversation is not a focus target"
    (let [empty-model (first (app/update-model (model-with-turns 0)
                                               {:type :runtime/db-updated :db {:conversation []}}))]
      (is (true? (view/conversation-empty? empty-model)) "precondition: empty")
      (testing "logs closed: Tab stays on input (only focusable region)"
        (is (= :input (:focus (first (app/update-model empty-model (msg/key-press :tab)))))))
      (testing "logs open: Tab cycles input ↔ logs, skipping the empty conversation"
        (let [open   (first (app/update-model empty-model (msg/key-press "l" :ctrl true)))
              [m1 _] (app/update-model open (msg/key-press :tab))
              [m2 _] (app/update-model m1 (msg/key-press :tab))]
          (is (= :input (:focus m1)) "logs → input")
          (is (= :logs (:focus m2)) "input → logs, conversation skipped"))))))

(deftest escape-returns-focus-to-the-input
  (testing "Esc snaps focus back to the input from any region"
    (let [conv (first (app/update-model (model-with-turns 5) (msg/key-press :tab)))]
      (is (= :conversation (:focus conv)) "precondition: conversation focused")
      (is (= :input (:focus (first (app/update-model conv (msg/key-press :escape)))))))
    (let [logs (first (app/update-model (model-with-turns 5) (msg/key-press "l" :ctrl true)))]
      (is (= :logs (:focus logs)) "precondition: logs focused")
      (is (= :input (:focus (first (app/update-model logs (msg/key-press :escape)))))))))

(deftest typing-returns-focus-to-the-input
  (testing "interacting with the input from another region snaps focus back"
    (doseq [[region focus-model]
            [["logs"         (first (app/update-model (model-with-turns 5) (msg/key-press "l" :ctrl true)))]
             ["conversation" (first (app/update-model (model-with-turns 5) (msg/key-press :tab)))]]]
      (doseq [[label key] [["a printable char" (msg/key-press "x")]
                           ["space"            (msg/key-press :space)]
                           ["backspace"        (msg/key-press :backspace)]
                           ["enter"            (msg/key-press :enter)]]]
        (let [[m _] (app/update-model focus-model key)]
          (is (= :input (:focus m)) (str label " from " region " returns focus to the input")))))))

(deftest arrow-keys-scroll-only-the-focused-region
  (let [base (reduce (fn [model i]
                       (first (app/update-model model
                                                {:type :log/appended
                                                 :entry {:level :debug :msg (str "line " i)}})))
                     (model-with-turns 20) (range 40))]
    (testing "input focused: arrows scroll nothing (the conversation tails live)"
      (let [[m _] (app/update-model base (msg/key-press :up))]
        (is (= :input (:focus base)) "precondition: input focused")
        (is (= (get-in base [:viewport :y-offset]) (get-in m [:viewport :y-offset]))
            "conversation offset unchanged while typing-focused")))
    (testing "conversation focused: Up scrolls the conversation up one line"
      (let [conv      (first (app/update-model base (msg/key-press :tab)))   ; focus :conversation
            [m _]     (app/update-model conv (msg/key-press :up))]
        (is (= :conversation (:focus conv)))
        (is (= (dec (get-in conv [:viewport :y-offset])) (get-in m [:viewport :y-offset])))))
    (testing "logs focused: Up scrolls the log viewport, not the conversation"
      (let [open  (first (app/update-model base (msg/key-press "l" :ctrl true)))   ; focus :logs
            [m _] (app/update-model open (msg/key-press :up))]
        (is (= :logs (:focus open)))
        (is (= (dec (get-in open [:log-viewport :y-offset])) (get-in m [:log-viewport :y-offset]))
            "log viewport scrolls up one line")
        (is (= (get-in open [:viewport :y-offset]) (get-in m [:viewport :y-offset]))
            "the unfocused conversation viewport is untouched")))))

(defn- model-with-history
  "A minimal model with a history vector in :db."
  [& texts]
  {:input "" :nav/index nil :nav/draft ""
   :focus :input
   :db {:ui/history (mapv #(hash-map :history/text %) texts)}})

(deftest up-arrow-in-input-focus-navigates-history
  (testing "↑ while input focused steps back through history"
    (let [model       (model-with-history "git status" "git diff")
          [m1 _]      (app/update-model model (msg/key-press :up))
          [m2 _]      (app/update-model m1 (msg/key-press :up))]
      (is (= "git diff" (:input m1)) "first ↑ shows most recent entry")
      (is (= "git status" (:input m2)) "second ↑ steps to older entry"))))

(deftest down-arrow-in-input-focus-restores-draft
  (testing "↓ after navigating back eventually restores the original draft"
    (let [model  (assoc (model-with-history "git status") :input "my draft")
          [m1 _] (app/update-model model (msg/key-press :up))
          [m2 _] (app/update-model m1 (msg/key-press :down))]
      (is (= "git status" (:input m1)) "↑ shows history entry")
      (is (= "my draft" (:input m2)) "↓ restores draft"))))

(deftest typing-while-navigating-exits-and-appends
  (testing "printable char during history navigation exits navigation and appends to the displayed entry"
    (let [model  (assoc (model-with-history "git status") :input "gi")
          [m1 _] (app/update-model model (msg/key-press :up))
          [m2 _] (app/update-model m1 (msg/key-press "!"))]
      (is (= "git status" (:input m1)) "navigating — shows history")
      (is (= "git status!" (:input m2)) "exited navigation, history entry + char")
      (is (nil? (:nav/index m2)) "nav index cleared"))))

(deftest enter-resets-navigation-state
  (testing "submitting resets nav/index and nav/draft"
    (let [events (atom [])
          model  (assoc (model-with-history "git status")
                        :input "hello" :dispatch! #(swap! events conj %))
          [m1 _] (app/update-model model (msg/key-press :up))
          [m2 _] (app/update-model (assoc m1 :input "send me") (msg/key-press :enter))]
      (is (some? (:nav/index m1)) "precondition: navigating")
      (is (nil? (:nav/index m2)) "nav index cleared after Enter")
      (is (= "" (:nav/draft m2)) "nav draft cleared after Enter"))))

(deftest focused-conversation-holds-position-against-new-turns
  (testing "scrolled up + focused, a new committed turn does not yank to the bottom"
    (let [conv     (first (app/update-model (model-with-turns 20) (msg/key-press :tab)))
          scrolled (first (app/update-model conv (msg/key-press :up)))
          off      (get-in scrolled [:viewport :y-offset])
          turns    (vec (for [i (range 21) role [:user :assistant]]
                          {:role role :content (str (name role) " " i)}))
          [m _]    (app/update-model scrolled {:type :runtime/db-updated :db {:conversation turns}})]
      (is (= :conversation (:focus scrolled)) "precondition: conversation focused & scrolled up")
      (is (false? (vp/viewport-at-bottom? (:viewport scrolled))))
      (is (= off (get-in m [:viewport :y-offset])) "position held, not pinned to bottom"))))

;; ---------------------------------------------------------------------------
;; Notifications
;; ---------------------------------------------------------------------------

(deftest ctrl-x-dismisses-pending-notifications
  (testing "^X dispatches :notifications/clear when the banner is showing"
    (let [events  (atom [])
          model   {:db {:ui/notifications [{:id "n1" :type :reminder :payload {:text "x"}}]}
                   :dispatch! #(swap! events conj %)}
          [m cmd] (app/update-model model (msg/key-press "x" :ctrl true))]
      (is (= model m) "the model itself is untouched — clearing flows back via the db subscription")
      (is (some? cmd))
      ((:fn cmd))
      (is (= [{:event/type :notifications/clear}] @events))))
  (testing "^X with nothing pending is a no-op"
    (let [[_ cmd] (app/update-model {:db {}} (msg/key-press "x" :ctrl true))]
      (is (nil? cmd)))))

;; ---------------------------------------------------------------------------
;; Multiline input — paste, Alt+Enter, submit, regressions
;; ---------------------------------------------------------------------------

(deftest paste-accumulates-content-with-newlines
  (testing "characters and :enter events between :paste-start and :paste-end build up the buffer"
    (let [events  (atom [])
          m0      {:input "" :pasting? false :dispatch! #(swap! events conj %)}
          [m1 _]  (app/update-model m0 (msg/key-press :paste-start))
          [m2 _]  (app/update-model m1 (msg/key-press "H"))
          [m3 _]  (app/update-model m2 (msg/key-press "i"))
          [m4 _]  (app/update-model m3 (msg/key-press :enter))   ; \n arriving as :enter in paste
          [m5 _]  (app/update-model m4 (msg/key-press "w"))
          [m6 _]  (app/update-model m5 (msg/key-press :paste-end))]
      (is (true?  (:pasting? m1))  ":paste-start sets pasting? flag")
      (is (false? (:pasting? m6))  ":paste-end clears pasting? flag")
      (is (= "Hi\nw" (:input m6))  "multiline content accumulated verbatim")
      (is (empty? @events)         "no :user/message dispatched during paste"))))

(deftest paste-suppresses-tab-focus-cycle
  (testing "Tab during paste appends \\t instead of cycling focus"
    (let [[m1 _] (app/update-model {:input "" :pasting? false :focus :input} (msg/key-press :paste-start))
          [m2 _] (app/update-model m1 (msg/key-press :tab))]
      (is (= "\t" (:input m2))   "Tab appended as literal character")
      (is (= :input (:focus m2)) "focus not changed by Tab during paste"))))

(deftest alt-enter-inserts-newline-without-submitting
  (testing "Alt+Enter (Option+Return on macOS) inserts \\n without dispatching a :user/message"
    (let [events  (atom [])
          model   {:input "line one" :dispatch! #(swap! events conj %)}
          [m cmd] (app/update-model model (msg/key-press "\r" :alt true))]
      (is (= "line one\n" (:input m)) "\\n appended to buffer")
      (is (nil? cmd)                  "no command returned")
      (is (empty? @events)            "no :user/message dispatched"))))

(deftest enter-on-multiline-buffer-dispatches-full-text
  (testing "Enter on a buffer with embedded \\n dispatches the whole text as one :user/message"
    (let [events  (atom [])
          model   {:input "line one\nline two" :dispatch! #(swap! events conj %)}
          [m cmd] (app/update-model model (msg/key-press :enter))]
      (is (= "" (:input m)) "buffer cleared after submit")
      (is (some? cmd))
      ((:fn cmd))
      (is (= [{:event/type :user/message :content "line one\nline two"}] @events)
          "full multiline text dispatched as a single event"))))

(deftest single-line-enter-submit-regression
  (testing "single-line Enter submit path is unchanged after multiline changes"
    (let [events  (atom [])
          [m cmd] (app/update-model {:input "hello" :dispatch! #(swap! events conj %)}
                                    (msg/key-press :enter))]
      (is (= "" (:input m)))
      ((:fn cmd))
      (is (= [{:event/type :user/message :content "hello"}] @events)))))

(deftest history-navigation-regression-after-multiline
  (testing "↑/↓ history navigation works correctly after multiline infrastructure is in place"
    (let [model  (assoc (model-with-history "git status" "git diff") :pasting? false)
          [m1 _] (app/update-model model (msg/key-press :up))
          [m2 _] (app/update-model m1    (msg/key-press :up))
          [m3 _] (app/update-model m2    (msg/key-press :down))]
      (is (= "git diff"   (:input m1)) "first ↑ shows most recent entry")
      (is (= "git status" (:input m2)) "second ↑ shows older entry")
      (is (= "git diff"   (:input m3)) "↓ steps forward through history"))))
