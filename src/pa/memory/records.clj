(ns pa.memory.records
  (:require [clojure.spec.alpha :as s])
  (:import [java.util UUID]
           [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Memory record spec
;; ---------------------------------------------------------------------------

(s/def :memory/id         string?)
(s/def :memory/type       #{:episodic :semantic :fact})
(s/def :memory/title      string?)
(s/def :memory/summary    string?)
(s/def :memory/tags       (s/coll-of string? :kind vector?))
(s/def :memory/path       string?)
(s/def :memory/created-at inst?)

(s/def ::record
  (s/keys :req [:memory/id
                :memory/type
                :memory/title
                :memory/summary
                :memory/created-at]
          :opt [:memory/tags
                :memory/path]))

(s/def ::input
  (s/keys :req [:memory/type
                :memory/title
                :memory/summary]
          :opt [:memory/tags
                :memory/path]))

;; ---------------------------------------------------------------------------
;; Constructor
;;
;; Required fields supplied by caller:
;;   :memory/type     — :episodic | :semantic | :fact
;;   :memory/title    — short human-readable label
;;   :memory/summary  — prose summary (the canonical content)
;;
;; Optional fields supplied by caller:
;;   :memory/tags     — vector of strings
;;   :memory/path     — set by writer after persisting
;;
;; Generated fields (always stamped by make, cannot be overridden):
;;   :memory/id         — random UUID string
;;   :memory/created-at — java.time.Instant
;; ---------------------------------------------------------------------------

(defn make
  "Construct a memory record map from fields.
  Stamps :memory/id (UUID) and :memory/created-at (Instant).
  Required keys: :memory/type, :memory/title, :memory/summary."
  [fields]
  (when-not (s/valid? ::input fields)
    (throw (ex-info (str "Invalid memory record input:\n" (s/explain-str ::input fields))
                    {:fields fields})))
  (merge fields
         {:memory/id         (str (UUID/randomUUID))
          :memory/created-at (Instant/now)}))
