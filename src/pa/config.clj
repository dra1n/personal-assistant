(ns pa.config
  "Loads the integrant system config from resources/system.edn via aero.

  #env resolution order: the real process environment first, then a
  gitignored .env file in the project root (KEY=VALUE lines) as a dev-time
  fallback. The JVM cannot mutate its environment, so .env is consulted at
  config-read time only — it never becomes a real env var."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig]))

(defmethod aero/reader 'ig/ref [_ _ value]
  (ig/ref value))

;; ---------------------------------------------------------------------------
;; .env
;; ---------------------------------------------------------------------------

(defn parse-dotenv
  "Parse .env file contents into {\"KEY\" \"value\"}. Supports blank lines,
  full-line # comments, an optional `export ` prefix, and single- or
  double-quoted values. No interpolation or inline comments. Pure."
  [s]
  (->> (str/split-lines s)
       (keep (fn [line]
               (let [line (str/trim line)]
                 (when-not (or (str/blank? line) (str/starts-with? line "#"))
                   (let [line  (if (str/starts-with? line "export ")
                                 (subs line (count "export "))
                                 line)
                         [k v] (str/split line #"=" 2)]
                     (when (and k v)
                       [(str/trim k)
                        (-> (str/trim v)
                            (str/replace #"^\"(.*)\"$" "$1")
                            (str/replace #"^'(.*)'$" "$1"))]))))))
       (into {})))

(defn- load-dotenv [path]
  (let [f (io/file path)]
    (if (.exists f) (parse-dotenv (slurp f)) {})))

;; Override aero's built-in #env reader: real environment wins, .env (passed
;; through aero's reader opts by system-config) fills the gaps.
(defmethod aero/reader 'env [{:keys [dotenv]} _ value]
  (or (System/getenv (str value))
      (get dotenv (str value))))

(defn system-config []
  (aero/read-config (io/resource "system.edn")
                    {:dotenv (load-dotenv ".env")}))
