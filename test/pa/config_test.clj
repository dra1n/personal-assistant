(ns pa.config-test
  (:require [aero.core :as aero]
            [clojure.test :refer [deftest is testing]]
            [pa.config :as config])
  (:import [java.io StringReader]))

(deftest parse-dotenv-test
  (testing "KEY=VALUE lines, comments, blanks, export prefix, quotes"
    (is (= {"A" "1" "B" "two words" "C" "sk-x" "D" "spaced" "E" "a=b"}
           (config/parse-dotenv
            (str "# comment\n"
                 "A=1\n"
                 "\n"
                 "B=\"two words\"\n"
                 "export C=sk-x\n"
                 "D = 'spaced'\n"
                 "E=a=b\n"
                 "malformed-line\n"))))
    (is (= {} (config/parse-dotenv "")))))

(defn- read-config-str
  ([s dotenv] (read-config-str s dotenv {}))
  ([s dotenv settings]
   (aero/read-config (StringReader. s) {:dotenv dotenv :settings settings})))

(deftest env-reader-precedence-test
  (testing ".env fills in vars missing from the real environment"
    (is (= {:x "from-dotenv"}
           (read-config-str "{:x #env PA_TEST_UNSET_VAR}"
                            {"PA_TEST_UNSET_VAR" "from-dotenv"}))))
  (testing "the real environment wins over .env"
    (is (= {:x (System/getenv "HOME")}
           (read-config-str "{:x #env HOME}" {"HOME" "shadowed"}))))
  (testing "unset everywhere resolves to nil, so #or defaults still apply"
    (is (= {:x "fallback"}
           (read-config-str "{:x #or [#env PA_TEST_UNSET_VAR \"fallback\"]}" {})))))

(def ^:private setting-chain
  "{:x #or [#env PA_TEST_UNSET_VAR #setting [:llm :model] \"default\"]}")

(deftest setting-reader-test
  (testing "#setting resolves a get-in path into the user settings map"
    (is (= {:x "from-settings"}
           (read-config-str "{:x #setting [:llm :model]}"
                            {} {:llm {:model "from-settings"}}))))
  (testing "in the standard #or chain: .env beats settings, settings beat the default"
    (is (= {:x "from-dotenv"}
           (read-config-str setting-chain
                            {"PA_TEST_UNSET_VAR" "from-dotenv"}
                            {:llm {:model "from-settings"}})))
    (is (= {:x "from-settings"}
           (read-config-str setting-chain {} {:llm {:model "from-settings"}})))
    (is (= {:x "default"}
           (read-config-str setting-chain {} {})))))
