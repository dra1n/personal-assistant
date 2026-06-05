(ns runner
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]))

(defn- discover-test-namespaces
  "Scan the test/ directory for *_test.clj files and return their namespace symbols."
  []
  (->> (file-seq (io/file "test"))
       (filter #(str/ends-with? (.getName %) "_test.clj"))
       (map (fn [f]
              (-> (.getPath f)
                  (str/replace #"^test[/\\]" "")
                  (str/replace #"\.clj$" "")
                  (str/replace #"[/\\]" ".")
                  (str/replace "_" "-")
                  symbol)))
       sort))

(defn -main [& _]
  (doseq [ns-sym (discover-test-namespaces)]
    (require ns-sym))
  (let [{:keys [fail error]} (test/run-all-tests #"pa\..*-test")]
    (System/exit (if (zero? (+ fail error)) 0 1))))
