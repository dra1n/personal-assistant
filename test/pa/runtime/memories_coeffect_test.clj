(ns pa.runtime.memories-coeffect-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [pa.db.memory :as db-memory]
            [pa.db.schema :as schema]
            [pa.memory.records :as records]
            [pa.runtime.coeffects :as coeffects])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Fixtures — temp dir + in-process SQLite db
;; ---------------------------------------------------------------------------

(def ^:dynamic *ds* nil)

(defn- delete-dir! [f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)] (delete-dir! child)))
  (.delete f))

(defn- with-db [f]
  (let [tmp  (Files/createTempDirectory "pa-mem-coeff-" (make-array FileAttribute 0))
        root (str tmp)]
    (.mkdirs (io/file root "sqlite"))
    (let [ds (jdbc/get-datasource (str "jdbc:sqlite:" root "/sqlite/test.db"))]
      (schema/init! ds)
      (binding [*ds* ds]
        (try (f) (finally (delete-dir! (.toFile tmp))))))))

(use-fixtures :each with-db)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- seed-record! [title summary]
  (let [r (merge (records/make {:memory/type    :episodic
                                :memory/title   title
                                :memory/summary summary})
                 {:memory/path "/fake/path.md"})]
    (db-memory/index! *ds* r)
    r))

(defn- make-ctx [event]
  {:event          event
   :system-context {:runtime {:retrieve-memories! (fn [q] (db-memory/retrieve *ds* q))}}
   :coeffects      {}})

;; ---------------------------------------------------------------------------
;; memories-interceptor tests
;; ---------------------------------------------------------------------------

(deftest memories-injected-for-user-message
  (testing ":memories is non-empty when the query matches a seeded record"
    (seed-record! "Clojure runtime" "event-driven personal assistant")
    (let [ctx    (make-ctx {:event/type :user/message :content "Clojure"})
          result ((:before coeffects/memories-interceptor) ctx)]
      (is (seq (get-in result [:coeffects :memories])))
      (is (= "Clojure runtime"
             (:memory/title (first (get-in result [:coeffects :memories]))))))))

(deftest memories-empty-when-db-empty
  (testing ":memories is [] when no records exist"
    (let [ctx    (make-ctx {:event/type :user/message :content "Clojure"})
          result ((:before coeffects/memories-interceptor) ctx)]
      (is (= [] (get-in result [:coeffects :memories]))))))

(deftest memories-empty-without-retrieve-fn
  (testing ":memories is [] when :retrieve-memories! is absent from runtime"
    (seed-record! "Clojure runtime" "something")
    (let [ctx    {:event          {:event/type :user/message :content "Clojure"}
                  :system-context {:runtime {}}
                  :coeffects      {}}
          result ((:before coeffects/memories-interceptor) ctx)]
      (is (= [] (get-in result [:coeffects :memories]))))))

(deftest memories-empty-for-blank-query
  (testing ":memories is [] when event :content is blank"
    (seed-record! "Clojure runtime" "something")
    (let [ctx    (make-ctx {:event/type :user/message :content ""})
          result ((:before coeffects/memories-interceptor) ctx)]
      (is (= [] (get-in result [:coeffects :memories]))))))
