(ns pa.ui.selector
  "Pure state machine for the slash-command selector overlay — a sibling to
  pa.ui.input's history navigation. UI-local and ephemeral: it reads the command
  registry and the current input buffer and tracks only a highlight index plus a
  dismissed flag. It dispatches nothing; completing a command edits the buffer
  (in pa.ui.app), and only Enter on the completed line dispatches a runtime event.

  Selector state: {:selector/index      highlighted row in the filtered list
                   :selector/dismissed? true after Esc, until the buffer leaves
                                        the command-name phase}

  open? is derived, not stored: the overlay is open exactly while the buffer is
  in the command-name phase (a leading slash, no whitespace yet) and has not been
  dismissed. Deriving it keeps open/closed in lockstep with the buffer with no
  separate flag to sync.

  All fns are pure over [state buffer ...]; matches reads the registry."
  (:require [clojure.string :as str]
            [pa.commands.registry :as registry]))

(def initial {:selector/index 0 :selector/dismissed? false})

(defn name-phase?
  "True when buffer is in the command-name phase: a leading slash with no
  whitespace yet (still typing/selecting the command name, before any argument)."
  [buffer]
  (boolean (and (string? buffer)
                (str/starts-with? buffer "/")
                (not (re-find #"\s" buffer)))))

(defn filter-text
  "The command-name prefix being typed (buffer minus the leading slash) while in
  the name phase, else nil."
  [buffer]
  (when (name-phase? buffer) (subs buffer 1)))

(defn matches
  "The registered command specs whose name has the current filter as a prefix,
  sorted by name — all commands when the filter is empty. Empty when nothing
  matches."
  [buffer]
  (let [ft (or (filter-text buffer) "")]
    (->> (registry/all-commands)
         (filter #(str/starts-with? (:command %) ft))
         (sort-by :command))))

(defn open?
  "Whether the overlay is showing: in the name phase and not dismissed."
  [state buffer]
  (and (name-phase? buffer)
       (not (:selector/dismissed? state))))

(defn sync-state
  "Reconcile the selector after a buffer edit. In the name phase, clamp the
  highlight to the current match count. On leaving the name phase, reset to
  initial (clearing any dismissal) so retyping a slash reopens the overlay."
  [state buffer]
  (if (name-phase? buffer)
    (let [n (count (matches buffer))]
      (assoc state :selector/index (-> (get state :selector/index 0)
                                       (max 0)
                                       (min (max 0 (dec n))))))
    initial))

(defn dismiss
  "Esc: close the overlay and mark it dismissed until the buffer leaves the name
  phase."
  [state]
  (assoc state :selector/index 0 :selector/dismissed? true))

(defn move
  "↑/↓: move the highlight by delta with wraparound over the current matches."
  [state buffer delta]
  (let [n (count (matches buffer))]
    (if (pos? n)
      (assoc state :selector/index (mod (+ (get state :selector/index 0) delta) n))
      state)))

(defn highlighted
  "The command spec currently highlighted, or nil when the overlay is closed or
  no command matches."
  [state buffer]
  (when (open? state buffer)
    (let [ms (vec (matches buffer))]
      (get ms (get state :selector/index 0)))))
