(ns pa.config)

(defn system-config []
  {:pa.logging/timbre        {}
   :pa.observability/portal  {}
   :storage/fs               {}
   :pa.runtime/dispatcher    {:config {:env :production}}
   :pa.ui/terminal           {}})
