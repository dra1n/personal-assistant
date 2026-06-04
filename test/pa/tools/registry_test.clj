(ns pa.tools.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.tools.registry :as tools]))

;; Isolate each test in an empty registry (other namespaces register real tools
;; globally at load time), then restore the real registry afterward.
(use-fixtures :each
  (fn [f]
    (let [snap (tools/snapshot)]
      (tools/restore! {})
      (try (f) (finally (tools/restore! snap))))))

(def ^:private noop-spec
  {:fn (fn [_args _ctx] :ok) :schema {} :description "noop"})

(deftest reg-tool-registers-and-returns-name
  (testing "reg-tool stores the spec and returns the tool name"
    (is (= :fs/read-file (tools/reg-tool :fs/read-file noop-spec)))
    (is (= noop-spec (tools/get-tool :fs/read-file)))))

(deftest get-tool-returns-nil-for-unknown
  (is (nil? (tools/get-tool :fs/does-not-exist))))

(deftest registered-tools-returns-name-set
  (tools/reg-tool :fs/read-file noop-spec)
  (tools/reg-tool :fs/write-file noop-spec)
  (is (= #{:fs/read-file :fs/write-file} (tools/registered-tools))))

(deftest reg-tool-overwrites-existing
  (testing "re-registering a name replaces the prior spec"
    (tools/reg-tool :fs/read-file noop-spec)
    (let [spec2 (assoc noop-spec :description "v2")]
      (tools/reg-tool :fs/read-file spec2)
      (is (= "v2" (:description (tools/get-tool :fs/read-file)))))))

(deftest reg-tool-rejects-invalid-specs
  (testing "preconditions reject malformed registrations"
    (is (thrown? AssertionError (tools/reg-tool "fs/read-file" noop-spec))
        "tool name must be a qualified keyword")
    (is (thrown? AssertionError (tools/reg-tool :read-file noop-spec))
        "tool name must be namespaced")
    (is (thrown? AssertionError (tools/reg-tool :fs/x (dissoc noop-spec :fn)))
        ":fn is required and must be invocable")
    (is (thrown? AssertionError (tools/reg-tool :fs/x (dissoc noop-spec :schema)))
        ":schema is required")
    (is (thrown? AssertionError (tools/reg-tool :fs/x (assoc noop-spec :description 42)))
        ":description must be a string")))

(deftest snapshot-restore-round-trips
  (tools/reg-tool :fs/read-file noop-spec)
  (let [snap (tools/snapshot)]
    (tools/reg-tool :fs/write-file noop-spec)
    (is (= #{:fs/read-file :fs/write-file} (tools/registered-tools)))
    (tools/restore! snap)
    (is (= #{:fs/read-file} (tools/registered-tools)))))
