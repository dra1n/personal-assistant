(ns pa.spec
  (:require [clojure.spec.alpha :as s]))

(defn validate!
  "Checks x against spec, returning true on success.
  On failure, throws ex-info with the failing value and a human-readable
  explanation. Meant for use in :pre/:post checks, e.g.
  {:post [(spec/validate! ::event %)]}."
  [spec x]
  (or (s/valid? spec x)
      (throw (ex-info (str "value did not conform to " spec)
                       {:spec    spec
                        :value   x
                        :explain (s/explain-str spec x)}))))
