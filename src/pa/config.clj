(ns pa.config)

(defn system-config []
  {:pa.logging/timbre        {}
   :pa.observability/portal  {}
   :pa.runtime/dispatcher    {:config {:env :production}}
   :pa.ui/terminal           {}})
