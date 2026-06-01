(ns pa.llm.component
  "Integrant component for the active LLM provider.

  Selects the implementation by the :provider key (defaults to :openai) and
  returns the constructed provider record directly, so it can be ref'd into
  the dispatcher and used wherever an LLMProvider is expected."
  (:require [integrant.core :as ig]
            [pa.llm.anthropic :as anthropic]
            [pa.llm.openai :as openai]
            [taoensso.timbre :as log]))

(defn- build [{:keys [provider] :as opts}]
  (case (or provider :openai)
    :openai    (openai/make-provider opts)
    :anthropic (anthropic/make-provider opts)
    (throw (ex-info "unknown :llm/provider" {:provider provider}))))

(defmethod ig/init-key :llm/provider [_ {:keys [provider] :as opts}]
  (log/info "llm provider initialized" {:provider (or provider :openai)})
  (build opts))

(defmethod ig/halt-key! :llm/provider [_ _])
