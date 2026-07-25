(ns pa.ui.selector.view
  "Rendering for the slash-command selector overlay: draws the overlay box that
  sits above the input while the selector is open. Pure presentation. The
  selector's state lives in pa.ui.selector.state; its view-model and sizing
  (selector-spec, selector-lines) live in pa.ui.view.layout; the bordered-list
  drawing is delegated to the reusable pa.ui.view.overlay primitive."
  (:require [pa.ui.view.layout :as layout]
            [pa.ui.view.overlay :as overlay]))

(defn selector-overlay
  "The rendered selector overlay box, or nil when closed."
  [model]
  (when-let [spec (layout/selector-spec model)]
    (overlay/overlay-list (assoc spec :inner-width (layout/inner-width model)))))
