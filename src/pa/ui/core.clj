(ns pa.ui.core
  (:require [charm.program :as charm]
            [clojure.core.async :as async]
            [integrant.core :as ig]
            [pa.ui.app :as app]
            [pa.ui.subscribe :as subscribe]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Integrant component — assembles subscribe + app, manages lifecycle
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :pa.ui/terminal [_ _opts]
  (let [{:keys [db-ch tap-sink watch-cmd] :as sub} (subscribe/make-subscription)
        _                        (add-tap tap-sink)
        {:keys [quit! result]}   (charm/run-async
                                  {:init   (app/init sub)
                                   :update app/update-model
                                   :view   app/view
                                   :alt-screen  false
                                   :hide-cursor false})]
    (log/info "terminal UI initialized")
    {:db-ch    db-ch
     :tap-sink tap-sink
     :watch-cmd watch-cmd
     :quit!    quit!
     :result   result}))

(defmethod ig/halt-key! :pa.ui/terminal [_ {:keys [quit! db-ch tap-sink]}]
  (remove-tap tap-sink)
  (async/close! db-ch)
  (quit!)
  (log/info "terminal UI stopped"))
