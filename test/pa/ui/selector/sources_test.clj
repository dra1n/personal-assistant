(ns pa.ui.selector.sources-test
  "The two overlay sources. Behaviour shared with the command overlay lives in
  pa.ui.selector.state-test; this covers what differs about mentions."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pa.commands.registry :as registry]
            [pa.ui.selector.sources :as sources]
            [pa.ui.selector.state :as selector]))

(use-fixtures :each
  (fn [f]
    (let [snap (registry/snapshot)]
      (registry/restore! {})
      (registry/reg-command {:command "help" :description "desc"
                             :arg-spec {:kind :none} :->event (fn [_] {})})
      (try (f) (finally (registry/restore! snap))))))

;; Shapes as a live server-everything session returned them.
(def ^:private resources
  [{:server :everything :uri "demo://resource/static/document/architecture.md"
    :name "architecture.md" :mime-type "text/markdown"
    :description "Static document file exposed from /docs: architecture.md"}
   {:server :everything :uri "demo://resource/static/document/features.md"
    :name "features.md" :mime-type "text/markdown" :description "…features"}
   {:server :other :uri "demo://notes" :name "notes" :mime-type "text/plain"}])

(defn- src [] (sources/mention-source resources))
(defn- labels [rows] (mapv :label rows))

(deftest mention-rows-are-labelled-server-then-uri
  (testing "the label is unique across servers and is what gets matched"
    (is (= ["@everything:demo://resource/static/document/architecture.md"
            "@everything:demo://resource/static/document/features.md"
            "@other:demo://notes"]
           (labels (selector/matches (src) "@"))))))

(deftest mention-rows-carry-display-metadata
  (let [row (first (selector/matches (src) "@arch"))]
    (is (= "text/markdown" (:hint row)))
    (is (= "Static document file exposed from /docs: architecture.md" (:help row)))
    (is (= :resource (:kind row)))
    (is (= "architecture.md" (:name (:value row))) "the value is the resource itself")))

(deftest mentions-match-on-substring-not-prefix
  (testing "labels start with a server name nobody types first"
    (is (= ["@everything:demo://resource/static/document/architecture.md"]
           (labels (selector/matches (src) "@arch"))))
    (is (= ["@other:demo://notes"] (labels (selector/matches (src) "@notes"))))
    (is (= [] (labels (selector/matches (src) "@nothinglikethis"))))))

(deftest mention-matching-ignores-case
  (is (= 1 (count (selector/matches (src) "@ARCH")))))

(deftest a-mention-may-sit-anywhere-in-the-line
  (testing "unlike a command, which owns the whole line"
    (is (some? (selector/token (src) "@arch")))
    (is (some? (selector/token (src) "please read @arch")))
    (is (= "arch" (selector/filter-text (src) "please read @arch")))))

(deftest a-mention-needs-whitespace-in-front-of-it
  (testing "an email address must not open the overlay"
    (is (nil? (selector/token (src) "mail me at ada@example.com")))
    (is (nil? (selector/token (src) "ada@example")))))

(deftest a-completed-mention-closes-the-overlay
  (testing "the token ends at whitespace, so the sentence continues normally"
    (is (nil? (selector/token (src) "read @other:demo://notes and summarize")))))

(deftest the-token-locates-itself-in-the-buffer
  (testing "so the app can replace exactly the mention, not the whole line"
    (let [{:keys [text start end]} (selector/token (src) "please read @arch")]
      (is (= "@arch" text))
      (is (= "please read " (subs "please read @arch" 0 start)))
      (is (= (count "please read @arch") end)))))

;; ---------------------------------------------------------------------------
;; Choosing between the two

(deftest active-source-follows-the-buffer
  (let [model #(hash-map :input % :resources resources)]
    (is (= "/" (:trigger (sources/active (model "/hel")))))
    (is (= "@" (:trigger (sources/active (model "read @arch")))))
    (is (nil? (sources/active (model "just talking"))))))

(deftest without-resources-there-is-no-mention-source
  (testing "a session with no MCP servers behaves exactly as before"
    (is (nil? (sources/active {:input "read @arch" :resources []})))
    (is (nil? (sources/active {:input "read @arch"})))
    (is (= "/" (:trigger (sources/active {:input "/hel"}))))))
