(ns pa.commands.args-test
  (:require [clojure.test :refer [deftest is testing]]
            [pa.commands.args :as args]))

;; Resolution is pure over the spec map, so tests pass specs directly rather
;; than registering them.

(def ^:private none-spec
  {:command  "clear"
   :arg-spec {:kind :none}})

(def ^:private free-text-required
  {:command  "memory"
   :arg-spec {:kind :free-text :required true :placeholder "<text>"}})

(def ^:private free-text-optional
  {:command  "reflect"
   :arg-spec {:kind :free-text :required false :placeholder "[topic]"}})

(def ^:private enum-spec
  {:command  "markdown"
   :arg-spec {:kind :enum :values ["on" "off"]}})

;; ---------------------------------------------------------------------------
;; :none

(deftest none-resolves-empty-args
  (is (= {:args {}} (args/resolve none-spec "")))
  (is (= {:args {}} (args/resolve none-spec "   "))
      "blank (whitespace-only) is treated as no argument"))

(deftest none-rejects-surplus
  (let [{:keys [error]} (args/resolve none-spec "extra stuff")]
    (is (= :surplus-argument (:reason error)))
    (is (= "clear" (:command error)))
    (is (re-find #"takes no arguments" (:message error)))))

;; ---------------------------------------------------------------------------
;; :free-text

(deftest free-text-preserves-internal-spacing
  (testing "the rest of the line is verbatim; internal spacing is not collapsed"
    (is (= {:args {:text "foo  bar"}} (args/resolve free-text-required "foo  bar")))))

(deftest free-text-required-missing-is-usage-error
  (let [{:keys [error]} (args/resolve free-text-required "")]
    (is (= :missing-argument (:reason error)))
    (is (= "memory" (:command error)))
    (is (re-find #"<text>" (:message error))))
  (is (:error (args/resolve free-text-required "   "))
      "whitespace-only counts as missing"))

(deftest free-text-optional-allows-blank
  (is (= {:args {:text ""}} (args/resolve free-text-optional "")))
  (is (= {:args {:text "a topic"}} (args/resolve free-text-optional "a topic"))))

;; ---------------------------------------------------------------------------
;; :enum

(deftest enum-accepts-allowed-tokens
  (is (= {:args {:token "on"}} (args/resolve enum-spec "on")))
  (is (= {:args {:token "off"}} (args/resolve enum-spec "off")))
  (is (= {:args {:token "on"}} (args/resolve enum-spec "  on  "))
      "surrounding whitespace around the token is trimmed"))

(deftest enum-rejects-unknown-value
  (let [{:keys [error]} (args/resolve enum-spec "maybe")]
    (is (= :unknown-value (:reason error)))
    (is (= "markdown" (:command error)))
    (is (= "on | off" (:hint error)))
    (is (re-find #"maybe" (:message error)))))

(deftest enum-blank-is-missing
  (is (= :missing-argument (:reason (:error (args/resolve enum-spec "")))))
  (is (= :missing-argument (:reason (:error (args/resolve enum-spec "   "))))))

(deftest enum-multiword-rejected-as-surplus
  (testing "a multi-word arg is not a member, so surplus is rejected as unknown"
    (is (= :unknown-value (:reason (:error (args/resolve enum-spec "on off")))))))

;; ---------------------------------------------------------------------------
;; :select passes the picker's completed value through

(deftest select-passes-value-through
  (testing ":select trusts the picker's completed value and returns it verbatim"
    (is (= {:args {:value "session-42"}}
           (args/resolve {:command  "load"
                          :arg-spec {:kind :select :options-fn (fn [_] [])}}
                         "session-42")))))

;; ---------------------------------------------------------------------------
;; unknown kind is a programmer error, not user input

(deftest unknown-kind-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (args/resolve {:command "x" :arg-spec {:kind :bogus}} "y"))))
