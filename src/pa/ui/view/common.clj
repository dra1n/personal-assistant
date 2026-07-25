(ns pa.ui.view.common
  "Small render helpers shared between the feature view namespaces (pa.ui.input.view,
  pa.ui.selector.view) and the top-level render aggregator (pa.ui.view). Kept in
  their own leaf namespace so both sides can depend on them without a require
  cycle. Presentation only."
  (:require [charm.style.core :as style]))

(def accent style/cyan)

(def box-padding
  "Inner horizontal padding for the bordered content boxes (1 column each
  side), so text doesn't sit flush against the side borders."
  [0 1])

(defn border-for
  "A focused region gets a thick border; otherwise a rounded one. Borders are
  never coloured — charm downgrades box-drawing edges to ASCII when a border
  :fg/:bg is set."
  [focused?]
  (if focused? style/thick-border style/rounded-border))
