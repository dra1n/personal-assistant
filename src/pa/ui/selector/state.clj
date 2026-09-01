(ns pa.ui.selector.state
  "Pure state machine for the input overlays. The slash-command selector and the
  @-mention picker are the same machine over different *sources*, so filtering,
  highlighting, wraparound and Esc behave identically in both.

  A source describes what the overlay is offering:

    {:trigger   \"/\"        the character that opens it
     :anchored? true        must sit at the start of the buffer (a command) or
                            may appear anywhere in it (a mention)
     :match     :prefix     :prefix (commands) or :substring (resources, whose
                            labels start with a server name nobody types first)
     :rows      [{:label \"/help\"     as shown, trigger included
                  :hint  \"on | off\"  right-aligned usage
                  :help  \"...\"       description of the highlighted row
                  :value <anything>  what the caller acts on
                  :kind  :command}]}

  Selector state: {:selector/index      highlighted row in the filtered list
                   :selector/dismissed? true after Esc, until the buffer leaves
                                        the trigger phase}

  open? is derived, not stored: the overlay is open exactly while the buffer
  carries a trigger token and has not been dismissed. Deriving it keeps
  open/closed in lockstep with the buffer with no separate flag to sync.

  Nothing here dispatches. Completing a selection edits the buffer (in
  pa.ui.app); only Enter on the completed line reaches the runtime."
  (:require [clojure.string :as str]))

(def initial {:selector/index 0 :selector/dismissed? false})

;; ---------------------------------------------------------------------------
;; The trigger token
;; ---------------------------------------------------------------------------

(defn token
  "The trigger-prefixed token the buffer currently ends with, as
  `{:text :start :end}`, or nil when the buffer is not in this source's phase.

  A command anchors at the start of the buffer. A mention may sit anywhere,
  but only after whitespace — otherwise every email address in a message would
  open the overlay. Either way the token ends at the first whitespace, so the
  overlay closes once the selection is done and the sentence continues."
  [{:keys [trigger anchored?]} buffer]
  (when (and (string? buffer) trigger)
    (when-let [idx (str/last-index-of buffer trigger)]
      (let [text (subs buffer idx)]
        (when (and (if anchored?
                     (zero? idx)
                     (or (zero? idx) (Character/isWhitespace (.charAt ^String buffer (dec idx)))))
                   (not (re-find #"\s" text)))
          {:text text :start idx :end (count buffer)})))))

(defn filter-text
  "The text typed after the trigger, or nil when out of phase."
  [source buffer]
  (when-let [{:keys [text]} (token source buffer)]
    (subs text (count (:trigger source)))))

;; ---------------------------------------------------------------------------
;; Matching
;; ---------------------------------------------------------------------------

(defn- body
  "A row's label without its trigger — what the filter is compared against, so
  typing `hel` matches the row displayed as `/help`."
  [{:keys [label]} trigger]
  (cond-> (str label)
    (and trigger (str/starts-with? (str label) trigger)) (subs (count trigger))))

(defn matches
  "The source's rows matching what has been typed, in source order. All rows
  when nothing has been typed yet; empty when out of phase or nothing matches."
  [{:keys [rows trigger match] :as source} buffer]
  (if-let [ft (some-> (filter-text source buffer) str/lower-case)]
    (let [hit? (case (or match :prefix)
                 :prefix    #(str/starts-with? (str/lower-case (body % trigger)) ft)
                 :substring #(str/includes? (str/lower-case (body % trigger)) ft))]
      (filterv hit? rows))
    []))

;; ---------------------------------------------------------------------------
;; Overlay state
;; ---------------------------------------------------------------------------

(defn open?
  "Whether the overlay is showing: in the trigger phase and not dismissed."
  [source state buffer]
  (boolean (and source
                (token source buffer)
                (not (:selector/dismissed? state)))))

(defn sync-state
  "Reconcile the selector after a buffer edit. In phase, clamp the highlight to
  the current match count. On leaving the phase (or with no active source),
  reset to initial — clearing any dismissal, so retyping a trigger reopens it."
  [source state buffer]
  (if (and source (token source buffer))
    (let [n (count (matches source buffer))]
      (assoc state :selector/index (-> (get state :selector/index 0)
                                       (max 0)
                                       (min (max 0 (dec n))))))
    initial))

(defn dismiss
  "Esc: close the overlay and mark it dismissed until the buffer leaves the
  trigger phase."
  [state]
  (assoc state :selector/index 0 :selector/dismissed? true))

(defn move
  "↑/↓: move the highlight by delta with wraparound over the current matches."
  [source state buffer delta]
  (let [n (count (matches source buffer))]
    (if (pos? n)
      (assoc state :selector/index (mod (+ (get state :selector/index 0) delta) n))
      state)))

(defn highlighted
  "The row currently highlighted, or nil when the overlay is closed or nothing
  matches."
  [source state buffer]
  (when (open? source state buffer)
    (let [ms (vec (matches source buffer))]
      (get ms (get state :selector/index 0)))))
