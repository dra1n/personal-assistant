(ns pa.ui.input
  "Pure navigation state machine for ↑/↓ command-history browsing.

  Navigation state: {:nav/index nil  — nil means not navigating
                     :nav/draft \"\"}  — the buffer text saved before navigation started

  All fns are pure: [nav history ...] → [new-nav text].")

(def initial-nav {:nav/index nil :nav/draft ""})

(defn navigate-back
  "Move one step back (older) in history. If not yet navigating, snapshots
  current-input as the draft to restore later. Returns [new-nav text]."
  [{:keys [nav/index nav/draft] :as nav} history current-input]
  (if (empty? history)
    [nav current-input]
    (let [draft' (if (nil? index) current-input draft)
          idx    (if (nil? index) (dec (count history)) (max 0 (dec index)))]
      [{:nav/index idx :nav/draft draft'} (:history/text (nth history idx))])))

(defn navigate-forward
  "Move one step forward (newer) in history. Once past the last entry, restores
  the saved draft and resets navigation. Returns [new-nav text]."
  [{:keys [nav/index nav/draft] :as nav} history]
  (if (nil? index)
    [nav draft]
    (let [next-idx (inc index)]
      (if (>= next-idx (count history))
        [initial-nav draft]
        [{:nav/index next-idx :nav/draft draft} (:history/text (nth history next-idx))]))))

(defn reset-navigation
  "Exit navigation mode when a printable character is typed. Appends char to
  the saved draft so neither the draft nor the typed character is lost.
  Returns [new-nav text]."
  [{:keys [nav/draft]} char]
  [initial-nav (str draft char)])
