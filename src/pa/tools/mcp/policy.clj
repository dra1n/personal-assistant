(ns pa.tools.mcp.policy
  "MCP server policy — the allowlist of external MCP servers this assistant may
  spawn and talk to.

  This is the mcp tool family's own policy, a sibling to `pa.tools.fs.policy`
  rather than a generalization of it: an allowlist of trusted *servers*,
  config-shaped rather than path-shaped, and sourced from `<PA_HOME>/config.edn`
  rather than a dedicated markdown file. See the spec design-notes for the
  convention.

  Servers are declared under a `:mcp` key in config.edn and reach this component
  through the same `#setting` aero plumbing `resources/system.edn` already uses
  for `:llm/provider` and `:pa.observability/portal`:

    {:mcp {:connect-timeout-ms 15000              ; optional, policy-wide
           :servers {:playwright {:transport :stdio
                                  :command   \"npx\"
                                  :args      [\"-y\" \"@playwright/mcp@latest\"]
                                  :env       {}
                                  :enabled?  false}}}}

  Each entry is coerced into canonical shape (see coerce-server) and then
  checked against ::server, yielding a policy value:

    {:connect-timeout-ms 15000
     :servers {:playwright {:transport :stdio
                            :command \"npx\" :args [...] :env {}
                            :enabled? false :connect-timeout-ms 15000}}}

  Two rules govern this namespace, both from the phase's \"degrade, don't crash\"
  principle:

    - A missing `:mcp` key yields zero servers — absent configuration means no
      capability, the same default-deny spirit as the filesystem policy.
    - An entry that fails ::server is dropped with its spec explanation logged,
      never a startup crash. One bad entry must not cost you the other servers
      or the app.

  Note that `:enabled?` defaults to true: writing a server block is itself the
  opt-in, and `:enabled? false` is how you keep a configured server inert
  without deleting it. Disabled servers are kept in the normalized map (so they
  remain inspectable) and filtered out by `enabled-servers` at connect time."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [integrant.core :as ig]
            [taoensso.timbre :as log]))

(def default-connect-timeout-ms
  "Per-server handshake budget when config names none. Generous, because a
  first run may download the server package before it can answer."
  15000)

(def supported-transports
  "Local stdio only this phase — remote transports (SSE/HTTP) are out of scope
  and deferred to the ideas backlog."
  #{:stdio})

;; ---------------------------------------------------------------------------
;; Specs — the whole description of a valid server entry
;; ---------------------------------------------------------------------------

;; Specs describe the *coerced* entry (see coerce-server), which is why every
;; key is :req-un: defaults are filled in before validation, so a missing key
;; here means the coercion could not supply one.

(s/def ::transport          supported-transports)
(s/def ::command            (s/and string? (complement str/blank?)))
(s/def ::args               (s/coll-of string? :kind vector?))
(s/def ::env                (s/map-of string? string?))
(s/def ::enabled?           boolean?)
(s/def ::connect-timeout-ms pos-int?)

(s/def ::server
  (s/keys :req-un [::transport ::command ::args ::env ::enabled? ::connect-timeout-ms]))

;; ---------------------------------------------------------------------------
;; Coercion — config conveniences into the canonical shape ::server describes
;; ---------------------------------------------------------------------------

(defn- server-name
  "Keywordized server name, or nil if the config key can't be one."
  [k]
  (cond
    (keyword? k)                           k
    (and (string? k) (not (str/blank? k))) (keyword k)))

(defn coerce-server
  "Fill defaults and accept the shapes EDN config naturally produces: any
  sequential of args becomes a vector, keyword env keys are named (ProcessBuilder
  wants String->String). Values that can't be coerced are passed through
  unchanged for ::server to reject with a precise message. Returns nil for a
  non-map entry."
  [default-timeout v]
  (when (map? v)
    (-> {:transport          :stdio
         :args               []
         :env                {}
         ;; Writing a server block is the opt-in; :enabled? false opts back out.
         :enabled?           true
         :connect-timeout-ms default-timeout}
        (merge v)
        (update :args #(cond-> % (sequential? %) vec))
        (update :env  #(cond->> % (map? %) (reduce-kv (fn [m k v]
                                                        (assoc m (cond-> k (keyword? k) name) v))
                                                      {}))))))

;; ---------------------------------------------------------------------------
;; Policy construction
;; ---------------------------------------------------------------------------

(defn- valid-timeout
  "The policy-wide connect timeout, falling back to the built-in default. Unlike
  a server entry, a nonsense value here degrades to the default rather than
  dropping anything — it isn't scoped to one server."
  [v]
  (cond
    (nil? v)                 default-connect-timeout-ms
    (s/valid? ::connect-timeout-ms v) v
    :else (do (log/warn "mcp policy: ignoring invalid :mcp :connect-timeout-ms" {:value v})
              default-connect-timeout-ms)))

(defn- normalize-server
  "Coerce and validate one `name -> config` entry into a `[name server]` pair,
  or nil if it is malformed. Every rejection logs why — a server silently
  missing from the registry is the failure mode these warnings exist to
  prevent."
  [default-timeout k v]
  (let [nm     (server-name k)
        server (coerce-server default-timeout v)]
    (cond
      (nil? nm)
      (do (log/warn "mcp policy: dropping server — name must be a keyword or non-blank string"
                    {:key k})
          nil)

      (nil? server)
      (do (log/warn "mcp policy: dropping server — config must be a map" {:server nm :value v})
          nil)

      (not (s/valid? ::server server))
      (do (log/warn "mcp policy: dropping malformed server config"
                    {:server nm :explain (s/explain-str ::server server)})
          nil)

      :else [nm server])))

(defn build-policy
  "Build a policy value from the raw `:servers` config map and an optional
  policy-wide connect timeout. A nil or non-map `servers` yields no servers."
  ([servers] (build-policy servers nil))
  ([servers connect-timeout-ms]
   (let [default-timeout (valid-timeout connect-timeout-ms)]
     {:connect-timeout-ms default-timeout
      :servers            (if (map? servers)
                            (into {} (keep (fn [[k v]] (normalize-server default-timeout k v)))
                                  servers)
                            (do (when (some? servers)
                                  (log/warn "mcp policy: :mcp :servers is not a map — no servers configured"
                                            {:value servers}))
                                {}))})))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defn enabled-servers
  "The `name -> server` map the registry should actually connect to. Disabled
  servers stay in the policy for inspection but are never spawned."
  [policy]
  (into {} (filter (comp :enabled? val)) (:servers policy)))

(defn server
  "The normalized config for `nm`, or nil if it isn't configured."
  [policy nm]
  (get (:servers policy) nm))

;; ---------------------------------------------------------------------------
;; Integrant component
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :tool.mcp/policy [_ {:keys [servers connect-timeout-ms]}]
  (let [policy (build-policy servers connect-timeout-ms)]
    (log/info "mcp policy loaded"
              {:configured (count (:servers policy))
               :enabled    (count (enabled-servers policy))})
    policy))

(defmethod ig/halt-key! :tool.mcp/policy [_ _])
