(ns pa.observability
  (:require [integrant.core :as ig]
            [portal.api :as portal]
            [taoensso.timbre :as log]))

(defmethod ig/init-key :pa.observability/portal [_ _opts]
  (let [p (portal/open {:launcher :vs-code})]
    (add-tap #'portal/submit)
    (log/info "portal initialized")
    {:instance p}))

(defmethod ig/halt-key! :pa.observability/portal [_ {:keys [instance]}]
  (remove-tap #'portal/submit)
  (portal/close instance)
  (log/info "portal closed"))
