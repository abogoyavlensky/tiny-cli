(ns tiny-cli.completion
  (:require #?(:lg [string :as str]
               :default [clojure.string :as str])))

(defn- find-command
  [app command-name]
  (some #(when (= (:name %) command-name) %) (:commands app)))

(defn- visible-command-names
  [app]
  (mapv :name (remove :hidden? (:commands app))))

(defn- long-flags
  [opts]
  (vec (keep (fn [o] (when (:long o) (str "--" (:long o)))) opts)))

(defn- value-option-index
  "Map of every value-taking option spelling (long and short) from the global
   and command scopes to its spec. Used to detect when the cursor sits on an
   option's value."
  [app command]
  (reduce (fn [idx o]
            (if (:value? o)
              (let [idx (if (:long o) (assoc idx (str "--" (:long o)) o) idx)]
                (if (:short o) (assoc idx (str "-" (:short o)) o) idx))
              idx))
          {}
          (concat (:opts app) (:opts command))))

(defn- command-name-candidates
  "Visible command names, plus the built-in `completion` when present, plus
   `help`, de-duplicated. Hidden user commands stay hidden; the injected
   (hidden) completion command is still offered."
  [app]
  (let [base (visible-command-names app)
        with-completion (if (find-command app "completion")
                          (conj base "completion")
                          base)]
    (vec (distinct (conj with-completion "help")))))

(defn- positional-spec
  "The arg spec governing the positional at index `npos`: the Nth fixed arg,
   else the variadic once the fixed args are filled, else nil."
  [command npos]
  (let [fixed (vec (:args command))]
    (cond
      (< npos (count fixed)) (nth fixed npos)
      (:variadic command) (:variadic command)
      :else nil)))

(defn- resolve-complete
  "Candidate strings from a spec's :complete: a fn called with ctx, a seq used
   as-is, or nothing."
  [complete ctx]
  (cond
    (nil? complete) []
    (fn? complete) (or (complete ctx) [])
    (sequential? complete) complete
    :else []))

(defn- split-context
  "Classify the words typed before the cursor. Returns
   {:command <spec | :help | :unknown | nil>, :positionals [..],
    :awaiting-option <opt-spec | nil>}. A value-taking option consumes the next
   word; when it is the last word the cursor sits on its value
   (:awaiting-option). The first non-option word selects the command."
  [app words]
  (loop [ws (seq words)
         command nil
         positionals []]
    (if (empty? ws)
      {:command command
       :positionals positionals
       :awaiting-option nil}
      (let [w (first ws)
            more (rest ws)
            value-opt (get (value-option-index app command) w)]
        (cond
          value-opt
          (if (empty? more)
            {:command command
             :positionals positionals
             :awaiting-option value-opt}
            (recur (rest more) command positionals))

          (str/starts-with? w "-")
          (recur more command positionals)

          (nil? command)
          (recur more
                 (or (find-command app w)
                     (if (= w "help") :help :unknown))
                 positionals)

          :else
          (recur more command (conj positionals w)))))))

(defn candidates
  "Prefix-filtered completion candidates for `cur`, given the words typed before
   the cursor (app name excluded). Deterministic given the app's :complete
   completers; an empty result means the shell should fall back to its default
   (filename) completion."
  [app words cur]
  (let [{:keys [command positionals awaiting-option]} (split-context app words)
        ctx {:words words
             :cur cur
             :command command
             :positionals positionals}]
    (filterv
      #(str/starts-with? % cur)
      (cond
        awaiting-option
        (resolve-complete (:complete awaiting-option) ctx)

        (= :unknown command)
        []

        ;; Flags: always offered on a non-variadic command (options interleave
        ;; with positionals); on a variadic command only before the first
        ;; positional, since after it every word is payload.
        (and (str/starts-with? cur "-")
             (or (empty? positionals)
                 (and (map? command) (not (:variadic command)))))
        (if (map? command)
          (concat (long-flags (:opts app))
                  (long-flags (:opts command))
                  ["--help"])
          (concat (long-flags (:opts app)) ["--help" "--version"]))

        (nil? command)
        (command-name-candidates app)

        (= :help command)
        (if (empty? positionals)
          (vec (remove #(= "help" %) (command-name-candidates app)))
          [])

        (map? command)
        (resolve-complete (:complete (positional-spec command (count positionals))) ctx)

        :else
        []))))

;; Shell-script generation. Each script is built from a vector of literal lines
;; (bulletproof for embedded quotes and the bash `$'\n'` backslash) with the app
;; name interpolated two ways: `name` verbatim for registration and invocation,
;; and `id` — a safe shell identifier — for function names.

(defn- sanitize
  "An app name reduced to a valid shell identifier: every character outside
   [A-Za-z0-9_] becomes `_` (so `my-tool` -> `my_tool`)."
  [s]
  (str/replace s #"[^A-Za-z0-9_]" "_"))

(defn- lines->script
  [lines]
  (str (str/join "\n" lines) "\n"))

(defn- bash-script
  [name id]
  (lines->script
    [(str "# bash completion for " name ".")
     (str "# Load with: source <(" name " completion bash)")
     (str "_" id "_complete() {")
     "    local cur candidates"
     "    cur=\"${COMP_WORDS[COMP_CWORD]}\""
     "    candidates=\"$(\"${COMP_WORDS[0]}\" __complete \"${COMP_WORDS[@]:1:COMP_CWORD}\" 2>/dev/null)\""
     "    local IFS=$'\\n'"
     "    COMPREPLY=($(compgen -W \"$candidates\" -- \"$cur\"))"
     "}"
     (str "# -o default: fall back to filename completion when " name " offers nothing.")
     (str "complete -o default -F _" id "_complete " name)]))

(defn- zsh-script
  [name id]
  (lines->script
    [(str "#compdef " name)
     (str "# zsh completion for " name ".")
     (str "# Load with: source <(" name " completion zsh)")
     (str "# or save on your fpath: " name " completion zsh > ~/.zfunc/_" id)
     (str "_" id "() {")
     "    local -a candidates"
     "    candidates=(\"${(@f)$(\"${words[1]}\" __complete \"${(@)words[2,CURRENT]}\" 2>/dev/null)}\")"
     "    if (( ${#candidates[@]} )) && [[ -n \"${candidates[1]}\" ]]; then"
     "        compadd -Q -a candidates"
     "    else"
     "        _default"
     "    fi"
     "}"
     (str "# On fpath, #compdef invokes _" id " for us; when sourced, register it manually.")
     (str "if [[ \"${funcstack[1]}\" == \"_" id "\" ]]; then")
     (str "    _" id " \"$@\"")
     "else"
     (str "    compdef _" id " " name)
     "fi"]))

(defn- fish-script
  [name id]
  (lines->script
    [(str "# fish completion for " name ".")
     (str "# Load with: " name " completion fish | source")
     (str "# or save it: " name " completion fish > ~/.config/fish/completions/" name ".fish")
     (str "function __" id "_complete")
     "    set -l words (commandline -opc)"
     "    set -l cur (commandline -ct)"
     (str "    set -g __" id "_candidates ($words[1] __complete $words[2..-1] \"$cur\" 2>/dev/null)")
     (str "    test (count $__" id "_candidates) -gt 0")
     "end"
     (str "complete -c " name " -f -n '__" id "_complete' -a '$__" id "_candidates'")
     (str "complete -c " name " -n 'not __" id "_complete' -F")]))

(defn script
  "The completion script for `shell` (\"bash\", \"zsh\", or \"fish\"), or nil for
   an unknown shell."
  [app shell]
  (let [name (:name app)
        id (sanitize name)]
    (case shell
      "bash" (bash-script name id)
      "zsh" (zsh-script name id)
      "fish" (fish-script name id)
      nil)))

(def ^:private shells ["bash" "zsh" "fish"])

(defn- valid-shell?
  [s]
  (some #(= s %) shells))

(defn- completion-command
  "The hidden built-in `completion <shell>` command. Closes over `app` for its
   name; the shell arg is validated, so :run always receives a supported shell."
  [app]
  {:name "completion"
   :doc "Print a shell completion script (bash, zsh, or fish)."
   :hidden? true
   :args [{:key :shell
           :doc "Shell name: bash, zsh, or fish."
           :validate {:pred valid-shell?
                      :msg "Shell must be one of: bash, zsh, fish."}
           :complete shells}]
   :run (fn [ctx] (print (script app (:shell (:args ctx)))))})

(defn install-command
  "Append the built-in hidden `completion` command to `app`, unless completion is
   disabled (`:completion? false`) or the app already defines its own
   `completion` command. Idempotent."
  [app]
  (if (or (false? (:completion? app))
          (find-command app "completion"))
    app
    (update app :commands conj (completion-command app))))

(defn complete!
  "I/O entry point for the hidden `<app> __complete` call the shell scripts make.
   `argv` is the words typed after `__complete`; the last is the word under the
   cursor (possibly empty). Prints one candidate per line. Never throws: a broken
   completer must not break the user's shell, so on any error it prints nothing."
  [app argv]
  (try
    (let [app (install-command app)
          words (vec (butlast argv))
          cur (or (last argv) "")]
      (doseq [c (candidates app words cur)]
        (println c)))
    (catch Exception _ nil)))
