(ns tiny-cli.core
  #?(:lg (:refer-clojure :exclude [run!])
     :clj (:refer-clojure :exclude [run!]))
  (:require #?(:lg [os])
            #?(:lg [string :as str]
               :default [clojure.string :as str])
            [tiny-cli.completion :as completion]))

(defn- join-lines
  [lines]
  (str/join "\n" (filter some? lines)))

(defn- command-by-name
  [app command-name]
  (first (filter #(= command-name (:name %)) (:commands app))))

(defn- visible-commands
  [app]
  (remove :hidden? (:commands app)))

(defn- summary-line
  [heading doc]
  (if (seq doc)
    (str heading " - " doc)
    heading))

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

(defn- pad-right
  [s width]
  (str s (apply str (repeat (- width (count s)) " "))))

(defn- align-rows
  "Render [label doc] pairs as indented rows with docs aligned to a shared
   column (two-space gutter after the widest label). Rows without a doc emit
   just the indented label, so no trailing whitespace."
  [pairs]
  (let [width (reduce max 0 (map (comp count first) pairs))]
    (map (fn [[label doc]]
           (if (seq doc)
             (str "  " (pad-right label width) "  " doc)
             (str "  " label)))
         pairs)))

(defn- option-row
  [opt]
  [(option-label opt) (option-doc opt)])

(defn- arg-row
  [arg]
  [(key-placeholder (:key arg)) (:doc arg)])

(defn- arg-placeholder
  [arg]
  (str "<" (key-placeholder (:key arg)) ">"))

(defn- variadic-placeholder
  [variadic]
  (str "[" (key-placeholder (:key variadic)) "...]"))

(defn- arg-specs
  "Fixed positional args plus the optional variadic spec, as one sequence."
  [command]
  (concat (:args command)
          (when (:variadic command) [(:variadic command)])))

(defn- command-usage
  [app command]
  (let [args (map arg-placeholder (:args command))]
    (str/join " " (filter some?
                          [(:name app)
                           (when (seq (:opts app))
                             "[global options]")
                           (:name command)
                           (when (seq (:opts command))
                             "[options]")
                           (when (seq args)
                             (str/join " " args))
                           (when (:variadic command)
                             (variadic-placeholder (:variadic command)))]))))

(defn- command-usage-min
  [app command]
  (let [args (map arg-placeholder (:args command))]
    (str/join " " (filter some?
                          [(:name app)
                           (:name command)
                           (when (seq args)
                             (str/join " " args))
                           (when (:variadic command)
                             (variadic-placeholder (:variadic command)))]))))

(defn- root-command-usage-rows
  "Compact `[usage doc]` rows for user commands, e.g. `app create <BRANCH>`."
  [app]
  (map (fn [command]
         [(command-usage-min app command) (:doc command)])
       (visible-commands app)))

(defn- root-usage-built-in-rows
  "Compact `[usage doc]` rows for built-in help/version invocations."
  [app]
  (concat [[(str (:name app) " help [command]") "Show a command help."]
           [(str (:name app) " --help") "Show the tool help."]]
          (when (:version app)
            [[(str (:name app) " --version") "Print version."]])))

(defn- footer-lines
  [app]
  (when (seq (:footer app))
    ["" (:footer app)]))

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

;; `help` is always built in, and `__complete` is the hidden completion RPC token
;; that `run!` intercepts before parsing — so neither may be a user command. Note
;; `completion` is intentionally NOT reserved: an app may define its own to
;; override the built-in (see tiny-cli.completion/install-command).
(def ^:private reserved-command-names #{"help" "__complete"})

(def ^:private reserved-option-spellings #{"--help" "-h" "--version"})

(defn- option-spellings-valid?
  [opts]
  (every? #(or (:short %) (:long %)) opts))

(defn- first-invalid-short
  [opts]
  (first (filter #(when-let [spelling (:short %)]
                    (not= 1 (count spelling)))
                 opts)))

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

(defn- first-reserved-spelling
  [opts]
  (first (filter reserved-option-spellings (option-spellings opts))))

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

(defn- bad-complete?
  [complete]
  (not (or (fn? complete)
           (and (sequential? complete) (every? string? complete)))))

(defn- first-invalid-complete
  [specs]
  (first (filter #(and (contains? % :complete)
                       (bad-complete? (:complete %)))
                 specs)))

(defn- command-spec-error
  [command]
  (cond
    (nil? (:name command))
    (error-result "Command requires :name.")

    (contains? reserved-command-names (:name command))
    (error-result (str "Reserved command name: " (:name command)))

    (nil? (:run command))
    (error-result (str "Missing command runner: " (:name command)))

    (first-missing-key (arg-specs command))
    (error-result "Arg requires :key.")

    (first-missing-key (:opts command))
    (error-result "Option requires :key.")

    (first-duplicate-key (arg-specs command))
    (error-result (str "Duplicate arg key: " (first-duplicate-key (arg-specs command))))

    (first-duplicate-key (:opts command))
    (error-result (str "Duplicate option key: " (first-duplicate-key (:opts command))))

    (not (option-spellings-valid? (:opts command)))
    (error-result "Option requires :short or :long.")

    (first-invalid-short (:opts command))
    (error-result (str "Short option must be a single character: " (:short (first-invalid-short (:opts command)))))

    (first-reserved-spelling (:opts command))
    (error-result (str "Reserved option spelling: " (first-reserved-spelling (:opts command))))

    (duplicate-in (option-spellings (:opts command)))
    (error-result (str "Duplicate option spelling: " (duplicate-in (option-spellings (:opts command)))))

    (first-invalid-validation (concat (arg-specs command) (:opts command)))
    (error-result "Invalid validation spec.")

    (first-invalid-complete (concat (arg-specs command) (:opts command)))
    (error-result "Invalid :complete spec.")))

(defn- first-command-spec-error
  [app]
  (first (keep command-spec-error (:commands app))))

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

    (first-invalid-short (:opts app))
    (error-result (str "Short option must be a single character: " (:short (first-invalid-short (:opts app)))))

    (first-reserved-spelling (:opts app))
    (error-result (str "Reserved option spelling: " (first-reserved-spelling (:opts app))))

    (duplicate-in (option-spellings (:opts app)))
    (error-result (str "Duplicate option spelling: " (duplicate-in (option-spellings (:opts app)))))

    (first-invalid-validation (:opts app))
    (error-result "Invalid validation spec.")

    (first-invalid-complete (:opts app))
    (error-result "Invalid :complete spec.")

    (first-option-conflict app)
    (error-result (str "Option conflict: " (first-option-conflict app)))

    (and (contains? app :run) (not (fn? (:run app))))
    (error-result "App :run must be a function.")

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
  (let [fixed-specs (:args command)
        variadic (:variadic command)
        arg-count (count fixed-specs)
        positionals (:positionals state)
        provided-count (count positionals)]
    (cond
      (< provided-count arg-count)
      (error-result (str "Missing argument: "
                         (key-placeholder (:key (nth fixed-specs provided-count)))))

      (and (not variadic) (> provided-count arg-count))
      (error-result "Too many arguments.")

      :else
      (let [fixed (into {} (map vector (map :key fixed-specs) positionals))
            args (if variadic
                   (assoc fixed (:key variadic) (vec (drop arg-count positionals)))
                   fixed)
            global (apply-defaults (:opts app) (:global state))
            opts (apply-defaults (:opts command) (:opts state))]
        (if-let [missing (or (required-option-missing (:opts app) global)
                             (required-option-missing (:opts command) opts))]
          (error-result (str "Missing required option: " (option-label missing)))
          (if-let [message (or (validation-error (arg-specs command) args)
                               (validation-error (:opts app) global)
                               (validation-error (:opts command) opts))]
            (error-result message)
            {:global global
             :args args
             :opts opts}))))))

(defn root-help
  [app]
  (join-lines
    (concat [(summary-line (:name app) (:doc app))
             ""
             "Usage:"
             (str "  " (:name app) " [global options] <command> [options] [args]")
             ""
             "Commands:"]
            (align-rows (concat (root-command-usage-rows app)
                                (root-usage-built-in-rows app)))
            (when (seq (:opts app))
              (concat ["" "Global Options:"]
                      (align-rows (map option-row (:opts app)))))
            (footer-lines app))))

(defn command-help
  [app command-name]
  (if-let [command (command-by-name app command-name)]
    (join-lines
      (concat [(summary-line (command-usage-min app command) (:doc command))
               ""
               "Usage:"
               (str "  " (command-usage app command))
               (str "  " (:name app) " help " (:name command))
               (command-option-built-ins (:name app) (:name command))]
              (when (seq (arg-specs command))
                (concat ["" "Args:"]
                        (align-rows (map arg-row (arg-specs command)))))
              (when (seq (:opts command))
                (concat ["" "Options:"]
                        (align-rows (map option-row (:opts command)))))
              (when (seq (:opts app))
                (concat ["" "Global Options:"]
                        (align-rows (map option-row (:opts app)))))))
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
    (let [app (completion/install-command app)
          global-longs (target-index :global (:opts app) long-index)
          global-shorts (target-index :global (:opts app) short-index)]
      (loop [tokens (vec argv)
             command nil
             state {:global {}
                    :opts {}
                    :positionals []
                    :phase :options}]
        (if (seq tokens)
          (let [token (first tokens)
                more (vec (rest tokens))]
            (cond
              ;; Arguments phase: every remaining token is positional, taken
              ;; verbatim. `:args` is variadic rest-mode (entered at a variadic
              ;; command's first positional); `:args-raw` is entered via `--`.
              ;; A non-variadic command never reaches this branch: it stays in
              ;; `:options`, interleaving options and positionals freely.
              (not= :options (:phase state))
              (recur more command (update state :positionals conj token))

              (= "--version" token)
              (version-available app)

              (and (= "-v" token)
                   (nil? command)
                   (not (short-claimed? (:opts app) "v")))
              (version-available app)

              (and (nil? command)
                   (= "help" token))
              (cond
                (empty? more)
                {:status :help
                 :command nil
                 :text (root-help app)}

                (= 1 (count more))
                (command-help-result app (first more))

                :else
                (error-result "Too many arguments for help."))

              (and (nil? command)
                   (or (= "--help" token)
                       (= "-h" token)))
              {:status :help
               :command nil
               :text (root-help app)}

              (and (nil? command) (= "--" token))
              (error-result "Unexpected end-of-options before command.")

              (and (nil? command) (option-token? token))
              (let [parsed (parse-option-token state token more global-longs global-shorts)]
                (if (= :error (:status parsed))
                  parsed
                  (recur (:tokens parsed) command (:state parsed))))

              (nil? command)
              (if-let [selected (command-by-name app token)]
                (if-let [conflict (option-conflict (:opts app) (:opts selected))]
                  (error-result (str "Option conflict: " conflict))
                  (if-let [spec-error (command-spec-error selected)]
                    spec-error
                    (recur more selected state)))
                (error-result (str "Unknown command: " token)))

              ;; Command selected, still in the options phase: `--` ends options.
              (= "--" token)
              (recur more command (assoc state :phase :args-raw))

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

              ;; Positional token. A variadic command's first positional starts
              ;; rest-mode: everything after it is payload, so options must come
              ;; before it. A non-variadic command stays in the options phase,
              ;; letting options appear before, between, or after positionals.
              :else
              (recur more command (cond-> (update state :positionals conj token)
                                    (:variadic command) (assoc :phase :args)))))
          (if command
            (let [context (finalize-context app command state)]
              (if (= :error (:status context))
                context
                {:status :ok
                 :command command
                 :context context}))
            ;; No command named. Dispatch the app's root handler when it has
            ;; one; otherwise fall back to root help (the default). Every
            ;; help/version path returns from inside the loop above, so only a
            ;; genuinely bare invocation — every token consumed as a global
            ;; option — reaches here.
            (if (:run app)
              (let [context (finalize-context app {} state)]
                (if (= :error (:status context))
                  context
                  {:status :ok
                   :command {:run (:run app)}
                   :context context}))
              {:status :help
               :command nil
               :text (root-help app)})))))))

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
  (if (and (not (false? (:completion? app)))
           (= "__complete" (first argv)))
    (do
      (completion/complete! app (rest argv))
      (exit! 0))
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
        result))))
