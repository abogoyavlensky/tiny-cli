(ns tiny-cli.core
  #?(:clj (:refer-clojure :exclude [run!])
     :lg (:refer-clojure :exclude [run!]))
  (:require #?(:lg [os])
            #?(:lg [string :as str]
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
    (str/join " " (filter some?
                          [(:name app)
                           (when (seq (:opts app))
                             "[global options]")
                           (:name command)
                           (when (seq args)
                             (str "[" (str/join " " args) "]"))
                           (when (seq (:opts command))
                             "[options]")]))))

(defn- command-usage-min
  [app command]
  (let [args (map #(key-placeholder (:key %)) (:args command))]
    (str/join " " (filter some?
                          [(:name app)
                           (:name command)
                           (when (seq args)
                             (str "[" (str/join " " args) "]"))]))))

(defn- root-option-built-ins
  [app]
  (concat ["  -h, --help  Show help."]
          (when (:version app)
            ["  --version  Print version."])))

(defn- root-command-built-ins
  [app]
  ["  help [command]  Show help."])

(defn- command-option-built-ins
  [app-name command-name]
  (str "  " app-name " " command-name " -h, --help  Show help for " command-name "."))

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
                  [spelling {:target target
                             :opt opt}])
                (index-fn opts))))

(defn- option-conflict
  [global-opts command-opts]
  (let [global-spellings (set (concat (keys (long-index global-opts))
                                      (keys (short-index global-opts))))]
    (first (filter global-spellings
                   (concat (keys (long-index command-opts))
                           (keys (short-index command-opts)))))))

(defn- version-available
  [app]
  (if (:version app)
    {:status :version
     :text (str (:name app) " " (:version app))}
    (error-result "No version available.")))

(defn- duplicate-command-name
  [commands]
  (loop [seen #{}
         remaining commands]
    (when (seq remaining)
      (let [command-name (:name (first remaining))]
        (if (contains? seen command-name)
          command-name
          (recur (conj seen command-name) (rest remaining)))))))

(defn- option-spellings-valid?
  [opts]
  (every? #(or (:short %) (:long %)) opts))

(defn- first-missing-key
  [specs]
  (first (filter #(nil? (:key %)) specs)))

(defn- first-duplicate-key
  [specs]
  (loop [seen #{}
         remaining specs]
    (when (seq remaining)
      (let [k (:key (first remaining))]
        (if (contains? seen k)
          k
          (recur (conj seen k) (rest remaining)))))))

(defn- duplicate-in
  [xs]
  (loop [seen #{}
         remaining xs]
    (when (seq remaining)
      (let [x (first remaining)]
        (if (contains? seen x)
          x
          (recur (conj seen x) (rest remaining)))))))

(defn- option-spellings
  [opts]
  (mapcat (fn [opt]
            (concat (when (:long opt) [(str "--" (:long opt))])
                    (when (:short opt) [(str "-" (:short opt))])))
          opts))

(defn- bad-validation?
  [validation]
  (or (nil? (:pred validation))
      (not (fn? (:pred validation)))
      (nil? (:msg validation))
      (not (string? (:msg validation)))))

(defn- first-invalid-validation
  [specs]
  (first (filter #(when-let [validation (:validate %)]
                    (bad-validation? validation))
                 specs)))

(defn- command-spec-error
  [app command]
  (cond
    (nil? (:name command))
    (error-result "Command requires :name.")

    (nil? (:run command))
    (error-result (str "Missing command runner: " (:name command)))

    (first-missing-key (:args command))
    (error-result "Arg requires :key.")

    (first-missing-key (:opts command))
    (error-result "Option requires :key.")

    (first-duplicate-key (:args command))
    (error-result (str "Duplicate arg key: " (first-duplicate-key (:args command))))

    (first-duplicate-key (:opts command))
    (error-result (str "Duplicate option key: " (first-duplicate-key (:opts command))))

    (not (option-spellings-valid? (:opts command)))
    (error-result "Option requires :short or :long.")

    (duplicate-in (option-spellings (:opts command)))
    (error-result (str "Duplicate option spelling: " (duplicate-in (option-spellings (:opts command)))))

    (first-invalid-validation (concat (:args command) (:opts command)))
    (error-result "Invalid validation spec.")))

(defn- first-command-spec-error
  [app]
  (first (keep #(command-spec-error app %) (:commands app))))

(defn- first-option-conflict
  [app]
  (first (keep #(option-conflict (:opts app) (:opts %)) (:commands app))))

(defn- short-claimed?
  [opts short-name]
  (contains? (short-index opts) (str "-" short-name)))

(defn- app-spec-error
  [app]
  (cond
    (nil? (:name app))
    (error-result "App requires :name.")

    (nil? (:commands app))
    (error-result "App requires :commands.")

    (duplicate-command-name (:commands app))
    (error-result (str "Duplicate command: " (duplicate-command-name (:commands app))))

    (first-missing-key (:opts app))
    (error-result "Option requires :key.")

    (first-duplicate-key (:opts app))
    (error-result (str "Duplicate option key: " (first-duplicate-key (:opts app))))

    (not (option-spellings-valid? (:opts app)))
    (error-result "Option requires :short or :long.")

    (duplicate-in (option-spellings (:opts app)))
    (error-result (str "Duplicate option spelling: " (duplicate-in (option-spellings (:opts app)))))

    (first-invalid-validation (:opts app))
    (error-result "Invalid validation spec.")

    (first-option-conflict app)
    (error-result (str "Option conflict: " (first-option-conflict app)))

    :else
    (first-command-spec-error app)))

(defn- apply-defaults
  [opts parsed]
  (reduce (fn [m opt]
            (if (and (contains? opt :default)
                     (not (contains? m (:key opt))))
              (assoc m (:key opt) (:default opt))
              m))
          parsed
          opts))

(defn- required-option-missing
  [opts parsed]
  (first (filter #(and (:required? %)
                       (not (contains? parsed (:key %))))
                 opts)))

(defn- validate-value
  [spec value]
  (when-let [validation (:validate spec)]
    (when-not ((:pred validation) value)
      (:msg validation))))

(defn- validation-error
  [specs parsed]
  (first (keep (fn [spec]
                 (when (contains? parsed (:key spec))
                   (validate-value spec (get parsed (:key spec)))))
               specs)))

(defn- finalize-context
  [app command state]
  (let [arg-count (count (:args command))
        provided-count (count (:positionals state))]
    (cond
      (< provided-count arg-count)
      (error-result (str "Missing argument: "
                         (key-placeholder (:key (nth (:args command) provided-count)))))

      (> provided-count arg-count)
      (error-result "Too many arguments.")

      :else
      (let [args (into {} (map vector (map :key (:args command)) (:positionals state)))
            global (apply-defaults (:opts app) (:global state))
            opts (apply-defaults (:opts command) (:opts state))]
        (if-let [missing (or (required-option-missing (:opts app) global)
                             (required-option-missing (:opts command) opts))]
          (error-result (str "Missing required option: " (option-label missing)))
          (if-let [message (or (validation-error (:args command) args)
                               (validation-error (:opts app) global)
                               (validation-error (:opts command) opts))]
            (error-result message)
            {:global global
             :args args
             :opts opts}))))))

(defn root-help
  [app]
  (join-lines
    (concat [(str (:name app) " - " (:doc app))
             ""
             "Usage:"
             (str "  " (:name app) " [global options] <command> [args] [options]")
             (str "  " (:name app) " help [command]")
             (str "  " (:name app) " --help")
             ""
             "Global Options:"]
            (map format-option (:opts app))
            (root-option-built-ins app)
            ["" "Commands:"]
            (map format-command-row (:commands app))
            (root-command-built-ins app))))

(defn command-help
  [app command-name]
  (if-let [command (command-by-name app command-name)]
    (join-lines
      (concat [(str (command-usage-min app command) " - " (:doc command))
               ""
               "Usage:"
               (str "  " (command-usage app command))
               (str "  " (:name app) " help " (:name command))
               (command-option-built-ins (:name app) (:name command))]
              (when (seq (:args command))
                (concat ["" "Args:"]
                        (map format-arg (:args command))))
              (when (seq (:opts command))
                (concat ["" "Options:"]
                        (map format-option (:opts command))))
              (when (seq (:opts app))
                (concat ["" "Global Options:"]
                        (map format-option (:opts app))))))
    (str "Unknown command: " command-name)))

(defn- command-help-result
  [app command-name]
  (if-let [command (command-by-name app command-name)]
    {:status :help
     :command command
     :text (command-help app command-name)}
    (error-result (str "Unknown command: " command-name))))

(defn parse
  [app argv]
  (if-let [spec-error (app-spec-error app)]
    spec-error
    (let [global-longs (target-index :global (:opts app) long-index)
          global-shorts (target-index :global (:opts app) short-index)]
      (loop [tokens (vec argv)
             command nil
             state {:global {}
                    :opts {}
                    :positionals []}]
        (if (seq tokens)
          (let [token (first tokens)
                more (vec (rest tokens))]
            (cond
              (= "--version" token)
              (version-available app)

              (and (= "-v" token)
                   (nil? command)
                   (not (short-claimed? (:opts app) "v")))
              (version-available app)

              (and (nil? command)
                   (= "help" token))
              (if (seq more)
                (command-help-result app (first more))
                {:status :help
                 :command nil
                 :text (root-help app)})

              (and (nil? command)
                   (or (= "--help" token)
                       (= "-h" token)))
              {:status :help
               :command nil
               :text (root-help app)}

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
                  (if-let [spec-error (command-spec-error app selected)]
                    spec-error
                    (recur more selected state)))
                (error-result (str "Unknown command: " token)))

              (option-token? token)
              (let [all-longs (merge (target-index :global (:opts app) long-index)
                                     (target-index :opts (:opts command) long-index))
                    all-shorts (merge (target-index :global (:opts app) short-index)
                                      (target-index :opts (:opts command) short-index))
                    command-short-v? (short-claimed? (:opts command) "v")
                    parsed (parse-option-token state token more all-longs all-shorts)]
                (cond
                  (and (= "-v" token)
                       (not (short-claimed? (:opts app) "v"))
                       (not command-short-v?))
                  (version-available app)

                  (or (= "--help" token) (= "-h" token))
                  (command-help-result app (:name command))

                  (= :error (:status parsed))
                  parsed

                  :else
                  (recur (:tokens parsed) command (:state parsed))))

              :else
              (recur more command (assoc state :positionals (conj (:positionals state) token)))))
          (if command
            (let [context (finalize-context app command state)]
              (if (= :error (:status context))
                context
                {:status :ok
                 :command command
                 :context context}))
            {:status :help
             :command nil
             :text (root-help app)}))))))

(defn run-result
  [app argv]
  (let [result (parse app argv)]
    (if (= :ok (:status result))
      (assoc result :result ((:run (:command result)) (:context result)))
      result)))

(defn- write-out!
  [s]
  #?(:lg (write! *out* s)
     :default (print s)))

(defn- write-err!
  [s]
  #?(:lg (write! *err* s)
     :default (binding [*out* *err*] (print s))))

(defn- exit!
  [code]
  #?(:lg (os/exit code)
     :default (System/exit code)))

(defn run!
  [app argv]
  (let [result (run-result app argv)]
    (case (:status result)
      :ok (:result result)
      :help (do
              (write-out! (str (:text result) "\n"))
              (exit! 0))
      :version (do
                 (write-out! (str (:text result) "\n"))
                 (exit! 0))
      :error (do
               (write-err! (str (:message result) "\n"))
               (when (:text result)
                 (write-err! (str (:text result) "\n")))
               (exit! 2))
      result)))
