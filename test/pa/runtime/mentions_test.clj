(ns pa.runtime.mentions-test
  "@-mentioned resources become attachments on the way to the model: the
  :user/message handler defers to a :mcp/resolve-mentions effect, whose result
  arrives as :mcp/mentions-resolved and carries the turn onward."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pa.llm.prompt :as prompt]
            [pa.runtime.coeffects :as coeffects]
            [pa.runtime.executor :as executor]
            [pa.runtime.handlers]                 ; registers the handlers
            [pa.runtime.registry :as registry]
            [pa.runtime.replay :as replay]
            [pa.tools.mcp.client :as client]
            [taoensso.timbre :as log]))

(defn- handler [event-type] (:fn (registry/get-handler event-type)))

(def ^:private a-resource
  {:server :everything :uri "demo://notes" :name "notes" :mime-type "text/plain"})

(def ^:private db-with-identity
  {:conversation [] :identity {:identity {:front-matter {:name "Aria"} :prose ""}}})

;; ---------------------------------------------------------------------------
;; :user/message — the fork
;; ---------------------------------------------------------------------------

(deftest a-message-without-mentions-calls-the-model-directly
  (testing "the path every message takes in a session with no MCP servers"
    (let [fx ((handler :user/message)
              {:db db-with-identity :mentions []
               :event {:event/type :user/message :content "hello"}})]
      (is (contains? fx :llm/invoke))
      (is (not (contains? fx :mcp/resolve-mentions))))))

(deftest a-message-with-mentions-resolves-first
  (let [fx ((handler :user/message)
            {:db db-with-identity :mentions [a-resource]
             :event {:event/type :user/message :content "read @everything:demo://notes"}})]
    (testing "the model is not called until the resources are in hand"
      (is (not (contains? fx :llm/invoke)))
      (is (= [a-resource] (get-in fx [:mcp/resolve-mentions :resources])))
      (is (= "read @everything:demo://notes" (get-in fx [:mcp/resolve-mentions :content]))))
    (testing "the turn and history are recorded exactly as before"
      (is (= {:role :user :content "read @everything:demo://notes"}
             (last (:conversation (:db fx)))))
      (is (= "read @everything:demo://notes" (:history/text (:history/append fx)))))))

;; ---------------------------------------------------------------------------
;; The mentions coeffect
;; ---------------------------------------------------------------------------

(deftest the-coeffect-identifies-mentions-from-connected-servers
  (let [ctx {:event {:content "read @everything:demo://notes"}
             :system-context
             {:runtime {:mcp/registry {:resources {:everything [{:uri "demo://notes"
                                                                 :name "notes"}]}}}}}
        out ((:before coeffects/mentions-interceptor) ctx)]
    (is (= [{:server :everything :uri "demo://notes" :name "notes"
             :description nil :mime-type nil}]
           (get-in out [:coeffects :mentions])))))

(deftest the-coeffect-is-harmless-with-no-registry
  (testing "a session with no MCP servers wired still dispatches normally"
    (let [out ((:before coeffects/mentions-interceptor)
               {:event {:content "read @everything:demo://notes"} :system-context {}})]
      (is (= [] (get-in out [:coeffects :mentions]))))))

;; ---------------------------------------------------------------------------
;; :mcp/resolve-mentions effect
;; ---------------------------------------------------------------------------

(defn- resolve-mentions!
  "Run the effect and wait for the event it dispatches."
  [registry resources]
  (let [dispatched (promise)]
    (executor/execute-effect :mcp/resolve-mentions
                             {:content "read @everything:demo://notes" :resources resources}
                             {:dispatch! #(deliver dispatched %) :mcp/registry registry})
    (deref dispatched 2000 ::timeout)))

(deftest the-effect-reads-each-resource-and-dispatches-the-result
  (with-redefs [client/read-resource (fn [_ uri] {:contents [{:uri uri :text "notes body"}]})]
    (let [event (resolve-mentions! {:clients {:everything {:name :everything}}} [a-resource])]
      (is (= :mcp/mentions-resolved (:event/type event)))
      (is (= "read @everything:demo://notes" (:content event))
          "the message text rides along so memory retrieval still sees it")
      (is (= "notes body" (:content (first (:attachments event))))))))

(deftest a-failed-read-is-attached-as-an-error-not-dropped
  (testing "the model must not believe it read something it did not"
    (log/with-min-level :error
      (with-redefs [client/read-resource (fn [& _] (throw (ex-info "server is gone"
                                                                   {:type :mcp/closed})))]
        (let [event (resolve-mentions! {:clients {:everything {}}} [a-resource])
              att   (first (:attachments event))]
          (is (= :mcp/mentions-resolved (:event/type event)))
          (is (= "server is gone" (:error att)))
          (is (nil? (:content att))))))))

(deftest a-disconnected-server-is-attached-as-an-error
  (log/with-min-level :error
    (let [event (resolve-mentions! {:clients {}} [a-resource])]
      (is (= "server is not connected" (:error (first (:attachments event))))))))

;; ---------------------------------------------------------------------------
;; :mcp/mentions-resolved — attach, then carry on
;; ---------------------------------------------------------------------------

(def ^:private resolved-event
  {:event/type  :mcp/mentions-resolved
   :content     "read @everything:demo://notes"
   :attachments [(assoc a-resource :content "notes body")]})

(deftest resolved-mentions-attach-to-the-user-turn-and-invoke-the-model
  (let [db (assoc db-with-identity
                  :conversation [{:role :user :content "read @everything:demo://notes"}])
        fx ((handler :mcp/mentions-resolved) {:db db :event resolved-event})]
    (testing "the attachment rides alongside the turn; its text is untouched"
      (let [turn (last (:conversation (:db fx)))]
        (is (= "read @everything:demo://notes" (:content turn)))
        (is (= "notes body" (:content (first (:attachments turn))))))) 
    (testing "and the turn continues to the model"
      (is (contains? fx :llm/invoke))
      (let [user-msg (last (get-in fx [:llm/invoke :messages]))]
        (is (str/includes? (:content user-msg) "<attached-resources>"))
        (is (str/includes? (:content user-msg) "notes body"))
        (is (str/ends-with? (:content user-msg) "read @everything:demo://notes")
            "the person's own words come last")))
    (testing "the event is persisted, which is what makes replay work"
      (is (= resolved-event (:event/store fx))))))

(deftest attachments-land-on-the-most-recent-user-turn
  (let [db (assoc db-with-identity
                  :conversation [{:role :user :content "first"}
                                 {:role :assistant :content "answer"}
                                 {:role :user :content "read @everything:demo://notes"}])
        fx ((handler :mcp/mentions-resolved) {:db db :event resolved-event})
        conv (:conversation (:db fx))]
    (is (nil? (:attachments (first conv))))
    (is (some? (:attachments (last conv))))))

;; ---------------------------------------------------------------------------
;; Replay
;; ---------------------------------------------------------------------------

(deftest replay-rebuilds-attachments-with-no-mcp-server-present
  (testing "the resolved text lives in the event, so the log is self-sufficient"
    (let [events [{:event/type :user/message :content "read @everything:demo://notes"}
                  resolved-event]
          db     (replay/replay db-with-identity events)
          turn   (last (:conversation db))]
      (is (= "read @everything:demo://notes" (:content turn)))
      (is (= "notes body" (:content (first (:attachments turn))))))))

;; ---------------------------------------------------------------------------
;; MCP prompts — the same two hops: render on the server, then carry on
;; ---------------------------------------------------------------------------

(deftest prompt-invoke-defers-to-the-server
  (let [fx ((handler :mcp/prompt-invoke)
            {:db db-with-identity
             :event {:event/type :mcp/prompt-invoke :server :everything
                     :prompt "args-prompt" :arguments {"city" "Berlin"}}})]
    (is (= {:server :everything :prompt "args-prompt" :arguments {"city" "Berlin"}}
           (:mcp/get-prompt fx)))
    (is (not (contains? fx :llm/invoke)) "nothing is sent until the server has rendered it")))

(defn- get-prompt!
  "Run the effect and wait for the event it dispatches."
  [registry]
  (let [dispatched (promise)]
    (executor/execute-effect :mcp/get-prompt
                             {:server :everything :prompt "simple-prompt" :arguments {}}
                             {:dispatch! #(deliver dispatched %) :mcp/registry registry})
    (deref dispatched 2000 ::timeout)))

(deftest the-effect-renders-the-prompt-and-dispatches-its-messages
  (with-redefs [client/get-prompt
                (fn [_ prompt arguments]
                  (is (= "simple-prompt" prompt))
                  (is (= {} arguments))
                  {:messages [{:role "user" :content {:type "text" :text "rendered"}}]})]
    (let [event (get-prompt! {:clients {:everything {}}})]
      (is (= :mcp/prompt-resolved (:event/type event)))
      (is (= [{:role :user :content "rendered"}] (:messages event))))))

(deftest a-prompt-failure-is-reported-where-the-command-was-typed
  (testing "a slash command's failure belongs with usage errors, not in a log line"
    (log/with-min-level :error
      (with-redefs [client/get-prompt (fn [& _] (throw (ex-info "no such prompt" {})))]
        (let [event (get-prompt! {:clients {:everything {}}})]
          (is (= :command/rejected (:event/type event)))
          (is (= "everything.simple-prompt" (:command event)))
          (is (str/includes? (:message event) "no such prompt")))))))

(deftest a-prompt-on-a-disconnected-server-is-rejected
  (log/with-min-level :error
    (let [event (get-prompt! {:clients {}})]
      (is (= :command/rejected (:event/type event)))
      (is (= :mcp/not-connected (:reason event))))))

(deftest a-prompt-that-renders-nothing-is-rejected-rather-than-sent
  (log/with-min-level :error
    (with-redefs [client/get-prompt (fn [& _] {:messages []})]
      (is (= :command/rejected (:event/type (get-prompt! {:clients {:everything {}}})))))))

(def ^:private prompt-resolved-event
  {:event/type :mcp/prompt-resolved :server :everything :prompt "simple-prompt"
   :messages   [{:role :user :content "rendered prompt text"}]})

(deftest resolved-prompt-messages-join-the-conversation-and-invoke-the-model
  (let [fx ((handler :mcp/prompt-resolved) {:db db-with-identity :event prompt-resolved-event})]
    (is (= [{:role :user :content "rendered prompt text"}] (:conversation (:db fx)))
        "the rendered text is a visible turn, not hidden context")
    (is (contains? fx :llm/invoke))
    (is (= prompt-resolved-event (:event/store fx)))))

(deftest replay-rebuilds-a-prompt-turn-without-the-server
  (let [db (replay/replay db-with-identity
                          [{:event/type :mcp/prompt-invoke :server :everything
                            :prompt "simple-prompt" :arguments {}}
                           prompt-resolved-event])]
    (is (= [{:role :user :content "rendered prompt text"}] (:conversation db)))))
