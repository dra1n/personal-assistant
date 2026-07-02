(ns pa.llm.anthropic
  "Anthropic provider — Phase 3 stub.

  Conforms to pa.llm.provider/LLMProvider so a real implementation is a
  drop-in later (Phase 3 out-of-scope per the spec). Both methods throw a
  clear 'not implemented' error; the record is fully constructible so the
  protocol's support for a second provider is demonstrable now."
  (:require [pa.llm.provider :as provider]))

(defn- not-implemented [method]
  (throw (ex-info (str "Anthropic provider is a Phase 3 stub — " method
                       " not implemented yet")
                  {:provider :anthropic :method method})))

(defrecord AnthropicProvider [api-key base-url model]
  provider/LLMProvider
  (invoke [_ _messages _opts] (not-implemented "invoke"))
  (stream [_ _messages _opts _on-delta] (not-implemented "stream")))

(defn make-provider
  "Construct the stub from explicit settings — defaults and env overrides
  live in the :llm/provider integrant config (system.edn)."
  [{:keys [api-key base-url model]}]
  (->AnthropicProvider api-key base-url model))
