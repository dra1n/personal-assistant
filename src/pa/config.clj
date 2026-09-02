(ns pa.config
  "Loads the integrant system config from resources/system.edn via aero.

  Two custom value sources, combined per value in system.edn with #or so the
  precedence is explicit where each setting is declared:

    #env X      — the real process environment, falling back to a gitignored
                  .env file in the project root (KEY=VALUE lines, dev-time).
                  The JVM cannot mutate its environment, so .env is consulted
                  at config-read time only — it never becomes a real env var.
    #setting P  — get-in path P into the user's <PA_HOME>/config.edn, which is
                  itself read through aero (bootstrapped from a template by
                  :storage/fs), so a user's settings file may use #env and #or
                  too — secrets need not be written into it in the clear."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig]
            [pa.storage.fs :as fs]))

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

;; ---------------------------------------------------------------------------
;; <PA_HOME>/config.edn user settings
;; ---------------------------------------------------------------------------

(defn- load-settings
  "The user's settings map, read through aero so config.edn gets the same
  readers system.edn does. An absent, empty or comment-only file is no
  settings rather than an error; anything unparseable is fatal and says so.

  The file is passed to aero as a File, not its contents, so a relative
  #include inside it resolves against the file's own directory."
  [path dotenv]
  (let [f (io/file path)]
    (if (.exists f)
      (try
        (or (aero/read-config f {:dotenv dotenv :settings ::self}) {})
        (catch Exception e
          (throw (ex-info (str "Malformed user settings file: " path)
                          {:path path} e))))
      {})))

;; #setting reaches into the user's settings file, so inside that same file it
;; would be circular. Throwing beats resolving to a silent nil, which is what
;; a plain get-in on the sentinel would do.
(defmethod aero/reader 'setting [{:keys [settings]} _ path]
  (if (= ::self settings)
    (throw (ex-info "#setting is not available inside config.edn — it resolves into that same file"
                    {:path path}))
    (get-in settings path)))

(defn system-config []
  (let [dotenv (load-dotenv ".env")]
    (aero/read-config (io/resource "system.edn")
                      {:dotenv   dotenv
                       :settings (load-settings (str (fs/pa-home) "/config.edn") dotenv)})))
