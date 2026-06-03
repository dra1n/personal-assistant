(ns pa.runtime.tool-effect-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.runtime.events :as events]
            [pa.runtime.executor :as executor]
            [pa.runtime.handlers]                  ; registers the :tool/result handler
            [pa.runtime.registry :as registry]
            [pa.runtime.replay :as replay]
            [pa.state.db :as db]
            [pa.tools.registry :as tools])
  (:import [java.time Instant]
           [java.util UUID]))

;; ---------------------------------------------------------------------------
;; Fixtures: capture dispatched events; snapshot/restore the tool registry and
;; runtime state around each test so nothing leaks.
;; ---------------------------------------------------------------------------

(def ^:private dispatched (atom []))

(defn- make-ctx [& {:as extra}]
  (merge {:dispatch! (fn [event] (swap! dispatched conj event))} extra))

(use-fixtures :each
  (fn [f]
    (let [snap (tools/snapshot)]
      (reset! dispatched [])
      (reset! db/db db/initial-db)
      (reset! db/trace-log [])
      (try (f) (finally (tools/restore! snap))))))

(defn- only-result [] (first @dispatched))

;; ---------------------------------------------------------------------------
;; :tool/invoke effect
;; ---------------------------------------------------------------------------

(deftest tool-invoke-ok-dispatches-result
  (testing "a successful tool call dispatches one :tool/result with output + duration"
    (tools/reg-tool :test/echo
                    {:fn          (fn [args _ctx] {:echoed (:msg args)})
                     :schema      {}
                     :description "echoes its argument"})
    (executor/execute-effect :tool/invoke
                             {:tool/name :test/echo :tool/args {:msg "hi"}}
                             (make-ctx))
    (let [r (only-result)]
      (is (= 1 (count @dispatched)) "exactly one event dispatched")
      (is (= :tool/result (:event/type r)))
      (is (= :test/echo (:tool/name r)))
      (is (= {:msg "hi"} (:tool/args r)))
      (is (= :ok (:tool/status r)))
      (is (= {:echoed "hi"} (:tool/output r)))
      (is (nat-int? (:tool/duration-ms r)) "duration is recorded"))))

(deftest tool-invoke-passes-ctx-to-tool-fn
  (testing "the tool fn receives the runtime ctx (so it can reach capabilities)"
    (let [seen (atom nil)]
      (tools/reg-tool :test/peek
                      {:fn          (fn [_args ctx] (reset! seen (:marker ctx)) :ok)
                       :schema      {}
                       :description "captures ctx"})
      (executor/execute-effect :tool/invoke
                               {:tool/name :test/peek :tool/args {}}
                               (make-ctx :marker :present))
      (is (= :present @seen)))))

(deftest tool-invoke-unknown-tool-errors
  (testing "invoking an unregistered tool dispatches an :unknown-tool error"
    (executor/execute-effect :tool/invoke
                             {:tool/name :test/missing :tool/args {}}
                             (make-ctx))
    (let [r (only-result)]
      (is (= :error (:tool/status r)))
      (is (= :unknown-tool (get-in r [:tool/error :type]))))))

(deftest tool-invoke-dry-run-skips-side-effect
  (testing "dry-run logs + dispatches :dry-run without calling the tool fn"
    (let [called (atom false)]
      (tools/reg-tool :test/mutate
                      {:fn          (fn [_args _ctx] (reset! called true) :did-it)
                       :schema      {}
                       :description "would mutate"})
      (executor/execute-effect :tool/invoke
                               {:tool/name :test/mutate :tool/args {} :tool/dry-run? true}
                               (make-ctx))
      (is (false? @called) "tool fn is never invoked")
      (is (= :dry-run (:tool/status (only-result))))
      (is (not (contains? (only-result) :tool/output))))))

(deftest tool-invoke-exception-becomes-error-result
  (testing "a throwing tool fn yields an :exception error result, not a crash"
    (tools/reg-tool :test/boom
                    {:fn          (fn [_args _ctx] (throw (ex-info "kaboom" {})))
                     :schema      {}
                     :description "always throws"})
    (executor/execute-effect :tool/invoke
                             {:tool/name :test/boom :tool/args {}}
                             (make-ctx))
    (let [r (only-result)]
      (is (= :error (:tool/status r)))
      (is (= :exception (get-in r [:tool/error :type])))
      (is (= "kaboom" (get-in r [:tool/error :message])))
      (is (nat-int? (:tool/duration-ms r))))))

;; ---------------------------------------------------------------------------
;; :tool/result handler
;; ---------------------------------------------------------------------------

(defn- handler [event-type] (:fn (registry/get-handler event-type)))

(deftest tool-result-handler-records-payload-and-stores-full-event
  (testing ":tool/result records only the payload in :db but persists the full event"
    (let [event {:event/type :tool/result :event/id (UUID/randomUUID)
                 :event/timestamp (Instant/now)
                 :tool/name :test/echo :tool/args {:msg "hi"}
                 :tool/status :ok :tool/output {:echoed "hi"}}
          fx    ((handler :tool/result) {:db db/initial-db :event event})
          [rec] (:tool/results (:db fx))]
      (testing "runtime state holds the tool payload with the envelope stripped"
        (is (= {:tool/name :test/echo :tool/args {:msg "hi"}
                :tool/status :ok :tool/output {:echoed "hi"}} rec))
        (is (empty? (select-keys rec events/envelope-keys))
            "no :event/* envelope keys leak into runtime state"))
      (testing "the full event (envelope included) is persisted for replay"
        (is (= event (:event/store fx))))
      (is (= :ok (get-in fx [:trace :tool/status]))))))

;; ---------------------------------------------------------------------------
;; Replay: tool outcomes reconstruct from events without re-running the tool
;; ---------------------------------------------------------------------------

(deftest tool-result-replays-payload-without-invoking-tool
  (testing "a stored :tool/result reconstructs the payload into :tool/results;
            :tool/invoke (an effect, never an event) is absent from the stream so
            no tool runs, and the event envelope is not duplicated into :db"
    (let [event {:event/type :tool/result :event/id (UUID/randomUUID)
                 :event/timestamp (Instant/now)
                 :tool/name :test/echo :tool/args {:msg "hi"}
                 :tool/status :ok :tool/output {:echoed "hi"}}
          final (replay/replay [event])]
      (is (= [(events/payload event)] (:tool/results final)))
      (is (empty? (select-keys (first (:tool/results final)) events/envelope-keys))))))
