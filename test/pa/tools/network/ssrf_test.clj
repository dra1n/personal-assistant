(ns pa.tools.network.ssrf-test
  (:require [clojure.test :refer [deftest is testing]]
            [pa.tools.network.ssrf :as ssrf])
  (:import [java.net InetAddress]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- addr
  "Return an InetAddress for the given IP string (v4 or v6)."
  [ip-str]
  (InetAddress/getByName ip-str))

(defn- addr-bytes
  "Return an InetAddress constructed from a raw byte array (length 4 or 16)."
  [bytes-vec]
  (InetAddress/getByAddress (byte-array bytes-vec)))

(defn- resolver
  "Inject a mock resolver that always returns [addr] for any hostname."
  [ip-addr]
  (fn [_host] [ip-addr]))

;; ---------------------------------------------------------------------------
;; RFC-1918 / private IPv4

(deftest check-url-blocks-rfc1918-10-slash-8
  (testing "10.0.0.0/8 is blocked"
    (is (string? (ssrf/check-url "http://internal/" (resolver (addr "10.0.0.1")))))))

(deftest check-url-blocks-rfc1918-172-16-slash-12
  (testing "172.16.0.0/12 is blocked"
    (is (string? (ssrf/check-url "http://internal/" (resolver (addr "172.16.0.1")))))))

(deftest check-url-blocks-rfc1918-192-168-slash-16
  (testing "192.168.0.0/16 is blocked"
    (is (string? (ssrf/check-url "http://internal/" (resolver (addr "192.168.1.100")))))))

;; ---------------------------------------------------------------------------
;; Loopback

(deftest check-url-blocks-ipv4-loopback
  (testing "127.0.0.1 loopback is blocked"
    (is (string? (ssrf/check-url "http://localhost/" (resolver (addr "127.0.0.1")))))))

(deftest check-url-blocks-ipv6-loopback
  (testing "::1 loopback is blocked"
    (is (string? (ssrf/check-url "http://ip6-localhost/" (resolver (addr "::1")))))))

;; ---------------------------------------------------------------------------
;; Link-local

(deftest check-url-blocks-ipv4-link-local
  (testing "169.254.x.x (cloud metadata) is blocked"
    (is (string? (ssrf/check-url "http://169.254.169.254/" (resolver (addr "169.254.169.254")))))))

(deftest check-url-blocks-ipv6-link-local
  (testing "fe80::/10 link-local is blocked"
    ;; fe80::1 as raw bytes: 0xFE 0x80 followed by 13 zeros and 0x01
    (let [fe80-addr (addr-bytes [-2 -128 0 0 0 0 0 0 0 0 0 0 0 0 0 1])]
      (is (string? (ssrf/check-url "http://fe80-host/" (resolver fe80-addr)))))))

;; ---------------------------------------------------------------------------
;; IPv6 Unique Local (fc00::/7)

(deftest check-url-blocks-ipv6-unique-local-fc
  (testing "fc00::/7 (fc prefix) is blocked"
    ;; fc00::1: first byte 0xFC = -4 signed
    (let [fc-addr (addr-bytes [-4 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1])]
      (is (string? (ssrf/check-url "http://ula-host/" (resolver fc-addr)))))))

(deftest check-url-blocks-ipv6-unique-local-fd
  (testing "fd00::/8 (fd prefix, most common ULA) is blocked"
    ;; fd00::1: first byte 0xFD = -3 signed
    (let [fd-addr (addr-bytes [-3 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1])]
      (is (string? (ssrf/check-url "http://ula-host/" (resolver fd-addr)))))))

;; ---------------------------------------------------------------------------
;; Public IPs — must be allowed

(deftest check-url-allows-public-ipv4
  (testing "a routable public IPv4 returns nil (safe)"
    (is (nil? (ssrf/check-url "https://example.com/" (resolver (addr "93.184.216.34")))))))

(deftest check-url-allows-public-ipv6
  (testing "a routable public IPv6 returns nil (safe)"
    ;; 2606:2800:21f:cb07:6820:80da:af6b:8b2c is example.com's IPv6
    (let [pub6 (addr-bytes [0x26 0x06 0x28 0x00 0x02 0x1f -53 7 0x68 0x20 -128 -38 -81 0x6b -117 0x2c])]
      (is (nil? (ssrf/check-url "https://example.com/" (resolver pub6)))))))

;; ---------------------------------------------------------------------------
;; DNS-rebinding scenario

(deftest check-url-blocks-dns-rebinding
  (testing "public hostname resolving to private IP is blocked (DNS rebinding)"
    ;; A hostname that looks external but resolves to an RFC-1918 address.
    (is (string? (ssrf/check-url "https://evil.example.com/" (resolver (addr "192.168.1.1")))))))

(deftest check-url-blocks-any-private-in-multi-a
  (testing "if ANY resolved address is private, the request is blocked"
    ;; One public and one private address — must still be blocked.
    (let [mixed-resolver (fn [_host] [(addr "93.184.216.34") (addr "10.0.0.1")])]
      (is (string? (ssrf/check-url "https://mixed.example.com/" mixed-resolver))))))
