(ns pa.runtime.events
  (:require [clojure.spec.alpha :as s])
  (:import [java.util UUID]
           [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Schema
;;
;; Events are immutable EDN maps representing facts that already happened.
;; :event/type  — namespaced keyword identifying the event
;; :id          — UUID, auto-stamped by make-event
;; :timestamp   — java.time.Instant, auto-stamped by make-event
;; All other keys are top-level payload fields.
;; ---------------------------------------------------------------------------

(s/def :event/type qualified-keyword?)
(s/def :event/id uuid?)
(s/def :event/timestamp inst?)

(s/def ::event
  (s/keys :req [:event/type :event/id :event/timestamp]))

(defn make-event
  "Stamps :event/id and :event/timestamp onto the given event map.
  Caller-supplied :event/id or :event/timestamp are preserved (useful in tests)."
  [m]
  {:pre [(qualified-keyword? (:event/type m))]}
  (merge {:event/id        (UUID/randomUUID)
          :event/timestamp (Instant/now)}
         m))
