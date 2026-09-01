(ns pa.tools.mcp.registry-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [pa.tools.mcp.client :as client]
            [pa.tools.mcp.policy :as policy]
            [pa.tools.mcp.registry :as registry]
            [taoensso.timbre :as log]))

(use-fixtures :each (fn [f] (log/with-min-level :error (f))))

;; ---------------------------------------------------------------------------
;; Fake clients. The transport itself is covered in pa.tools.mcp.client-test
;; against a piped stdio pair; here we stub client/connect so the registry's
;; own logic — concurrency, caching, resilience, shutdown — is what's under
;; test, and no subprocess is ever spawned.
;; ---------------------------------------------------------------------------

(defn- fake-conn [name & {:keys [capabilities] :or {capabilities {:resources {} :prompts {}}}}]
  {:name name :closed? (atom false) :capabilities (atom capabilities)})

(defn- closed? [conn] @(:closed? conn))

(defn- pol
  "A policy over `servers`, each given as name -> extra config."
  [servers]
  (policy/build-policy (update-vals servers #(merge {:command "fake"} %))))

(defmacro ^:private with-fakes
  "Stub the client namespace. `connect` maps server name -> connection (or nil
  to simulate a server that won't connect); listings default to empty."
  [{:keys [connect resources prompts]} & body]
  `(with-redefs [client/connect        (fn [nm# _#] (get ~connect nm#))
                 client/supports?      (fn [conn# cap#] (boolean (get @(:capabilities conn#) cap#)))
                 client/list-resources (fn [conn#] (get ~resources (:name conn#) []))
                 client/list-prompts   (fn [conn#] (get ~prompts (:name conn#) []))
                 client/close!         (fn [conn#] (reset! (:closed? conn#) true) nil)]
     ~@body))

;; ---------------------------------------------------------------------------
;; Connecting
;; ---------------------------------------------------------------------------

(deftest connects-only-enabled-servers
  (let [attempted (atom #{})
        a (fake-conn :a)]
    (with-redefs [client/connect (fn [nm _] (swap! attempted conj nm) (when (= nm :a) a))
                  client/supports? (constantly false)
                  client/close! (constantly nil)]
      (let [entries (registry/connect-all (pol {:a {} :off {:enabled? false}}))]
        (is (= #{:a} @attempted) "a disabled server is never even attempted")
        (is (= #{:a} (set (keys entries))))))))

(defn- captured-logs
  "Run f, collecting the messages it logs."
  [f]
  (let [msgs (atom [])]
    (log/with-merged-config
      {:min-level :debug
       :appenders {:capture {:enabled? true :fn #(swap! msgs conj (force (:msg_ %)))}
                   :println {:enabled? false}
                   :file    {:enabled? false}}}
      (f))
    @msgs))

(deftest a-server-that-fails-fast-is-not-reported-as-a-timeout
  (testing "an unstartable server is skipped for the reason the client logged, not a timeout"
    (with-fakes {:connect {:bad nil}}
      (let [msgs (captured-logs #(registry/connect-all (pol {:bad {}})))]
        (is (not-any? #(str/includes? % "did not finish connecting") msgs)
            (str "misreported as a timeout: " (pr-str msgs)))))))

(deftest a-server-that-fails-to-connect-does-not-affect-the-others
  (with-fakes {:connect {:good (fake-conn :good) :bad nil}}
    (let [entries (registry/connect-all (pol {:good {} :bad {}}))]
      (is (= #{:good} (set (keys entries))))
      (is (some? (get-in entries [:good :client]))))))

(deftest servers-connect-concurrently
  (testing "startup costs the slowest server's budget, not the sum of all of them"
    (let [conns (into {} (map (juxt identity fake-conn)) [:a :b :c :d])]
      (with-redefs [client/connect (fn [nm _] (Thread/sleep 300) (get conns nm))
                    client/supports? (constantly false)
                    client/close! (constantly nil)]
        (let [start   (System/currentTimeMillis)
              entries (registry/connect-all (pol {:a {} :b {} :c {} :d {}}))
              elapsed (- (System/currentTimeMillis) start)]
          (is (= 4 (count entries)))
          (is (< elapsed 900) (str "took " elapsed "ms — serial would be ~1200ms")))))))

(deftest a-server-that-overruns-its-budget-is-skipped-and-reaped
  (testing "an abandoned connect never leaves an orphaned client behind"
    (let [late (fake-conn :late)]
      ;; The real grace is seconds on top of the server's own timeout; shrink it
      ;; so the overrun path is reachable without a multi-second test.
      (with-redefs [registry/connect-grace-ms 50
                    client/connect (fn [_ _] (Thread/sleep 500) late)
                    client/supports? (constantly false)
                    client/close! (fn [conn] (reset! (:closed? conn) true) nil)]
        (let [entries (registry/connect-all
                       (policy/build-policy {:late {:command "fake" :connect-timeout-ms 1}}))]
          (is (empty? entries) "the overrunning server is not part of the registry")
          ;; the late arrival is disconnected in the background
          (Thread/sleep 900)
          (is (closed? late) "and is disconnected once it finally answers"))))))

;; ---------------------------------------------------------------------------
;; Cached listings
;; ---------------------------------------------------------------------------

(deftest caches-resource-and-prompt-listings
  (let [res    [{:uri "file:///a.md" :name "a"}]
        prompt [{:name "summarize"}]]
    (with-fakes {:connect   {:s (fake-conn :s)}
                 :resources {:s res}
                 :prompts   {:s prompt}}
      (let [entries (registry/connect-all (pol {:s {}}))]
        (is (= res (get-in entries [:s :resources])))
        (is (= prompt (get-in entries [:s :prompts])))))))

(deftest listings-are-skipped-when-the-server-declared-no-such-capability
  (with-fakes {:connect   {:s (fake-conn :s :capabilities {:tools {}})}
               :resources {:s [{:uri "file:///a.md"}]}
               :prompts   {:s [{:name "p"}]}}
    (let [entries (registry/connect-all (pol {:s {}}))]
      (is (= [] (get-in entries [:s :resources])))
      (is (= [] (get-in entries [:s :prompts]))))))

(deftest a-failing-listing-does-not-cost-us-the-server
  (testing "a broken resources/list still leaves the server connected for its tools"
    (with-redefs [client/connect        (fn [nm _] (fake-conn nm))
                  client/supports?      (constantly true)
                  client/list-resources (fn [_] (throw (ex-info "boom" {})))
                  client/list-prompts   (fn [_] [{:name "p"}])
                  client/close!         (constantly nil)]
      (let [entries (registry/connect-all (pol {:s {}}))]
        (is (some? (get-in entries [:s :client])))
        (is (= [] (get-in entries [:s :resources])))
        (is (= [{:name "p"}] (get-in entries [:s :prompts])))))))

;; ---------------------------------------------------------------------------
;; Component lifecycle
;; ---------------------------------------------------------------------------

(defn- start [servers fakes]
  (with-redefs [client/connect        (fn [nm _] (get (:connect fakes) nm))
                client/supports?      (fn [conn cap] (boolean (get @(:capabilities conn) cap)))
                client/list-resources (fn [conn] (get (:resources fakes) (:name conn) []))
                client/list-prompts   (fn [conn] (get (:prompts fakes) (:name conn) []))
                client/close!         (fn [conn] (reset! (:closed? conn) true) nil)]
    (ig/init {:tool.mcp/policy {:servers (update-vals servers (fn [c] (merge {:command "fake"} c)))}
              :mcp/registry    {:policy (ig/ref :tool.mcp/policy)}})))

(deftest init-exposes-clients-and-listings
  (let [a (fake-conn :a)
        sys (start {:a {}} {:connect   {:a a}
                            :resources {:a [{:uri "file:///a.md"}]}
                            :prompts   {:a [{:name "p"}]}})
        reg (:mcp/registry sys)]
    (is (= #{:a} (registry/connected-servers reg)))
    (is (= a (registry/client reg :a)))
    (is (nil? (registry/client reg :nope)))
    (testing "listings are tagged with their server, for provenance downstream"
      (is (= [{:uri "file:///a.md" :server :a}] (registry/all-resources reg)))
      (is (= [{:name "p" :server :a}] (registry/all-prompts reg))))
    (with-redefs [client/close! (fn [conn] (reset! (:closed? conn) true) nil)]
      (ig/halt! sys))))

(deftest halt-disconnects-every-client
  (let [a (fake-conn :a)
        b (fake-conn :b)
        sys (start {:a {} :b {}} {:connect {:a a :b b}})]
    (is (= #{:a :b} (registry/connected-servers (:mcp/registry sys))))
    (with-redefs [client/close! (fn [conn] (reset! (:closed? conn) true) nil)]
      (ig/halt! sys))
    (is (closed? a))
    (is (closed? b))))

(deftest halt-disconnects-the-rest-after-a-partial-startup
  (testing "a server that never connected does not stop the others being closed"
    (let [good (fake-conn :good)
          sys  (start {:good {} :bad {}} {:connect {:good good :bad nil}})]
      (with-redefs [client/close! (fn [conn] (reset! (:closed? conn) true) nil)]
        (ig/halt! sys))
      (is (closed? good)))))

(deftest halt-continues-when-a-disconnect-throws
  (let [a (fake-conn :a)
        b (fake-conn :b)
        sys (start {:a {} :b {}} {:connect {:a a :b b}})]
    (with-redefs [client/close! (fn [conn]
                                  (when (= :a (:name conn)) (throw (ex-info "stuck" {})))
                                  (reset! (:closed? conn) true) nil)]
      (is (nil? (ig/halt! sys))))
    (is (closed? b) "b is still disconnected after a's close threw")))

(deftest an-empty-policy-starts-and-halts-cleanly
  (let [sys (ig/init {:tool.mcp/policy {:servers nil}
                      :mcp/registry    {:policy (ig/ref :tool.mcp/policy)}})]
    (is (= #{} (registry/connected-servers (:mcp/registry sys))))
    (is (nil? (ig/halt! sys)))))
