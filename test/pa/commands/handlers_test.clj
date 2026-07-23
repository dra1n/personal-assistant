(ns pa.commands.handlers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.commands.handlers :as handlers]        ; registers the command handlers
            [pa.commands.registry :as commands]
            [pa.runtime.registry :as registry]
            [pa.state.queries :as queries]))

;; Isolate the command registry so help enumeration is deterministic; the
;; handler registry (global) keeps the real registrations from the ns load.
(use-fixtures :each
  (fn [f]
    (let [snap (commands/snapshot)]
      (commands/restore! {})
      (commands/reg-command {:command "alpha" :description "First"
                             :arg-spec {:kind :none} :->event (fn [_] {})})
      (commands/reg-command {:command "beta" :description "Second"
                             :arg-spec {:kind :none} :->event (fn [_] {})})
      (try (f) (finally (commands/restore! snap))))))

(defn- handler [event-type]
  (:fn (registry/get-handler event-type)))

;; ---------------------------------------------------------------------------
;; :command/help

(deftest help-text-enumerates-exactly-registered-commands
  (testing "help-text lists every registered command, sorted, and nothing else"
    (is (= "Available commands:\n/alpha — First\n/beta — Second" (handlers/help-text)))))

(deftest help-handler-appends-a-system-turn
  (testing ":command/help appends the listing to the conversation as a system turn"
    (let [fx   ((handler :command/help) {:db {:conversation []}})
          turn (last (:conversation (:db fx)))]
      (is (= :system (:role turn)))
      (is (= (handlers/help-text) (:content turn))))))

;; ---------------------------------------------------------------------------
;; :memory/note

(deftest memory-note-emits-wisdom-merge
  (testing ":memory/note appends the verbatim text via :wisdom/merge"
    (is (= {:wisdom/merge ["buy  milk"]}
           ((handler :memory/note) {:event {:event/type :memory/note :text "buy  milk"}})))))

;; ---------------------------------------------------------------------------
;; :conversation/clear

(deftest conversation-clear-resets-the-conversation
  (testing ":conversation/clear empties the active conversation via :db"
    (let [fx ((handler :conversation/clear)
              {:db {:conversation [{:role :user :content "hi"}
                                   {:role :assistant :content "hello"}]}})]
      (is (= [] (:conversation (:db fx)))))))

;; ---------------------------------------------------------------------------
;; :command/rejected

(deftest command-rejected-adds-a-notification
  (testing ":command/rejected surfaces the usage message as a UI notification"
    (let [fx   ((handler :command/rejected)
                {:db {} :event {:event/type :command/rejected
                                :event/id "id-1" :message "/markdown: bad value"}})
          note (last (queries/notifications (:db fx)))]
      (is (= "id-1" (:id note)))
      (is (= "/markdown: bad value" (get-in note [:payload :text]))))))
