(ns pa.tools.schema-test
  "Property-based and unit tests for pa.tools.registry/validate-args.
  Covers: required-key checks, type checks, and all registered fs tool schemas."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pa.runtime.executor :as executor]
            [pa.tools.registry :as tools]
            [pa.tools.fs]))   ; loads fs tool registrations

;; ---------------------------------------------------------------------------
;; Unit tests: validate-args directly

(deftest empty-schema-always-passes
  (testing "{} schema accepts any args, including empty"
    (is (nil? (tools/validate-args {} {})))
    (is (nil? (tools/validate-args {} {:foo "bar" :baz 42})))
    (is (nil? (tools/validate-args {} {:x false})))))

(deftest missing-required-key-fails
  (testing "a missing required key returns an error string"
    (let [schema {:type "object"
                  :properties {:path {:type "string"}}
                  :required [:path]}]
      (is (string? (tools/validate-args schema {})))
      (is (re-find #"path" (tools/validate-args schema {}))))))

(deftest all-required-keys-present-passes
  (testing "all required keys present returns nil"
    (let [schema {:type "object"
                  :properties {:path {:type "string"}}
                  :required [:path]}]
      (is (nil? (tools/validate-args schema {:path "/tmp/foo"}))))))

(deftest wrong-type-fails
  (testing "a required key with the wrong type returns an error string"
    (let [schema {:type "object"
                  :properties {:path {:type "string"}}
                  :required [:path]}]
      (is (string? (tools/validate-args schema {:path 42})))
      (is (re-find #"path" (tools/validate-args schema {:path 42}))))))

(deftest optional-key-wrong-type-fails
  (testing "an optional key present with the wrong type still fails"
    (let [schema {:type "object"
                  :properties {:path      {:type "string"}
                               :recursive {:type "boolean"}}
                  :required [:path]}]
      (is (string? (tools/validate-args schema {:path "/tmp" :recursive "yes"})))
      (is (re-find #"recursive" (tools/validate-args schema {:path "/tmp" :recursive "yes"}))))))

(deftest boolean-false-is-valid
  (testing "false is a valid boolean (not falsy nil)"
    (let [schema {:type "object"
                  :properties {:recursive {:type "boolean"}}
                  :required [:recursive]}]
      (is (nil? (tools/validate-args schema {:recursive false}))))))

(deftest multiple-missing-keys-reported
  (testing "multiple missing required keys are all named in the error"
    (let [schema {:type "object"
                  :properties {:from {:type "string"} :to {:type "string"}}
                  :required [:from :to]}
          err    (tools/validate-args schema {})]
      (is (string? err))
      (is (re-find #"from" err))
      (is (re-find #"to" err)))))

;; ---------------------------------------------------------------------------
;; Integration: validation wired into :tool/invoke effect

(def ^:private dispatched (atom []))

(use-fixtures :each
  (fn [f]
    (let [snap (tools/snapshot)]
      (reset! dispatched [])
      (try (f) (finally (tools/restore! snap))))))

(deftest tool-invoke-invalid-args-emits-error-result
  (testing "executor emits :tool/invalid-args when required arg is missing"
    (tools/reg-tool :test/needs-path
                    {:fn          (fn [_args _ctx] {:ok true})
                     :schema      {:type "object"
                                   :properties {:path {:type "string"}}
                                   :required [:path]}
                     :description "needs a path"})
    (executor/execute-effect :tool/invoke
                             {:tool/name :test/needs-path :tool/args {}}
                             {:dispatch! (fn [ev] (swap! dispatched conj ev))})
    (let [r (first @dispatched)]
      (is (= 1 (count @dispatched)))
      (is (= :tool/result (:event/type r)))
      (is (= :error (:tool/status r)))
      (is (= :tool/invalid-args (get-in r [:tool/error :type])))
      (is (string? (get-in r [:tool/error :message]))))))

(deftest tool-invoke-valid-args-succeeds
  (testing "executor runs the tool when all required args are valid"
    (tools/reg-tool :test/with-schema
                    {:fn          (fn [args _ctx] {:got (:path args)})
                     :schema      {:type "object"
                                   :properties {:path {:type "string"}}
                                   :required [:path]}
                     :description "needs a path"})
    (executor/execute-effect :tool/invoke
                             {:tool/name :test/with-schema :tool/args {:path "/tmp/x"}}
                             {:dispatch! (fn [ev] (swap! dispatched conj ev))})
    (let [r (first @dispatched)]
      (is (= :ok (:tool/status r)))
      (is (= {:got "/tmp/x"} (:tool/output r))))))

;; ---------------------------------------------------------------------------
;; Property-based tests: all registered fs tool schemas

(def ^:private fs-schemas
  "Each registered fs tool's {:tool-name :schema} for generative testing."
  (delay
    (->> (tools/registered-tools)
         (map (fn [n] {:tool-name n :schema (:schema (tools/get-tool n))}))
         (filter #(seq (get-in % [:schema :required]))))))

(defn- gen-args-missing-one-required
  "Generator: arg map with exactly one required key removed."
  [schema]
  (let [required (vec (:required schema))
        props    (:properties schema {})]
    (gen/let [drop-kw (gen/elements required)]
      (dissoc (into {}
                    (for [[k {:keys [type]}] props
                          :let [v (case type "string" "val" "boolean" true "val")]]
                      [k v]))
              drop-kw))))

(defn- valid-args-for [schema]
  (into {}
        (for [[k {:keys [type]}] (:properties schema {})
              :let [v (case type "string" "val" "boolean" true "val")]]
          [k v])))

(defspec missing-required-always-fails 50
  (prop/for-all [[schema args]
                 (gen/let [ti   (gen/elements (force fs-schemas))
                            args (gen-args-missing-one-required (:schema ti))]
                   [(:schema ti) args])]
    (string? (tools/validate-args schema args))))

(defspec valid-args-always-passes 50
  (prop/for-all [ti (gen/elements (force fs-schemas))]
    (nil? (tools/validate-args (:schema ti) (valid-args-for (:schema ti))))))
