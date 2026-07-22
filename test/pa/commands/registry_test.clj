(ns pa.commands.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.commands.registry :as registry]))

;; Isolate each test in an empty registry (real commands register globally at
;; load time), then restore the real registry afterward.
(use-fixtures :each
  (fn [f]
    (let [snap (registry/snapshot)]
      (registry/restore! {})
      (try (f) (finally (registry/restore! snap))))))

(def ^:private memory-spec
  {:command     "memory"
   :description "Append a note to permanent memory"
   :arg-spec    {:kind :free-text :required true :placeholder "<text>"}
   :->event     (fn [args] {:event/type :memory/note :text (:text args)})})

(def ^:private markdown-spec
  {:command     "markdown"
   :description "Toggle terminal markdown rendering"
   :arg-spec    {:kind :enum :values ["on" "off"]}
   :->event     (fn [_] {:event/type :settings/set})})

(deftest reg-command-registers-and-returns-name
  (testing "reg-command stores the spec keyed by :command and returns the name"
    (is (= "memory" (registry/reg-command memory-spec)))
    (is (= memory-spec (registry/get-command "memory")))))

(deftest get-command-returns-nil-for-unknown
  (is (nil? (registry/get-command "does-not-exist"))))

(deftest registered-commands-returns-name-set
  (registry/reg-command memory-spec)
  (registry/reg-command markdown-spec)
  (is (= #{"memory" "markdown"} (registry/registered-commands))))

(deftest all-commands-returns-specs
  (registry/reg-command memory-spec)
  (registry/reg-command markdown-spec)
  (is (= #{memory-spec markdown-spec} (set (registry/all-commands)))))

(deftest reg-command-overwrites-existing
  (testing "re-registering a name replaces the prior spec"
    (registry/reg-command memory-spec)
    (registry/reg-command (assoc memory-spec :description "v2"))
    (is (= "v2" (:description (registry/get-command "memory"))))))

(deftest reg-command-rejects-invalid-specs
  (testing "the ::spec check rejects malformed registrations"
    (is (thrown? clojure.lang.ExceptionInfo (registry/reg-command (assoc memory-spec :command :memory)))
        ":command must be a string")
    (is (thrown? clojure.lang.ExceptionInfo (registry/reg-command (assoc memory-spec :command "")))
        ":command must be non-empty")
    (is (thrown? clojure.lang.ExceptionInfo (registry/reg-command (dissoc memory-spec :description)))
        ":description is required")
    (is (thrown? clojure.lang.ExceptionInfo (registry/reg-command (dissoc memory-spec :arg-spec)))
        ":arg-spec is required")
    (is (thrown? clojure.lang.ExceptionInfo (registry/reg-command (dissoc memory-spec :->event)))
        ":->event is required and must be invocable")))

(deftest reg-command-validates-arg-spec-per-kind
  (testing "the arg-spec multi-spec enforces per-kind required keys"
    (is (thrown? clojure.lang.ExceptionInfo
                 (registry/reg-command (assoc markdown-spec :arg-spec {:kind :enum})))
        ":enum requires :values")
    (is (thrown? clojure.lang.ExceptionInfo
                 (registry/reg-command (assoc markdown-spec :arg-spec {:kind :enum :values []})))
        ":enum :values must be non-empty")
    (is (thrown? clojure.lang.ExceptionInfo
                 (registry/reg-command (assoc markdown-spec :arg-spec {:kind :select})))
        ":select requires :options-fn")
    (is (thrown? clojure.lang.ExceptionInfo
                 (registry/reg-command (assoc markdown-spec :arg-spec {:kind :bogus})))
        "unknown :kind is rejected, not thrown on")
    (testing "well-formed arg-specs of every shippable kind are accepted"
      (is (= "c" (registry/reg-command (assoc markdown-spec :command "c"
                                              :arg-spec {:kind :none}))))
      (is (= "c" (registry/reg-command (assoc markdown-spec :command "c"
                                              :arg-spec {:kind :free-text :placeholder "<x>"}))))
      (is (= "c" (registry/reg-command (assoc markdown-spec :command "c"
                                              :arg-spec {:kind :enum :values ["a" "b"]})))))))

(deftest snapshot-restore-round-trips
  (registry/reg-command memory-spec)
  (let [snap (registry/snapshot)]
    (registry/reg-command markdown-spec)
    (is (= #{"memory" "markdown"} (registry/registered-commands)))
    (registry/restore! snap)
    (is (= #{"memory"} (registry/registered-commands)))))

;; ---------------------------------------------------------------------------
;; Usage-hint derivation

(deftest usage-hint-derives-from-arg-spec
  (testing ":enum joins :values with ' | '"
    (is (= "on | off" (registry/usage-hint markdown-spec))))
  (testing ":free-text uses :placeholder"
    (is (= "<text>" (registry/usage-hint memory-spec))))
  (testing ":select uses :placeholder"
    (is (= "<session>"
           (registry/usage-hint {:arg-spec {:kind :select :placeholder "<session>"}}))))
  (testing ":none is blank"
    (is (= "" (registry/usage-hint {:arg-spec {:kind :none}})))))

(deftest usage-hint-explicit-override-wins
  (testing "an explicit :hint overrides the derived value verbatim"
    (is (= "gpt5.4, gpt5-mini, …"
           (registry/usage-hint {:hint     "gpt5.4, gpt5-mini, …"
                                 :arg-spec {:kind :enum :values ["gpt5.4" "gpt5-mini"]}})))
    (is (= "[topic]"
           (registry/usage-hint {:hint     "[topic]"
                                 :arg-spec {:kind :free-text :placeholder "<text>"}})))))
