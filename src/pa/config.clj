(ns pa.config)

(defn system-config []
  {:pa.logging/timbre       {}
   :pa.observability/portal {}
   :pa.runtime/event-bus    {}
   :pa.ui/terminal          {}})
