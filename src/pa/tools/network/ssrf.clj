(ns pa.tools.network.ssrf
  "SSRF guard: resolves a hostname's DNS addresses before any HTTP connection
  and rejects any that fall in private or reserved IP ranges (RFC-1918,
  loopback, link-local, IPv6 Unique Local / link-local).")

(defn- private-ip?
  "Returns true if addr is a private, loopback, link-local, or reserved address."
  [^java.net.InetAddress addr]
  (or (.isLoopbackAddress addr)
      (.isLinkLocalAddress addr)
      (.isSiteLocalAddress addr)
      (.isAnyLocalAddress addr)
      ;; IPv6 Unique Local fc00::/7 — not covered by isSiteLocalAddress()
      (let [raw (.getAddress addr)]
        (and (= 16 (alength raw))
             (= 0xFC (bit-and (bit-and (aget raw 0) 0xFF) 0xFE))))))

(defn default-resolve
  "Resolve host to a seq of InetAddress via DNS.
  Throws ex-info with :type :tool/ssrf-unresolvable on unknown host."
  [host]
  (try
    (into [] (java.net.InetAddress/getAllByName host))
    (catch java.net.UnknownHostException _
      (throw (ex-info (str "Cannot resolve host: " host)
                      {:type :tool/ssrf-unresolvable :host host})))))

(defn check-url
  "Returns nil if url-str is safe to fetch, or an error string if any resolved
  address falls in a private/reserved range. resolve-fn is called with the
  hostname and must return a seq of InetAddress; defaults to default-resolve.
  Throws ex-info on DNS failure."
  ([url-str]
   (check-url url-str default-resolve))
  ([url-str resolve-fn]
   (let [url   (java.net.URL. url-str)
         host  (.getHost url)
         addrs (resolve-fn host)]
     (when (some private-ip? addrs)
       (str "Blocked: " host " resolves to a private or reserved IP address")))))
