(ns pa.http
  "HTTP client seam.

  A thin protocol over the concrete HTTP library (hato) so call sites depend
  on an abstraction that can be faked in tests without touching the network.
  Production code uses the HatoClient; tests provide their own implementation."
  (:require [hato.client :as hato]))

(defprotocol HttpClient
  (post [this url opts]
    "POST to `url` with a hato-style opts map. Returns at least `{:status :body}`.")
  (fetch [this url opts]
    "GET `url` with a hato-style opts map (`:query-params`, `:headers`, `:as`, ...).
    Returns at least `{:status :body}`."))

(defrecord HatoClient []
  HttpClient
  (post  [_ url opts] (hato/post url opts))
  (fetch [_ url opts] (hato/get  url opts)))

(defn hato-client
  "Construct the production HTTP client backed by hato."
  []
  (->HatoClient))
