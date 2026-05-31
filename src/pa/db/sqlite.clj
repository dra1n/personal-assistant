(ns pa.db.sqlite
  (:require [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [pa.db.schema :as schema]))

(defmethod ig/init-key :db/sqlite [_ {:keys [fs]}]
  (let [path (str (:root fs) "/sqlite/assistant.db")
        ds   (jdbc/get-datasource (str "jdbc:sqlite:" path))]
    (schema/init! ds)
    {:datasource ds}))

(defmethod ig/halt-key! :db/sqlite [_ _])
