(ns pa.config
  (:require [integrant.core :as ig]))

(defn system-config []
  {:pa.logging/timbre        {}
   :pa.observability/portal  {}
   :storage/fs               {}
   :storage/identity         {:fs (ig/ref :storage/fs)}
   :storage/events           {:fs (ig/ref :storage/fs)}
   :memory/store             {:fs (ig/ref :storage/fs)}
   :db/sqlite                {:fs (ig/ref :storage/fs)}
   :memory/indexer           {:db (ig/ref :db/sqlite) :fs (ig/ref :storage/fs)}
   :llm/provider             {:provider :openai}
   :tool.fs/policy           {:fs (ig/ref :storage/fs)}
   :ui/deltas                {}
   :pa.runtime/dispatcher    {:config   {:env :production}
                              :events   (ig/ref :storage/events)
                              :identity (ig/ref :storage/identity)
                              :memory   (ig/ref :memory/store)
                              :indexer  (ig/ref :memory/indexer)
                              :llm      (ig/ref :llm/provider)
                              :policy   (ig/ref :tool.fs/policy)
                              :deltas   (ig/ref :ui/deltas)}
   :pa.ui/terminal           {:dispatcher (ig/ref :pa.runtime/dispatcher)
                              :deltas     (ig/ref :ui/deltas)}})
