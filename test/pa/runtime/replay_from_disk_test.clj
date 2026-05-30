(ns pa.runtime.replay-from-disk-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [pa.runtime.events :as events]
            [pa.runtime.registry :as registry]
            [pa.runtime.replay :as replay]
            [pa.runtime.state :as state]
            [pa.storage.events :as storage.events]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:dynamic *events-path* nil)

(defn- with-tmp-events-file [f]
  (let [tmp (java.nio.file.Files/createTempFile
             "pa-replay-disk-test-" ".edn"
             (make-array java.nio.file.attribute.FileAttribute 0))
        path (str tmp)]
    (binding [*events-path* path]
      (try (f) (finally (.delete (io/file path)))))))

(use-fixtures :each
  with-tmp-events-file
  (fn [f]
    (reset! state/db state/initial-db)
    (reset! state/trace-log [])
    (let [before (registry/snapshot)]
      (f)
      (registry/restore! before))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- write-fixture-event! [type payload]
  (storage.events/append-event!
   *events-path*
   (events/make-event (merge {:event/type type} payload))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest replay-from-disk-reconstructs-state
  (testing "events written to disk are replayed and produce the expected state"
    (registry/reg-handler :test/add-message
      (fn [{:keys [db event]}]
        {:db (update db :conversation conj {:text (:text event)})}))
    (write-fixture-event! :test/add-message {:text "hello"})
    (write-fixture-event! :test/add-message {:text "world"})
    (let [result (replay/replay-from-disk *events-path*)]
      (is (= [{:text "hello"} {:text "world"}]
             (:conversation result))))))

(deftest replay-from-disk-empty-file-returns-initial-db
  (testing "an empty events file replays to the unmodified initial db"
    (let [result (replay/replay-from-disk *events-path*)]
      (is (= state/initial-db result)))))

(deftest replay-from-disk-does-not-mutate-live-state
  (testing "replaying from disk leaves state/db untouched"
    (registry/reg-handler :test/set-flag
      (fn [{:keys [db]}]
        {:db (assoc db :replayed true)}))
    (write-fixture-event! :test/set-flag {})
    (replay/replay-from-disk *events-path*)
    (is (nil? (:replayed @state/db)))))

(deftest replay-from-disk-threads-state-across-events
  (testing "each event on disk sees the state produced by the previous event"
    (registry/reg-handler :test/append-key
      (fn [{:keys [db event]}]
        {:db (update db :tasks assoc (:key event) true)}))
    (write-fixture-event! :test/append-key {:key :a})
    (write-fixture-event! :test/append-key {:key :b})
    (write-fixture-event! :test/append-key {:key :c})
    (let [result (replay/replay-from-disk *events-path*)]
      (is (= {:a true :b true :c true} (:tasks result))))))

(deftest replay-from-disk-accepts-custom-initial-db
  (testing "replay-from-disk starts from the provided initial-db"
    (registry/reg-handler :test/noop (fn [{:keys [db]}] {:db db}))
    (write-fixture-event! :test/noop {})
    (let [seed-db (assoc state/initial-db :conversation [{:text "seed"}])
          result  (replay/replay-from-disk *events-path* seed-db)]
      (is (= [{:text "seed"}] (:conversation result))))))
