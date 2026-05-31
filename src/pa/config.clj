(ns pa.config
  (:require [integrant.core :as ig]))

(defn system-config []
  {:pa.logging/timbre        {}
   :pa.observability/portal  {}
   :storage/fs               {}
   :storage/identity         {:fs (ig/ref :storage/fs)}
   :storage/events           {:fs (ig/ref :storage/fs)}
   :memory/store             {:fs (ig/ref :storage/fs)}
   :pa.runtime/dispatcher    {:config   {:env :production}
                              :events   (ig/ref :storage/events)
                              :identity (ig/ref :storage/identity)
                              :memory   (ig/ref :memory/store)}
   :pa.ui/terminal           {}})
