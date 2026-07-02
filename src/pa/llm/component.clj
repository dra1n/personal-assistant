(ns pa.llm.component
  "Integrant component for the active LLM provider.

  Config shape (see resources/system.edn):
    {:provider  :openai
     :openai    {:api-key ... :base-url ... :model ...}
     :anthropic {:api-key ... :base-url ... :model ...}}

  Selects the implementation by :provider, constructs it from that provider's
  sub-map, and returns the provider record directly, so it can be ref'd into
  the dispatcher and used wherever an LLMProvider is expected."
  (:require [integrant.core :as ig]
            [pa.llm.anthropic :as anthropic]
            [pa.llm.openai :as openai]
            [taoensso.timbre :as log]))

(defn- build [provider opts]
  (case provider
    :openai    (openai/make-provider opts)
    :anthropic (anthropic/make-provider opts)
    (throw (ex-info "unknown :llm/provider" {:provider provider}))))

(defmethod ig/init-key :llm/provider [_ {:keys [provider] :as config}]
  (let [opts (get config provider)]
    (log/info "llm provider initialized" {:provider provider :model (:model opts)})
    (build provider opts)))

(defmethod ig/halt-key! :llm/provider [_ _])
