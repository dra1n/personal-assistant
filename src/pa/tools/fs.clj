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

(defn make-dir
  "Create the directory at :path, including parents (requires :write).
  Idempotent. Returns {:path <canonical> :created <bool>}."
  [{:keys [path]} ctx]
  (let [p (policy/check (policy-of ctx) path :write)
        f (io/file p)]
    (cond
      (.isDirectory f) {:path p :created false}
      (.mkdirs f)      {:path p :created true}
      :else            (throw (ex-info "could not create directory"
                                       {:type :tool/io-error :path p})))))

(defn- delete-recursively! [^java.io.File f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)] (delete-recursively! child)))
  (.delete f))

(defn delete
  "Delete the file or directory at :path (requires :write). A non-empty
  directory needs :recursive true. Refuses to delete an allowlist root.
  Idempotent on a missing path. Returns {:path <canonical> :deleted <bool>}."
  [{:keys [path recursive]} ctx]
  (let [policy (policy-of ctx)
        p      (policy/check policy path :write)
        f      (io/file p)]
    (when (policy/root-path? policy p)
      (throw (ex-info "refusing to delete an allowlist root"
                      {:type :tool/refused :path p})))
    (cond
      (not (.exists f))
      {:path p :deleted false}

      (and (.isDirectory f) (seq (.listFiles f)) (not recursive))
      (throw (ex-info "directory not empty — pass recursive true to delete it"
                      {:type :tool/not-empty :path p}))

      :else
      (do (delete-recursively! f) {:path p :deleted true}))))

(defn move
  "Move/rename :from to :to (requires :write on both). Creates parent dirs of
  the destination; refuses to overwrite an existing destination. Returns
  {:from <canonical> :to <canonical>}."
  [{:keys [from to]} ctx]
  (let [policy (policy-of ctx)
        src    (policy/check policy from :write)
        dst    (policy/check policy to :write)
        srcf   (io/file src)
        dstf   (io/file dst)]
    (when-not (.exists srcf)
      (throw (ex-info "source does not exist" {:type :tool/not-found :path src})))
    (when (.exists dstf)
      (throw (ex-info "destination already exists" {:type :tool/exists :path dst})))
    (io/make-parents dstf)
    (java.nio.file.Files/move (.toPath srcf) (.toPath dstf)
                              (make-array java.nio.file.CopyOption 0))
    {:from src :to dst}))

(defn file-info
  "Stat :path (requires :read). Returns {:path <canonical> :exists <bool>
  :type :file|:dir|nil :size <bytes-or-nil>} without reading contents."
  [{:keys [path]} ctx]
  (let [p (policy/check (policy-of ctx) path :read)
        f (io/file p)]
    {:path   p
     :exists (.exists f)
     :type   (cond (not (.exists f)) nil
                   (.isDirectory f)  :dir
                   :else             :file)
     :size   (when (.isFile f) (.length f))}))

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

(registry/reg-tool :fs/make-dir
  {:fn          make-dir
   :description "Create a directory at the given path, including any parent directories."
   :schema      {:type       "object"
                 :properties {:path {:type "string" :description "Path of the directory to create."}}
                 :required   [:path]}})

(registry/reg-tool :fs/delete
  {:fn          delete
   :description "Delete the file or directory at the given path. A non-empty directory requires recursive=true."
   :schema      {:type       "object"
                 :properties {:path      {:type "string" :description "Path to delete."}
                              :recursive {:type "boolean" :description "Delete a non-empty directory and its contents."}}
                 :required   [:path]}})

(registry/reg-tool :fs/move
  {:fn          move
   :description "Move or rename a file or directory from one path to another."
   :schema      {:type       "object"
                 :properties {:from {:type "string" :description "Source path."}
                              :to   {:type "string" :description "Destination path."}}
                 :required   [:from :to]}})

(registry/reg-tool :fs/file-info
  {:fn          file-info
   :description "Get whether a path exists and its type and size, without reading its contents."
   :schema      {:type       "object"
                 :properties {:path {:type "string" :description "Path to inspect."}}
                 :required   [:path]}})
