(ns pa.storage.memory-store
  (:require [integrant.core :as ig]
            [pa.storage.memory :as memory]
            [pa.storage.memory-wisdom :as wisdom]))

;; ---------------------------------------------------------------------------
;; Integrant component
;;
;; Provides :write-memory! fn to the executor context so :memory/write effects
;; can call it without knowing the root path.
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :memory/store [_ {:keys [fs]}]
  (let [root (:root fs)]
    {:write-memory!   (partial memory/write-daily root)
     :merge-wisdom!   (partial wisdom/merge-items! root)
     :read-wisdom!    (fn [] (wisdom/read-items root))
     :rewrite-wisdom! (partial wisdom/rewrite! root)}))

(defmethod ig/halt-key! :memory/store [_ _])
