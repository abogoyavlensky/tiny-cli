(ns tiny-cli.core
  (:require #?(:lg [string :as str]
               :default [clojure.string :as str])))


(defn- join-lines
  [lines]
  (str/join "\n" (filter some? lines)))

(defn- command-by-name
  [app command-name]
  (first (filter #(= command-name (:name %)) (:commands app))))

(defn- key-placeholder
  [k]
  (-> (name k)
      (str/replace #"\?$" "")
      (str/upper-case)))

(defn- option-label
  [opt]
  (let [base (cond
               (and (:short opt) (:long opt))
               (str "-" (:short opt) ", --" (:long opt))

               (:short opt)
               (str "-" (:short opt))

               (:long opt)
               (str "--" (:long opt))

               :else
               "")
        value (when (:value? opt)
                (str " " (key-placeholder (:key opt))))]
    (str base value)))

(defn- option-doc
  [opt]
  (str (or (:doc opt) "")
       (when (contains? opt :default)
         (str " Default: " (:default opt)))))

(defn- format-option
  [opt]
  (str "  " (option-label opt)
       (when (seq (option-doc opt))
         (str "  " (option-doc opt)))))

(defn- format-arg
  [arg]
  (str "  " (key-placeholder (:key arg))
       (when (seq (:doc arg))
         (str "  " (:doc arg)))))

(defn- format-command-row
  [command]
  (str "  " (:name command)
       (when (seq (:doc command))
         (str "  " (:doc command)))))

(defn- command-usage
  [app command]
  (let [args (map #(key-placeholder (:key %)) (:args command))]
    (str (:name app) " " (:name command)
         (when (seq args)
           (str " " (str/join " " args))))))

(defn- root-built-ins
  [app]
  (concat ["  -h, --help  Show help."
           "  help [command]  Show help."]
          (when (:version app)
            ["  --version  Print version."])))

(defn- command-built-ins
  [command-name]
  [(str "  -h, --help  Show help for " command-name ".")
   (str "  help " command-name "  Show help for " command-name ".")])

(defn root-help
  [app]
  (join-lines
    (concat [(:name app)
             ""
             (:doc app)
             ""
             "Usage:"
             (str "  " (:name app) " [options] <command> [args]")
             (str "  " (:name app) " help [command]")
             (str "  " (:name app) " --help")
             ""
             "Options:"]
            (map format-option (:opts app))
            ["" "Commands:"]
            (map format-command-row (:commands app))
            ["" "Built-ins:"]
            (root-built-ins app))))

(defn command-help
  [app command-name]
  (if-let [command (command-by-name app command-name)]
    (join-lines
      (concat [(command-usage app command)
               ""
               (:doc command)
               ""
               "Usage:"
               (str "  " (command-usage app command))
               (str "  " (:name app) " help " (:name command))]
              (when (seq (:args command))
                (concat ["" "Args:"]
                        (map format-arg (:args command))))
              (when (seq (:opts command))
                (concat ["" "Options:"]
                        (map format-option (:opts command))))
              (when (seq (:opts app))
                (concat ["" "Global Options:"]
                        (map format-option (:opts app))))
              ["" "Built-ins:"]
              (command-built-ins (:name command))))
    (str "Unknown command: " command-name)))

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
