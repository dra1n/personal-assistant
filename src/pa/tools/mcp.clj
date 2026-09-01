(ns pa.tools.mcp
  "MCP tools — each connected server's `tools/list`, projected into the ordinary
  tool registry.

  There is no separate execution path. A registered MCP tool is an ordinary
  registry entry whose `:fn` proxies to `tools/call`, so it is invoked through
  `:tool/invoke`, produces a `:tool/result`, and is advertised to the LLM by
  `registry/advertise` exactly like a filesystem or network tool — with the
  same logging, dry-run, and replay guarantees.

  Names are namespaced by server (`:mcp-playwright/browser_navigate`) so two
  servers cannot collide and provenance is visible wherever a tool name is.
  The namespace is `mcp-<server>`, not `mcp.<server>`, because tool names reach
  provider APIs through `encode-name`, and OpenAI accepts only
  `[A-Za-z0-9_-]` there — a dot 400s the entire request, not just that tool.
  For the same reason a server tool whose own name breaks that rule is skipped
  rather than registered: one unusable name would poison every LLM call.

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
            [pa.commands.registry :as commands]
            [pa.tools.registry :as tools]
            [taoensso.timbre :as log]))

(def provider-safe-name
  "Tool names travel to provider APIs, which are stricter than keywords are.
  OpenAI's function names must match [A-Za-z0-9_-]{1,64}; we hold both halves
  of a name to that so the pair survives encoding."
  #"[A-Za-z0-9_-]+")

(defn- safe-segment
  "Coerce a server name into something a provider will accept as part of a
  function name. Server names come from user config, so they can be anything."
  [server]
  (str/replace (name server) #"[^A-Za-z0-9_-]" "_"))

(defn tool-key
  "The registry name for `tool` on `server`: `:mcp-<server>/<tool>`."
  [server tool]
  (keyword (str "mcp-" (safe-segment server)) tool))

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
                (if (and (string? tool) (re-matches provider-safe-name (str tool)))
                  (tools/reg-tool
                   (tool-key server tool)
                   {:fn          (proxy-fn server conn tool)
                    ;; JSON Schema, used as-is (see the ns docstring). An
                    ;; absent schema means "no declared arguments", which the
                    ;; empty schema expresses to validate-args.
                    :schema      (or inputSchema {})
                    :description (or description
                                     (str tool " (" (name server) " MCP server)"))})
                  (do (log/warn "mcp: skipping tool whose name a provider API would reject"
                                {:server server :tool tool :must-match (str provider-safe-name)})
                      nil))))
        tool-list))

(defn unregister-tools!
  "Drop `tool-names` from the registry — called when a server disconnects, so
  no tool outlives the connection it proxies to."
  [tool-names]
  (run! tools/unreg-tool tool-names)
  nil)

;; ---------------------------------------------------------------------------
;; Resources
;;
;; Resources are not tools: selecting one attaches its text to the message a
;; person is writing, rather than being something the model invokes. So they
;; never enter the tool registry — they are listed for the mention overlay and
;; read on demand.
;;
;; The two halves of a resource arrive from different calls. `resources/list`
;; carries the display metadata (:name, :description, :mimeType); a
;; `resources/read` result carries only {:uri :mimeType :text} per content
;; entry, and no name at all. So a row keeps what the listing said and read-one
;; only adds the text.
;; ---------------------------------------------------------------------------

(defn resource-row
  "Shape one cached `resources/list` entry (tagged with its `:server` by the
  registry) into the row the overlay shows and reads from."
  [{:keys [server uri name description mimeType]}]
  {:server      server
   :uri         uri
   ;; A server may omit :name; the uri is what a person recognizes it by then.
   :name        (or name uri)
   :description description
   :mime-type   mimeType})

(defn resource-label
  "How a resource is shown in the overlay and referred to in a message:
  `server:uri`, which is unique across servers."
  [{:keys [server uri]}]
  (str (clojure.core/name server) ":" uri))

(defn- readable-text
  "The text of a `resources/read` result. A single resource may return several
  contents entries, so they are joined; binary (:blob) entries are skipped —
  a mention inserts text into a message, and base64 bytes are not that."
  [{:keys [contents]}]
  (let [text (->> contents (keep :text) (str/join "\n\n"))
        blobs (count (filter :blob contents))]
    (when (pos? blobs)
      (log/debug "mcp: skipping binary resource content" {:parts blobs}))
    text))

(defn read-resource
  "Read `row`'s resource through `conn` and return the row with its `:content`
  attached. Throws the client's typed ex-info if the server refuses or is gone —
  the caller decides how loudly to fail, since a mention is a person's action,
  not a model's."
  [conn row]
  (assoc row :content (readable-text (client/read-resource conn (:uri row)))))

;; ---------------------------------------------------------------------------
;; Mentions
;;
;; A person writes `@server:uri` in a message; the runtime resolves it into an
;; attachment on the way to the model (see pa.runtime.handlers). Resolution
;; matches against the *known* resources of connected servers rather than
;; guessing where a URI ends — URIs contain slashes, dots and colons, so any
;; boundary rule invented here would be wrong sooner or later.
;; ---------------------------------------------------------------------------

(def ^:private mention-terminators
  "What may follow a mention. Anything else means the URI continues, which is
  what stops `@s:demo://notes` from matching inside `@s:demo://notes2` while
  still resolving `@s:demo://notes.` at the end of a sentence."
  #{\space \tab \newline \return \. \, \; \: \! \? \) \] \} \" \' \>})

(def ^:private mention-openers
  "What may precede a mention besides whitespace. Brackets and quotes open one
  naturally; the point of the rule is that a letter or digit does not, so an
  address like ada@example.com is never mistaken for a mention."
  #{\( \[ \{ \" \' \<})

(defn- mention-at?
  [^String text ^String label idx]
  (and (or (zero? idx)
           (Character/isWhitespace (.charAt text (dec idx)))
           (contains? mention-openers (.charAt text (dec idx))))
       (let [end (+ idx (count label))]
         (or (= end (count text))
             (contains? mention-terminators (.charAt text end))))))

(defn- mention-index
  "Where `label` appears as a whole mention in `text`, or nil."
  [text label]
  (loop [from 0]
    (when-let [idx (str/index-of text label from)]
      (if (mention-at? text label idx) idx (recur (inc idx))))))

(defn parse-mentions
  "The resources `text` mentions, in order of first appearance. `resources` are
  rows from the connected servers' cached listings; a mention naming anything
  else is left alone as ordinary text."
  [text resources]
  (if-not (string? text)
    []
    (->> resources
         (keep (fn [row]
                 (when-let [idx (mention-index text (str "@" (resource-label row)))]
                   [idx row])))
         (sort-by first)
         (mapv second))))

;; ---------------------------------------------------------------------------
;; Prompts
;;
;; A server's prompts become slash commands — the concrete user of the :select
;; extension point Phase 7 documented but left unbuilt. `<server>.<prompt>` is
;; the command name; unlike a tool name it never reaches a provider API, so a
;; dot is safe here.
;;
;; Argument mapping keys on *required* arguments, not declared ones. The
;; reference server's args-prompt takes one required argument and one optional,
;; and is perfectly usable with a single value; counting declared arguments
;; would refuse it for no reason. Two or more required arguments have no
;; sensible one-line syntax yet and are a documented, deferred limitation.
;; ---------------------------------------------------------------------------

(defn prompt-command-name
  [server prompt]
  (str (clojure.core/name server) "." prompt))

(defn required-arguments
  "A prompt's arguments that must be supplied. :required is often simply absent
  rather than false."
  [prompt]
  (filterv :required (:arguments prompt)))

(defn prompt->command
  "The command spec for one `prompts/list` entry, or nil when its argument shape
  is not supported. `->event` dispatches :mcp/prompt-invoke; the runtime renders
  the prompt and feeds the result into the conversation."
  [server {prompt :name :keys [description title] :as entry}]
  (let [required (required-arguments entry)
        command  (prompt-command-name server prompt)
        desc     (or description title (str prompt " (" (clojure.core/name server) " MCP prompt)"))
        invoke   (fn [arguments]
                   {:event/type :mcp/prompt-invoke
                    :server     server
                    :prompt     prompt
                    :arguments  arguments})]
    (cond
      (not (and (string? prompt) (seq prompt)))
      (do (log/warn "mcp: skipping prompt with no name" {:server server}) nil)

      (empty? required)
      {:command command :description desc
       :arg-spec {:kind :none}
       :->event  (fn [_] (invoke {}))}

      (= 1 (count required))
      (let [arg (first required)]
        {:command command :description desc
         :arg-spec {:kind :free-text :required true
                    :placeholder (str "<" (:name arg) ">")}
         :->event  (fn [args] (invoke {(:name arg) (:text args)}))})

      :else
      (do (log/warn "mcp: skipping prompt — prompts with several required arguments are not supported yet"
                    {:server server :prompt prompt
                     :required (mapv :name required)})
          nil))))

(defn register-prompts!
  "Register every prompt `server` offers as a slash command. Returns the
  registered command names, which the caller keeps so it can withdraw them."
  [server prompt-list]
  (into [] (keep (fn [entry]
                   (when-let [spec (prompt->command server entry)]
                     (commands/reg-command spec))))
        prompt-list))

(defn unregister-prompts!
  "Drop `command-names` from the command registry — called when a server
  disconnects."
  [command-names]
  (run! commands/unreg-command command-names)
  nil)

(defn prompt-messages
  "Flatten a `prompts/get` result into conversation turns. A message's :content
  is a content block ({:type \"text\" :text \"…\"}), not a string; a block that
  carries no text — an image, an embedded resource — is dropped, since there is
  nowhere sensible to put it in a text conversation."
  [{:keys [messages]}]
  (into []
        (keep (fn [{:keys [role content]}]
                (let [text (cond
                             (string? content)     content
                             (map? content)        (:text content)
                             (sequential? content) (not-empty (str/join "\n" (keep :text content))))]
                  (if (str/blank? text)
                    (do (log/warn "mcp: dropping prompt message with no text content"
                                  {:role role})
                        nil)
                    {:role (keyword (or role "user")) :content text}))))
        messages))
