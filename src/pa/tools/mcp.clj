(ns pa.tools.mcp
  "MCP tools — each connected server's `tools/list`, projected into the ordinary
  tool registry.

  There is no separate execution path. A registered MCP tool is an ordinary
  registry entry whose `:fn` proxies to `tools/call`, so it is invoked through
  `:tool/invoke`, produces a `:tool/result`, and is advertised to the LLM by
  `registry/advertise` exactly like a filesystem or network tool — with the
  same logging, dry-run, and replay guarantees.

  Names are namespaced by server (`:mcp.playwright/browser_navigate`) so two
  servers cannot collide and provenance is visible wherever a tool name is.

  The server's `inputSchema` is used verbatim as the tool's `:schema`: it is
  JSON Schema, `pa.tools.registry/validate-args` already speaks JSON-Schema
  -shaped EDN, and the transport hands it over keywordized. No translation
  layer means nothing to drift out of sync with the spec.

  Failures arrive by two different channels, and both must become an error
  result. A protocol-level failure throws out of the client; a *tool-level*
  failure comes back as a perfectly successful response carrying
  `:isError true`. Both are rethrown as `{:type :mcp/tool-error}` with an
  `:mcp/cause` naming which channel it came from, so the executor turns them
  into `:tool/status :error` indistinguishable in shape from a native tool's
  failure."
  (:require [clojure.string :as str]
            [pa.tools.mcp.client :as client]
            [pa.tools.registry :as tools]
            [taoensso.timbre :as log]))

(defn tool-key
  "The registry name for `tool` on `server`: `:mcp.<server>/<tool>`."
  [server tool]
  (keyword (str "mcp." (name server)) tool))

(defn- text-content
  "The text parts of an MCP content vector, joined — what a person or a model
  reads out of a result."
  [content]
  (->> content (keep :text) (str/join "\n")))

(defn- tool-error
  [server tool cause message extra cause-ex]
  (ex-info message
           (merge {:type       :mcp/tool-error
                   :mcp/cause  cause
                   :mcp/server server
                   :mcp/tool   tool}
                  extra)
           cause-ex))

(defn- proxy-fn
  "The tool `:fn`: call the server and let both failure channels throw.

  Closes over the connection rather than reaching for it through ctx: the
  connection is stable for the session, and these registrations are removed
  when it closes, so there is no window in which the closure outlives it."
  [server conn tool]
  (fn [args _ctx]
    (let [result (try
                   (client/call-tool conn tool args)
                   (catch clojure.lang.ExceptionInfo e
                     (throw (tool-error server tool
                                        (get (ex-data e) :type :mcp/unknown)
                                        (ex-message e) {} e))))]
      (if (:isError result)
        (throw (tool-error server tool :mcp/is-error
                           (or (not-empty (text-content (:content result)))
                               (str "mcp tool failed: " tool))
                           {:mcp/content (:content result)} nil))
        result))))

(defn register-tools!
  "Register every tool in `tool-list` under `server`. Returns the registered
  names, which the caller keeps so it can unregister them on disconnect."
  [server conn tool-list]
  (into []
        (keep (fn [{tool :name :keys [description inputSchema]}]
                (if (and (string? tool) (not (str/blank? tool)))
                  (tools/reg-tool
                   (tool-key server tool)
                   {:fn          (proxy-fn server conn tool)
                    ;; JSON Schema, used as-is (see the ns docstring). An
                    ;; absent schema means "no declared arguments", which the
                    ;; empty schema expresses to validate-args.
                    :schema      (or inputSchema {})
                    :description (or description
                                     (str tool " (" (name server) " MCP server)"))})
                  (do (log/warn "mcp: skipping tool with no usable name"
                                {:server server :tool tool})
                      nil))))
        tool-list))

(defn unregister-tools!
  "Drop `tool-names` from the registry — called when a server disconnects, so
  no tool outlives the connection it proxies to."
  [tool-names]
  (run! tools/unreg-tool tool-names)
  nil)
