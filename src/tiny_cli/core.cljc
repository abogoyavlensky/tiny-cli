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

(defn- error-result
  [message]
  {:status :error
   :message message})

(defn- option-index
  [opts prefix key-name]
  (reduce (fn [idx opt]
            (if-let [spelling (get opt key-name)]
              (assoc idx (str prefix spelling) opt)
              idx))
          {}
          opts))

(defn- long-index
  [opts]
  (option-index opts "--" :long))

(defn- short-index
  [opts]
  (option-index opts "-" :short))

(defn- option-token?
  [token]
  (and (string? token)
       (str/starts-with? token "-")
       (not= "-" token)))

(defn- set-option
  [state target opt value]
  (assoc state target (assoc (get state target) (:key opt) value)))

(defn- parse-long-option
  [state token rest-tokens long-options]
  (let [eq-idx (str/index-of token "=")
        spelling (if eq-idx (subs token 0 eq-idx) token)
        entry (get long-options spelling)
        opt (:opt entry)
        target (:target entry)]
    (cond
      (nil? opt)
      (error-result (str "Unknown option: " spelling))

      (:value? opt)
      (if eq-idx
        {:state (set-option state target opt (subs token (inc eq-idx)))
         :tokens rest-tokens}
        (if (seq rest-tokens)
          {:state (set-option state target opt (first rest-tokens))
           :tokens (vec (rest rest-tokens))}
          (error-result (str "Missing value for option: " spelling))))

      eq-idx
      (error-result (str "Option does not take a value: " spelling))

      :else
      {:state (set-option state target opt true)
       :tokens rest-tokens})))

(defn- parse-short-group
  [state token rest-tokens short-options]
  (let [letters (map str (seq (subs token 1)))]
    (loop [remaining letters
           current state]
      (if (seq remaining)
        (let [spelling (str "-" (first remaining))
              entry (get short-options spelling)
              opt (:opt entry)
              target (:target entry)]
          (cond
            (nil? opt)
            (error-result (str "Unknown option: " spelling))

            (:value? opt)
            (if (= 1 (count letters))
              (if (seq rest-tokens)
                {:state (set-option current target opt (first rest-tokens))
                 :tokens (vec (rest rest-tokens))}
                (error-result (str "Missing value for option: " spelling)))
              (error-result (str "Option requires a value and cannot be grouped: " spelling)))

            :else
            (recur (rest remaining) (set-option current target opt true))))
        {:state current
         :tokens rest-tokens}))))

(defn- parse-option-token
  [state token rest-tokens long-options short-options]
  (if (str/starts-with? token "--")
    (parse-long-option state token rest-tokens long-options)
    (parse-short-group state token rest-tokens short-options)))

(defn- long-spelling
  [token]
  (let [eq-idx (str/index-of token "=")]
    (if eq-idx
      (subs token 0 eq-idx)
      token)))

(defn- target-index
  [target opts index-fn]
  (into {} (map (fn [[spelling opt]]
                  [spelling {:target target :opt opt}])
                (index-fn opts))))

(defn- option-conflict
  [global-opts command-opts]
  (let [global-spellings (set (concat (keys (long-index global-opts))
                                      (keys (short-index global-opts))))]
    (first (filter global-spellings
                   (concat (keys (long-index command-opts))
                           (keys (short-index command-opts)))))))

(defn- build-context
  [command state]
  (let [arg-keys (map :key (:args command))]
    {:global (:global state)
     :args (into {} (map vector arg-keys (:positionals state)))
     :opts (:opts state)}))

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
  (let [global-longs (target-index :global (:opts app) long-index)
        global-shorts (target-index :global (:opts app) short-index)]
    (loop [tokens (vec argv)
           command nil
           state {:global {} :opts {} :positionals []}]
      (if (seq tokens)
        (let [token (first tokens)
              more (vec (rest tokens))]
          (cond
            (= "--version" token)
            {:status :version
             :text (str (:name app) " " (:version app))}

            (= "--" token)
            (if command
              (recur [] command (assoc state :positionals (vec (concat (:positionals state) more))))
              (error-result "Unexpected end-of-options before command."))

            (and (nil? command) (option-token? token))
            (let [parsed (parse-option-token state token more global-longs global-shorts)]
              (if (= :error (:status parsed))
                parsed
                (recur (:tokens parsed) command (:state parsed))))

            (nil? command)
            (if-let [selected (command-by-name app token)]
              (if-let [conflict (option-conflict (:opts app) (:opts selected))]
                (error-result (str "Option conflict: " conflict))
                (recur more selected state))
              (error-result (str "Unknown command: " token)))

            (option-token? token)
            (let [all-longs (merge (target-index :global (:opts app) long-index)
                                   (target-index :opts (:opts command) long-index))
                  all-shorts (merge (target-index :global (:opts app) short-index)
                                    (target-index :opts (:opts command) short-index))
                  parsed (parse-option-token state token more all-longs all-shorts)]
              (if (= :error (:status parsed))
                parsed
                (recur (:tokens parsed) command (:state parsed))))

            :else
            (recur more command (assoc state :positionals (conj (:positionals state) token)))))
        (if command
          {:status :ok
           :command command
           :context (build-context command state)}
          {:status :help
           :command nil
           :text (root-help app)})))))

(defn run!
  [app]
  (parse app []))
