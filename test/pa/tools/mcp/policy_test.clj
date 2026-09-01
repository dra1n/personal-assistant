(ns pa.tools.mcp.policy-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [pa.tools.mcp.policy :as policy]
            [taoensso.timbre :as log]))

;; Malformed-entry cases warn by design; keep the test output readable.
(use-fixtures :each (fn [f] (log/with-min-level :error (f))))

(def ^:private playwright
  {:transport :stdio
   :command   "npx"
   :args      ["-y" "@playwright/mcp@latest"]
   :env       {}
   :enabled?  false})

;; ---------------------------------------------------------------------------
;; Normalization
;; ---------------------------------------------------------------------------

(deftest normalizes-a-full-entry
  (testing "a fully specified server round-trips with the default timeout filled in"
    (is (= {:playwright {:transport          :stdio
                         :command            "npx"
                         :args               ["-y" "@playwright/mcp@latest"]
                         :env                {}
                         :enabled?           false
                         :connect-timeout-ms policy/default-connect-timeout-ms}}
           (:servers (policy/build-policy {:playwright playwright}))))))

(deftest fills-in-optional-keys
  (testing ":transport, :args, :env and :enabled? all have defaults — only :command is required"
    (is (= {:transport          :stdio
            :command            "some-server"
            :args               []
            :env                {}
            :enabled?           true
            :connect-timeout-ms policy/default-connect-timeout-ms}
           (policy/server (policy/build-policy {:bare {:command "some-server"}}) :bare)))))

(deftest enabled-defaults-to-true
  (testing "writing a server block is the opt-in; only an explicit false opts back out"
    (let [pol (policy/build-policy {:on  {:command "a"}
                                    :yes {:command "b" :enabled? true}
                                    :off {:command "c" :enabled? false}})]
      (is (true?  (:enabled? (policy/server pol :on))))
      (is (true?  (:enabled? (policy/server pol :yes))))
      (is (false? (:enabled? (policy/server pol :off)))))))

(deftest string-server-names-are-keywordized
  (is (= [:playwright] (keys (:servers (policy/build-policy {"playwright" playwright}))))))

(deftest args-are-vectorized
  (testing "any sequential of strings normalizes to a vector"
    (is (= ["-y" "pkg"]
           (:args (policy/server (policy/build-policy {:s {:command "npx" :args '("-y" "pkg")}}) :s))))))

(deftest env-keys-are-stringified
  (testing "ProcessBuilder wants String->String, so keyword keys are named"
    (is (= {"TOKEN" "abc" "HOME" "/tmp"}
           (:env (policy/server
                  (policy/build-policy {:s {:command "x" :env {:TOKEN "abc" "HOME" "/tmp"}}})
                  :s))))))

(deftest coercion-is-separable-from-validation
  (testing "coerce-server fills defaults and reshapes; ::server judges the result"
    (let [coerced (policy/coerce-server 15000 {:command "npx" :args '("-y") :env {:TOKEN "abc"}})]
      (is (= {:transport :stdio :command "npx" :args ["-y"] :env {"TOKEN" "abc"}
              :enabled? true :connect-timeout-ms 15000}
             coerced))
      (is (s/valid? ::policy/server coerced))))
  (testing "uncoercible values pass through for the spec to reject with a precise message"
    (is (not (s/valid? ::policy/server (policy/coerce-server 15000 {:command "x" :args "-y"}))))
    (is (not (s/valid? ::policy/server (policy/coerce-server 15000 {:command "x" :env {:BAD 2}}))))
    (is (nil? (policy/coerce-server 15000 "not-a-map")))))

;; ---------------------------------------------------------------------------
;; Connect timeout
;; ---------------------------------------------------------------------------

(deftest connect-timeout-default-and-overrides
  (testing "policy-wide setting applies to every server; per-server wins over it"
    (let [pol (policy/build-policy {:a {:command "a"}
                                    :b {:command "b" :connect-timeout-ms 500}}
                                   30000)]
      (is (= 30000 (:connect-timeout-ms pol)))
      (is (= 30000 (:connect-timeout-ms (policy/server pol :a))))
      (is (= 500   (:connect-timeout-ms (policy/server pol :b))))))
  (testing "no configured timeout falls back to the built-in default"
    (is (= policy/default-connect-timeout-ms
           (:connect-timeout-ms (policy/build-policy {} nil)))))
  (testing "a nonsense policy-wide timeout degrades to the default rather than dropping servers"
    (let [pol (policy/build-policy {:a {:command "a"}} 0)]
      (is (= policy/default-connect-timeout-ms (:connect-timeout-ms pol)))
      (is (= policy/default-connect-timeout-ms (:connect-timeout-ms (policy/server pol :a)))))))

;; ---------------------------------------------------------------------------
;; Malformed entries are dropped, never fatal
;; ---------------------------------------------------------------------------

(deftest malformed-entries-are-dropped
  (testing "each malformed server is dropped individually and the good one survives"
    (doseq [[label bad] {"missing :command"    {:args ["-y"]}
                         "blank :command"      {:command "   "}
                         "non-string :command" {:command 42}
                         "non-map config"      "npx"
                         "non-string args"     {:command "x" :args ["-y" 3]}
                         "non-sequential args" {:command "x" :args "-y"}
                         "non-map env"         {:command "x" :env "TOKEN=1"}
                         "remote transport"    {:command "x" :transport :sse}
                         "non-string env value" {:command "x" :env {:BAD 2}}
                         "non-boolean enabled?" {:command "x" :enabled? "yes"}
                         "negative timeout"    {:command "x" :connect-timeout-ms -1}}]
      (testing label
        (let [pol (policy/build-policy {:bad bad :playwright playwright})]
          (is (nil? (policy/server pol :bad)))
          (is (some? (policy/server pol :playwright))))))))

(deftest unusable-server-name-is-dropped
  (let [pol (policy/build-policy {42 {:command "x"} "" {:command "y"} :ok {:command "z"}})]
    (is (= [:ok] (keys (:servers pol))))))

;; ---------------------------------------------------------------------------
;; Default-deny: absent config yields no capability
;; ---------------------------------------------------------------------------

(deftest absent-config-yields-no-servers
  (testing "a missing :mcp key reaches the component as nil and grants nothing"
    (is (= {} (:servers (policy/build-policy nil))))
    (is (= {} (:servers (policy/build-policy {}))))
    (is (= {} (:servers (policy/build-policy "not-a-map"))))))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(deftest enabled-servers-filters-but-policy-keeps-everything
  (testing "disabled servers stay inspectable in the policy, but are never connected"
    (let [pol (policy/build-policy {:on  {:command "a"}
                                    :off {:command "b" :enabled? false}})]
      (is (= #{:on :off} (set (keys (:servers pol)))))
      (is (= [:on] (keys (policy/enabled-servers pol))))))
  (testing "the shipped playwright entry contributes nothing until opted in"
    (is (empty? (policy/enabled-servers (policy/build-policy {:playwright playwright}))))))

;; ---------------------------------------------------------------------------
;; Integrant component
;; ---------------------------------------------------------------------------

(deftest component-lifecycle
  (testing "init-key builds the policy from its config and halt-key! is a no-op"
    (let [sys (ig/init {:tool.mcp/policy {:servers            {:playwright playwright}
                                          :connect-timeout-ms 1000}})]
      (is (= 1000 (:connect-timeout-ms (:tool.mcp/policy sys))))
      (is (some? (policy/server (:tool.mcp/policy sys) :playwright)))
      (is (nil? (ig/halt! sys)))))
  (testing "a system with no :mcp config still starts, granting no servers"
    (let [sys (ig/init {:tool.mcp/policy {:servers nil :connect-timeout-ms nil}})]
      (is (= {} (:servers (:tool.mcp/policy sys))))
      (ig/halt! sys))))
