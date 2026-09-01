(ns pa.logging
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [pa.storage.fs :as fs]
            [taoensso.timbre :as log]))

(defonce ^{:doc "When false, the stdout (:println) appender is silenced. The
  terminal UI and the app entrypoint set this false so logs don't corrupt the
  charm-rendered frame; REPL sessions leave it true for inline console output.
  Held as a flag (not just a runtime toggle) because the choice is often made
  before the appenders exist — pa.core silences the console before ig/init —
  so configure! reads it when installing :println rather than assuming on."}
  console?
  (atom true))

(defn- log-file
  "Absolute path to the durable log file under PA_HOME/logs, parents created."
  []
  (let [f (io/file (fs/pa-home) "logs" "pa.log")]
    (io/make-parents f)
    (str f)))

(defn set-console!
  "Enable or disable the stdout log appender immediately, and record the choice
  so a later (re)initialisation of :pa.logging/timbre honours it."
  [on?]
  (reset! console? on?)
  (log/merge-config! {:appenders {:println {:enabled? (boolean on?)}}}))

;; The :println appender always carries its :fn (so set-console! can re-enable
;; it), but its :enabled? tracks the console? flag. The file appender is the
;; durable record and stays on in every mode.
(defn configure!
  "Install the appenders. Idempotent.

  Applied by the :pa.logging/timbre component, which every other component
  refs (see resources/system.edn) so that it initialises first: components
  init-log before their appenders exist otherwise, and in app mode — where
  set-console! has already silenced stdout — those lines are simply lost."
  []
  (log/merge-config!
   {:min-level :debug
    :appenders {:file    (log/spit-appender {:fname (log-file)})
                :println (assoc (log/println-appender {:stream :auto})
                                :enabled? @console?)}}))

(defmethod ig/init-key :pa.logging/timbre [_ _opts]
  (configure!)
  (log/info "logging initialized")
  {})

(defmethod ig/halt-key! :pa.logging/timbre [_ _]
  (log/info "logging shutting down"))
