(ns pa.http
  "HTTP client seam.

  A thin protocol over the concrete HTTP library (hato) so call sites depend
  on an abstraction that can be faked in tests without touching the network.
  Production code uses the HatoClient; tests provide their own implementation."
  (:require [hato.client :as hato]))

(defprotocol HttpClient
  (post [this url opts]
    "POST to `url` with a hato-style request opts map (`:headers`, `:body`,
    `:as`, ...). Returns the response map, at least `{:status :body}`; `:body`
    honours the requested `:as` coercion (`:string`, `:stream`, ...)."))

(defrecord HatoClient []
  HttpClient
  (post [_ url opts] (hato/post url opts)))

(defn hato-client
  "Construct the production HTTP client backed by hato."
  []
  (->HatoClient))
