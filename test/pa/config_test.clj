(ns pa.config-test
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
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

;; ---------------------------------------------------------------------------
;; load-settings — <PA_HOME>/config.edn, read through aero
;;
;; The user's settings file gets the same readers system.edn does, so a secret
;; can live in the environment instead of in the file. These exercise the
;; private var directly: it is the seam where the file becomes a map.
;; ---------------------------------------------------------------------------

(def ^:private load-settings #'config/load-settings)

(defn- with-settings-file
  "Write `content` to a temp config.edn and hand its path to `f`."
  [content f]
  (let [dir  (java.nio.file.Files/createTempDirectory
              "pa-settings" (into-array java.nio.file.attribute.FileAttribute []))
        file (io/file (.toFile dir) "config.edn")]
    (try
      (when content (spit file content))
      (f (str file))
      (finally
        (.delete file)
        (.delete (.toFile dir))))))

(deftest load-settings-reads-plain-edn
  (testing "an ordinary settings map is unchanged by going through aero"
    (with-settings-file "{:llm {:openai {:model \"gpt-5.4\"}} :settings {:markdown true}}"
      (fn [path]
        (is (= {:llm {:openai {:model "gpt-5.4"}} :settings {:markdown true}}
               (load-settings path {})))))))

(deftest load-settings-resolves-env-tags
  (testing "#env in config.edn resolves from the real environment"
    (with-settings-file "{:token #env HOME}"
      (fn [path]
        (is (= {:token (System/getenv "HOME")} (load-settings path {}))))))
  (testing "#env falls back to .env, exactly as it does in system.edn"
    (with-settings-file "{:token #env PA_TEST_UNSET_VAR}"
      (fn [path]
        (is (= {:token "from-dotenv"}
               (load-settings path {"PA_TEST_UNSET_VAR" "from-dotenv"}))))))
  (testing "unset everywhere is nil, so #or defaults still apply"
    (with-settings-file "{:token #or [#env PA_TEST_UNSET_VAR \"fallback\"]}"
      (fn [path]
        (is (= {:token "fallback"} (load-settings path {})))))))

(deftest load-settings-absent-or-empty-is-no-settings
  (testing "a file that was never created"
    (with-settings-file nil
      (fn [path] (is (= {} (load-settings path {}))))))
  (testing "an empty file"
    (with-settings-file "" (fn [path] (is (= {} (load-settings path {}))))))
  (testing "the freshly bootstrapped template, which is entirely comments"
    (with-settings-file ";; nothing uncommented yet\n"
      (fn [path] (is (= {} (load-settings path {})))))))

(deftest load-settings-malformed-is-fatal-and-names-the-file
  (testing "unbalanced EDN"
    (with-settings-file "{:a 1"
      (fn [path]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Malformed user settings file"
                              (load-settings path {}))))))
  (testing "an unknown reader tag names itself in the cause"
    (with-settings-file "{:a #bogus X}"
      (fn [path]
        (let [e (is (thrown? clojure.lang.ExceptionInfo (load-settings path {})))]
          (is (re-find #"No reader for tag bogus" (.getMessage (.getCause ^Exception e)))))))))

(deftest setting-tag-inside-the-settings-file-is-rejected
  (testing "#setting resolves into config.edn, so using it there is circular
            and fails loudly rather than reading as nil"
    (with-settings-file "{:a #setting [:llm :model]}"
      (fn [path]
        (let [e (is (thrown? clojure.lang.ExceptionInfo (load-settings path {})))]
          (is (re-find #"not available inside config.edn"
                       (.getMessage (.getCause ^Exception e)))))))))
