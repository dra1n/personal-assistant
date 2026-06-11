(ns pa.observability
  (:require [integrant.core :as ig]
            [portal.api :as portal]
            [taoensso.timbre :as log]))

(defmethod ig/init-key :pa.observability/portal [_ {:keys [enabled? launcher]}]
  (when enabled?
    (let [p (portal/open {:launcher (keyword launcher)})]
      (add-tap #'portal/submit)
      (log/info "portal initialized" {:launcher launcher})
      {:instance p})))

(defmethod ig/halt-key! :pa.observability/portal [_ state]
  (when state
    (remove-tap #'portal/submit)
    (portal/close (:instance state))
    (log/info "portal closed")))
