(ns pa.tools.mcp-prompts-test
  "MCP prompts as slash commands. Prompt fixtures are the shapes a live
  @modelcontextprotocol/server-everything 2.0.0 session returned — including
  that :required is simply absent on an optional argument, and that a message's
  :content is a block rather than a string."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.commands.args :as args]
            [pa.commands.registry :as commands]
            [pa.tools.mcp :as mcp]
            [taoensso.timbre :as log]))

(use-fixtures :each
  (fn [f]
    (let [snap (commands/snapshot)]
      (log/with-min-level :error
        (try (f) (finally (commands/restore! snap)))))))

(def ^:private simple-prompt
  {:name "simple-prompt" :title "Simple Prompt" :description "A prompt with no arguments"})

(def ^:private args-prompt
  {:name "args-prompt" :title "Arguments Prompt"
   :description "A prompt with two arguments, one required and one optional"
   :arguments [{:name "city" :description "Name of the city" :required true}
               {:name "state" :required false}]})

(def ^:private two-required
  {:name "completable-prompt" :description "First argument narrows the second."
   :arguments [{:name "department" :required true}
               {:name "name" :required true}]})

;; ---------------------------------------------------------------------------
;; Argument mapping
;; ---------------------------------------------------------------------------

(deftest a-prompt-with-no-arguments-becomes-a-bare-command
  (let [spec (mcp/prompt->command :everything simple-prompt)]
    (is (= "everything.simple-prompt" (:command spec)))
    (is (= {:kind :none} (:arg-spec spec)))
    (is (= "A prompt with no arguments" (:description spec)))
    (is (= {:event/type :mcp/prompt-invoke :server :everything
            :prompt "simple-prompt" :arguments {}}
           ((:->event spec) {})))))

(deftest one-required-argument-becomes-free-text
  (testing "optional arguments are not counted — the prompt is usable with one value"
    (let [spec (mcp/prompt->command :everything args-prompt)]
      (is (= :free-text (get-in spec [:arg-spec :kind])))
      (is (true? (get-in spec [:arg-spec :required])))
      (is (= "<city>" (get-in spec [:arg-spec :placeholder])))
      (testing "and the value is passed under the argument's own name"
        (is (= {:event/type :mcp/prompt-invoke :server :everything
                :prompt "args-prompt" :arguments {"city" "Berlin"}}
               ((:->event spec) {:text "Berlin"})))))))

(deftest two-required-arguments-are-a-documented-gap
  (is (nil? (mcp/prompt->command :everything two-required))))

(deftest a-nameless-prompt-is-skipped
  (is (nil? (mcp/prompt->command :everything {:description "no name"}))))

(deftest a-prompt-without-a-description-still-registers
  (let [spec (mcp/prompt->command :everything {:name "bare"})]
    (is (string? (:description spec)))
    (is (re-find #"everything" (:description spec)))))

(deftest a-title-stands-in-for-a-missing-description
  (is (= "Simple Prompt"
         (:description (mcp/prompt->command :everything (dissoc simple-prompt :description))))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(deftest registering-adds-the-supported-prompts-as-commands
  (let [registered (mcp/register-prompts! :everything [simple-prompt args-prompt two-required])]
    (is (= ["everything.simple-prompt" "everything.args-prompt"] registered))
    (is (some? (commands/get-command "everything.simple-prompt")))
    (is (nil? (commands/get-command "everything.completable-prompt"))
        "the unsupported one is skipped, not registered broken")))

(deftest prompt-commands-resolve-their-arguments-like-any-command
  (testing "they go through the ordinary parse/resolve path"
    (mcp/register-prompts! :everything [args-prompt])
    (let [spec (commands/get-command "everything.args-prompt")]
      (is (= {:args {:text "Berlin"}} (args/resolve spec "Berlin")))
      (is (= :missing-argument (get-in (args/resolve spec "") [:error :reason]))))))

(deftest two-servers-with-the-same-prompt-do-not-collide
  (mcp/register-prompts! :one [simple-prompt])
  (mcp/register-prompts! :two [simple-prompt])
  (is (= #{"one.simple-prompt" "two.simple-prompt"}
         (set (filter #(re-find #"simple-prompt" %) (commands/registered-commands))))))

(deftest unregistering-withdraws-them
  (let [registered (mcp/register-prompts! :everything [simple-prompt args-prompt])]
    (mcp/unregister-prompts! registered)
    (is (nil? (commands/get-command "everything.simple-prompt")))
    (is (nil? (commands/get-command "everything.args-prompt")))))

;; ---------------------------------------------------------------------------
;; prompts/get → conversation turns
;; ---------------------------------------------------------------------------

(deftest prompt-messages-flatten-content-blocks
  (testing ":content is a block, not a string"
    (is (= [{:role :user :content "This is a simple prompt without arguments."}]
           (mcp/prompt-messages
            {:messages [{:role "user"
                         :content {:type "text"
                                   :text "This is a simple prompt without arguments."}}]})))))

(deftest prompt-messages-keep-roles
  (is (= [:user :assistant]
         (mapv :role (mcp/prompt-messages
                      {:messages [{:role "user" :content {:type "text" :text "q"}}
                                  {:role "assistant" :content {:type "text" :text "a"}}]})))))

(deftest prompt-messages-accept-a-plain-string-or-a-list-of-blocks
  (is (= [{:role :user :content "plain"}]
         (mcp/prompt-messages {:messages [{:role "user" :content "plain"}]})))
  (is (= [{:role :user :content "one\ntwo"}]
         (mcp/prompt-messages {:messages [{:role "user"
                                           :content [{:type "text" :text "one"}
                                                     {:type "text" :text "two"}]}]}))))

(deftest non-text-content-is-dropped
  (testing "an image has nowhere to go in a text conversation"
    (is (= [{:role :user :content "keep"}]
           (mcp/prompt-messages
            {:messages [{:role "user" :content {:type "image" :data "aGk="}}
                        {:role "user" :content {:type "text" :text "keep"}}]})))))
