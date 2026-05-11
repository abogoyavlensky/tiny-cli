(ns tiny-cli.core)


(defn root-help
  [app]
  (str (:name app) "\n\n" (:doc app)))

(defn command-help
  [app command-name]
  (let [command (first (filter #(= command-name (:name %)) (:commands app)))]
    (str (:name app) " " (:name command) "\n\n" (:doc command))))

(defn parse
  [app argv]
  (if (= ["--version"] argv)
    {:status :version
     :text (str (:name app) " " (:version app))}
    {:status :help
     :command nil
     :text (root-help app)}))

(defn run!
  [app]
  (parse app []))
