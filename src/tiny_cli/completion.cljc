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

        (and (str/starts-with? cur "-") (empty? positionals))
        (if (map? command)
          (concat (long-flags (:opts app))
                  (long-flags (:opts command))
                  ["--help"])
          (concat (long-flags (:opts app)) ["--help" "--version"]))

        (nil? command)
        (command-name-candidates app)

        (= :help command)
        (vec (remove #(= "help" %) (command-name-candidates app)))

        (map? command)
        (resolve-complete (:complete (positional-spec command (count positionals))) ctx)

        :else
        []))))
