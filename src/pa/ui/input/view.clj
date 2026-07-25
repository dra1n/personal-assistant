(ns pa.ui.input.view
  "Rendering for the input buffer: the prompt line, the cursor cell, horizontal
  scrolling for long single lines, hard-wrapped multiline editing, and the dim
  enum-ghost preview. Pure presentation over the charm model map. The buffer's
  navigation state lives in pa.ui.input.state; its sizing in pa.ui.view.layout
  (input-line-count, which must stay in lockstep with multiline-with-cursor here)."
  (:require [charm.style.core :as style]
            [clojure.string :as str]
            [pa.commands.parse :as parse]
            [pa.commands.registry :as commands]
            [pa.ui.view.common :as common]
            [pa.ui.view.layout :as layout]))

(def ^:private placeholder "Ask me anything…")

(defn enum-ghost
  "The dim placeholder shown when the input is a recognised :enum command
  awaiting its argument: the command name plus a trailing space and no token yet
  (e.g. '/markdown ⎵'). Returns the current value via the arg-spec :current-fn
  (e.g. \"on\"), or nil. Pure derivation from the model + registry — no new
  runtime state."
  [{:keys [input db] :as _model}]
  (when-let [{:keys [command raw-args]} (parse/command-line input)]
    (when (re-find #"\s\z" (str input))                 ; a trailing space: awaiting the token
      (let [{:keys [kind current-fn]} (:arg-spec (commands/get-command command))]
        (when (and (= :enum kind) current-fn (str/blank? raw-args))
          (current-fn db))))))

(defn- cursor []
  (style/styled " " :reverse true))

(defn- mark-cursor
  "s with the character at pos rendered in reverse video — the cursor cell.
  When pos is at the end, a reversed trailing space is appended instead
  (one extra column; callers must leave room for it)."
  [s pos]
  (if (< pos (count s))
    (str (subs s 0 pos) (style/styled (subs s pos (inc pos)) :reverse true) (subs s (inc pos)))
    (str s (cursor))))

(defn- single-line-with-cursor
  "The input line with the cursor marked at pos, horizontally scrolled so the
  cursor stays visible (roughly centred while mid-string) within avail
  columns. Ellipses flag text scrolled off either side; the result is never
  wider than avail."
  [s pos avail]
  (let [len (count s)]
    (if (< len avail)
      (mark-cursor s pos)
      (let [start     (-> (- pos (quot avail 2))
                          (min (- (inc len) avail))   ; inc: the end-cursor cell
                          (max 0))
            end       (min len (+ start avail))
            head?     (pos? start)
            tail?     (< end len)
            vis-start (if head? (inc start) start)
            vis-end   (if tail? (dec end) end)
            visible   (subs s vis-start vis-end)
            cpos      (-> (- pos vis-start) (max 0) (min (count visible)))]
        (str (when head? "…") (mark-cursor visible cpos) (when tail? "…"))))))

(defn- hard-chunks
  "Hard-wrap a newline-free segment into avail-column rows. A segment whose
  length is an exact multiple of avail gets a trailing empty row — the cell
  the cursor wraps onto after filling the last column — and an empty segment
  is one empty row. Row count is always (inc (quot (count seg) avail)),
  matching pa.ui.view.layout/input-line-count."
  [seg avail]
  (let [base (mapv str/join (partition-all avail seg))]
    (if (zero? (mod (count seg) avail))
      (conj base "")
      base)))

(defn- multiline-with-cursor
  "Multiline buffer rendering: newline-delimited segments hard-wrapped to
  avail columns, first row prefixed with the prompt and continuation rows
  aligned under it, cursor marked on its exact row/column."
  [s pos avail prompt]
  (let [rows    (loop [segs (str/split s #"\n" -1) off 0 acc []]
                  (if-let [seg (first segs)]
                    (recur (rest segs)
                           (+ off (count seg) 1)
                           (into acc (map-indexed (fn [i c] [c (+ off (* i avail))])
                                                  (hard-chunks seg avail))))
                    acc))
        ;; The row whose span contains the cursor; a position on a chunk
        ;; boundary matches two rows and takes the later one (the cursor
        ;; wraps with the text).
        row-idx (or (->> rows
                         (keep-indexed (fn [i [c st]]
                                         (when (<= st pos (+ st (count c))) i)))
                         last)
                    (dec (count rows)))]
    (->> rows
         (map-indexed (fn [i [c st]]
                        (str (if (zero? i) prompt "  ")
                             (if (= i row-idx)
                               (mark-cursor c (-> (- pos st) (max 0) (min (count c))))
                               c))))
         (str/join "\n"))))

(defn input-view [{:keys [input focus] :as model}]
  (let [inner  (layout/inner-width model)
        avail  (max 1 (- inner 5))
        pos    (-> (or (:cursor model) (count input)) (max 0) (min (count input)))
        prompt (str (style/styled "›" :fg common/accent :bold true) " ")
        ghost  (enum-ghost model)]
    (style/render
     (style/style :border  (common/border-for (= :input focus))
                  :padding common/box-padding
                  :width   inner)
     (cond
       (str/blank? input)
       (str prompt (cursor) (style/styled placeholder :faint true))

       (not (str/includes? input "\n"))
       ;; The enum ghost trails the cursor as a dim preview of the current value
       ;; (e.g. '/markdown ⎵' → dim "on"); it awaits a token, so input is short.
       (str prompt (single-line-with-cursor input pos avail)
            (when ghost (style/styled ghost :faint true)))

       :else
       (multiline-with-cursor input pos avail prompt)))))
