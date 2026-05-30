(ns pa.storage.events-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [pa.storage.events :as events]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:dynamic *events-path* nil)

(defn- with-tmp-events-file [f]
  (let [tmp (java.nio.file.Files/createTempFile
             "pa-events-test-" ".edn"
             (make-array java.nio.file.attribute.FileAttribute 0))
        path (str tmp)]
    (binding [*events-path* path]
      (try
        (f)
        (finally (.delete (io/file path)))))))

(use-fixtures :each with-tmp-events-file)

;; ---------------------------------------------------------------------------
;; append-event! / load-events round-trip
;; ---------------------------------------------------------------------------

(deftest append-and-load-single-event
  (testing "a single appended event is returned by load-events"
    (let [event {:event/type :user/message
                 :event/id   #uuid "00000000-0000-0000-0000-000000000001"
                 :text       "hello"}]
      (events/append-event! *events-path* event)
      (let [loaded (events/load-events *events-path*)]
        (is (= 1 (count loaded)))
        (is (= :user/message (:event/type (first loaded))))
        (is (= "hello" (:text (first loaded))))))))

(deftest append-and-load-multiple-events
  (testing "multiple appended events are loaded in order"
    (let [e1 {:event/type :user/message :text "first"
              :event/id #uuid "00000000-0000-0000-0000-000000000001"}
          e2 {:event/type :scheduler/tick
              :event/id #uuid "00000000-0000-0000-0000-000000000002"}
          e3 {:event/type :memory/stored :key :fact-1
              :event/id #uuid "00000000-0000-0000-0000-000000000003"}]
      (events/append-event! *events-path* e1)
      (events/append-event! *events-path* e2)
      (events/append-event! *events-path* e3)
      (let [loaded (events/load-events *events-path*)]
        (is (= 3 (count loaded)))
        (is (= :user/message  (:event/type (nth loaded 0))))
        (is (= :scheduler/tick (:event/type (nth loaded 1))))
        (is (= :memory/stored  (:event/type (nth loaded 2))))))))

(deftest load-events-returns-empty-for-blank-file
  (testing "empty file yields an empty vector"
    (is (= [] (events/load-events *events-path*)))))

(deftest load-events-returns-empty-for-missing-file
  (testing "non-existent path yields an empty vector"
    (is (= [] (events/load-events "/tmp/pa-no-such-file-test.edn")))))

(deftest round-trip-preserves-all-event-fields
  (testing "all top-level fields survive the EDN serialization round-trip"
    (let [id  #uuid "cafebabe-dead-beef-cafe-babe00000001"
          ts  (java.time.Instant/parse "2026-01-15T10:30:00Z")
          event {:event/type      :user/message
                 :event/id        id
                 :event/timestamp ts
                 :text            "round-trip test"
                 :source          :terminal}]
      (events/append-event! *events-path* event)
      (let [loaded (first (events/load-events *events-path*))]
        (is (= :user/message (:event/type loaded)))
        (is (= id            (:event/id loaded)))
        (is (= "round-trip test" (:text loaded)))
        (is (= :terminal     (:source loaded)))
        ;; Instant serializes as #inst → loads back as java.util.Date (inst?)
        (is (inst?           (:event/timestamp loaded)))))))

(deftest append-is-additive-not-overwriting
  (testing "each append call adds to the file rather than replacing it"
    (events/append-event! *events-path* {:event/type :test/a
                                         :event/id #uuid "00000000-0000-0000-0000-000000000001"})
    (let [after-first (events/load-events *events-path*)]
      (is (= 1 (count after-first))))
    (events/append-event! *events-path* {:event/type :test/b
                                         :event/id #uuid "00000000-0000-0000-0000-000000000002"})
    (let [after-second (events/load-events *events-path*)]
      (is (= 2 (count after-second))))))
