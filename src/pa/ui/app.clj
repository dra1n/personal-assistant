(ns pa.ui.app
  (:require [charm.message :as msg]
            [charm.program :as charm]
            [charm.style.core :as style]
            [clojure.string :as str]
            [pa.state.db :as db]
            [pa.state.queries :as queries]
            [pa.ui.subscribe :as subscribe]))

;; ---------------------------------------------------------------------------
;; Charm model
;;
;; {:db        <latest runtime state snapshot>
;;  :db-ch     <core.async channel — owned by pa.ui.core, passed in at init>
;;  :dispatch! <runtime dispatch fn — UI's only way to push events inward>
;;  :input     <UI-local input buffer string>
;;  :width     <terminal width, tracked from :window-size>
;;  :height    <terminal height>}
;;
;; Runtime state is read only through pa.state.queries; the UI never mutates
;; it directly. User input becomes a :user/message event via :dispatch!.
;; ---------------------------------------------------------------------------

(defn init
  "Return a charm init fn. Returns the initial model plus the first watch-db command."
  [{:keys [db-ch watch-cmd dispatch!]}]
  (fn []
    [{:db        (db/current-db)
      :db-ch     db-ch
      :dispatch! dispatch!
      :input     ""
      :width     80
      :height    24}
     watch-cmd]))

;; ---------------------------------------------------------------------------
;; Update
;; ---------------------------------------------------------------------------

(defn- dispatch-user-message
  "A charm command that pushes a :user/message event into the runtime. The
  command produces no follow-up charm message (returns nil)."
  [dispatch! text]
  (charm/cmd
   (fn []
     (dispatch! {:event/type :user/message :content text})
     nil)))

(defn- backspace [s]
  (if (seq s) (subs s 0 (dec (count s))) s))

(defn update-model [model message]
  (cond
    (and (msg/key-press? message) (msg/key-match? message "ctrl+c"))
    [model charm/quit-cmd]

    (= :runtime/db-updated (:type message))
    [(assoc model :db (:db message))
     (subscribe/watch-db-cmd (:db-ch model))]

    (= :window-size (:type message))
    [(assoc model :width (:width message) :height (:height message)) nil]

    ;; Enter — commit the buffer as a user message (ignored when blank).
    (msg/key-match? message :enter)
    (let [text (str/trim (:input model))]
      (if (str/blank? text)
        [model nil]
        [(assoc model :input "")
         (dispatch-user-message (:dispatch! model) text)]))

    (msg/key-match? message :backspace)
    [(update model :input backspace) nil]

    ;; Space arrives as a special key, not a runes string.
    (msg/key-match? message :space)
    [(update model :input str " ") nil]

    ;; Printable characters arrive as a runes string; ignore modified chords.
    (and (msg/key-press? message)
         (string? (:key message))
         (not (msg/ctrl? message))
         (not (msg/alt? message)))
    [(update model :input str (:key message)) nil]

    :else
    [model nil]))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(def ^:private accent style/cyan)

(defn- header []
  (style/styled "  personal assistant  " :bold true :fg style/black :bg accent))

(defn- render-turn [{:keys [role content]}]
  (let [label (case role
                :user      (style/styled "you"       :fg accent :bold true)
                :assistant (style/styled "assistant" :fg style/green :bold true)
                (style/styled (name (or role :system)) :faint true))]
    (str label "\n" content)))

(defn- conversation-view [model]
  (let [turns (queries/conversation (:db model))]
    (if (seq turns)
      (str/join "\n\n" (map render-turn turns))
      (style/styled "Type a message and press Enter." :faint true))))

(defn- input-view [{:keys [input width]}]
  ;; NOTE: charm's apply-text-style downgrades box-drawing edges (─ │) to
  ;; ASCII (- |) whenever a border :fg/:bg is set, so the border is left
  ;; uncoloured; the accent lives on the prompt glyph instead.
  (style/render
   (style/style :border  style/rounded-border
                :padding [0 1]
                :width   (max 20 (- (or width 80) 4)))
   (str (style/styled "›" :fg accent :bold true) " " input)))

(defn view [model]
  (style/join-vertical
   :left
   (header)
   ""
   (conversation-view model)
   ""
   (input-view model)))
