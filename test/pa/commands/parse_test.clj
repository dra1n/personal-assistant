(ns pa.commands.parse-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.commands.parse :as parse]
            [pa.commands.registry :as registry]))

;; Register a small fixed command set into an isolated registry for each test.
(use-fixtures :each
  (fn [f]
    (let [snap (registry/snapshot)]
      (registry/restore! {})
      (registry/reg-command {:command     "memory"
                             :description "Append a note to permanent memory"
                             :arg-spec    {:kind :free-text}
                             :->event     (fn [_] {:event/type :memory/note})})
      (registry/reg-command {:command     "help"
                             :description "List the available slash commands"
                             :arg-spec    {:kind :none}
                             :->event     (fn [_] {:event/type :command/help})})
      (try (f) (finally (registry/restore! snap))))))

(deftest parses-free-text-args-verbatim
  (testing "the command splits off; :raw-args keeps internal spacing verbatim"
    (is (= {:command "memory" :raw-args "foo  bar"}
           (parse/parse "/memory foo  bar")))))

(deftest parses-none-command
  (is (= {:command "help" :raw-args ""} (parse/parse "/help"))))

(deftest trailing-space-yields-empty-raw-args
  (is (= {:command "help" :raw-args ""} (parse/parse "/help "))))

(deftest non-command-line-returns-nil
  (testing "an ordinary message without a leading slash is not a command"
    (is (nil? (parse/parse "hello")))
    (is (nil? (parse/parse "what is /memory about")))))

(deftest unknown-command-returns-nil
  (testing "a leading slash naming an unregistered command is not a command"
    (is (nil? (parse/parse "/unknown")))
    (is (nil? (parse/parse "/unknown some args")))))

(deftest bare-slash-returns-nil
  (testing "a bare slash (with or without trailing args) is an ordinary message"
    (is (nil? (parse/parse "/")))
    (is (nil? (parse/parse "/ memory")))))

(deftest leading-whitespace-returns-nil
  (testing "leading whitespace means the line does not start with a slash"
    (is (nil? (parse/parse "  /help")))
    (is (nil? (parse/parse "\t/memory foo")))))

(deftest non-string-returns-nil
  (is (nil? (parse/parse nil))))

(deftest multiline-body-captured-whole
  (testing "a newline in the argument body is preserved in :raw-args"
    (is (= {:command "memory" :raw-args "line one\nline two"}
           (parse/parse "/memory line one\nline two")))))
