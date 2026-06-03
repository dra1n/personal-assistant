(ns pa.tools.fs
  "Filesystem tools — read-file, list-dir, write-file.

  Every tool is gated by the fs access policy (pa.tools.fs.policy): it calls
  policy/check with the capability it needs, receives the safe canonical path,
  and operates only on that path — never the raw argument. A denied path throws
  :tool/access-denied, which the :tool/invoke effect turns into an error
  :tool/result rather than a side effect.

  Schemas are JSON-Schema-shaped EDN so they double as argument contracts and as
  the function specs advertised to the LLM (Group 4). Tools self-register in the
  tool registry at namespace load time."
  (:require [clojure.java.io :as io]
            [pa.tools.fs.policy :as policy]
            [pa.tools.registry :as registry]))

(defn- policy-of [ctx]
  (or (:tool.fs/policy ctx)
      (throw (ex-info "no fs policy in ctx — is :tool.fs/policy wired?"
                      {:type :tool/misconfigured}))))

;; ---------------------------------------------------------------------------
;; Tool implementations: (fn [args ctx] -> result)
;; ---------------------------------------------------------------------------

(defn read-file
  "Read the file at :path (requires :read). Returns {:path <canonical> :content <string>}."
  [{:keys [path]} ctx]
  (let [p (policy/check (policy-of ctx) path :read)]
    {:path p :content (slurp p)}))

(defn list-dir
  "List the directory at :path (requires :read). Returns
  {:path <canonical> :entries [{:name :type} ...]} sorted by name; :type is
  :dir or :file."
  [{:keys [path]} ctx]
  (let [p (policy/check (policy-of ctx) path :read)
        f (io/file p)]
    (when-not (.isDirectory f)
      (throw (ex-info "not a directory" {:type :tool/not-a-directory :path p})))
    {:path    p
     :entries (->> (.listFiles f)
                   (map (fn [c] {:name (.getName c)
                                 :type (if (.isDirectory c) :dir :file)}))
                   (sort-by :name)
                   vec)}))

(defn write-file
  "Write :content to :path (requires :write), creating parent dirs as needed.
  Returns {:path <canonical> :bytes-written <int>}."
  [{:keys [path content]} ctx]
  (let [p     (policy/check (policy-of ctx) path :write)
        bytes (str content)]
    (io/make-parents p)
    (spit p bytes)
    {:path p :bytes-written (count (.getBytes bytes))}))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(registry/reg-tool :fs/read-file
  {:fn          read-file
   :description "Read the contents of a file at the given path."
   :schema      {:type       "object"
                 :properties {:path {:type "string" :description "Path of the file to read."}}
                 :required   [:path]}})

(registry/reg-tool :fs/list-dir
  {:fn          list-dir
   :description "List the entries of a directory at the given path."
   :schema      {:type       "object"
                 :properties {:path {:type "string" :description "Path of the directory to list."}}
                 :required   [:path]}})

(registry/reg-tool :fs/write-file
  {:fn          write-file
   :description "Write text content to a file at the given path, creating parent directories as needed."
   :schema      {:type       "object"
                 :properties {:path    {:type "string" :description "Path of the file to write."}
                              :content {:type "string" :description "Text content to write."}}
                 :required   [:path :content]}})
