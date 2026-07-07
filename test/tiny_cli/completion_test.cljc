(ns tiny-cli.completion-test
  (:require #?(:lg [string :as str]
               :default [clojure.string :as str])
            #?(:lg [test :as test :refer [deftest is testing run-tests]]
               :default [clojure.test :as test :refer [deftest is testing run-tests]])
            [tiny-cli.completion :as completion]))

;; A sample app exercising every completion path. :run is irrelevant here:
;; `candidates` only introspects names, opts, args, and :complete.
(def app
  {:name "wtr"
   :opts [{:key :base-dir
           :long "base-dir"
           :value? true}]
   :commands [{:name "list"}
              {:name "create"
               :args [{:key :name}]
               :opts [{:key :from
                       :short "f"
                       :long "from"
                       :value? true
                       :complete ["main" "dev"]}]}
              {:name "remove"
               :args [{:key :name
                       :complete (fn [_ctx] ["feat-x" "feature/bar"])}]
               :opts [{:key :force
                       :long "force"}]}
              {:name "run"
               :args [{:key :name}]
               :variadic {:key :cmd}}]})

(deftest candidates-commands
  (testing "empty input lists every command plus help"
    (is (= ["list" "create" "remove" "run" "help"]
           (completion/candidates app [] ""))))

  (testing "prefix filters command names"
    (is (= ["create"] (completion/candidates app [] "cr"))))

  (testing "help completes command names, minus help itself"
    (is (= ["list" "create" "remove" "run"]
           (completion/candidates app ["help"] "")))
    (is (= ["run"] (completion/candidates app ["help"] "ru"))))

  (testing "help offers nothing once its command arg is filled"
    (is (= [] (completion/candidates app ["help" "list"] ""))))

  (testing "unknown command completes nothing"
    (is (= [] (completion/candidates app ["bogus"] "")))))

(deftest candidates-flags
  (testing "root flags when the word starts with a dash"
    (is (= ["--base-dir" "--help" "--version"]
           (completion/candidates app [] "-"))))

  (testing "command flags include globals, the command's own, and help"
    (is (= ["--base-dir" "--force" "--help"]
           (completion/candidates app ["remove"] "-"))))

  (testing "flags after a positional on a regular command"
    (is (= ["--base-dir" "--force" "--help"]
           (completion/candidates app ["remove" "feat-x"] "-"))))

  (testing "no flags after the first positional on a variadic command"
    (is (= [] (completion/candidates app ["run" "feat-x"] "-"))))

  (testing "flags before the first positional on a variadic command still complete"
    (is (= ["--base-dir" "--help"]
           (completion/candidates app ["run"] "-")))))

(deftest candidates-positional-complete
  (testing "a fn :complete supplies the positional candidates"
    (is (= ["feat-x" "feature/bar"] (completion/candidates app ["remove"] "")))
    (is (= ["feat-x" "feature/bar"] (completion/candidates app ["remove"] "fea")))
    (is (= ["feature/bar"] (completion/candidates app ["remove"] "feature"))))

  (testing "a boolean flag does not consume the positional slot"
    (is (= ["feat-x" "feature/bar"]
           (completion/candidates app ["remove" "--force"] ""))))

  (testing "an arg without :complete offers nothing"
    (is (= [] (completion/candidates app ["create"] ""))))

  (testing "the second positional offers nothing"
    (is (= [] (completion/candidates app ["remove" "feat-x"] ""))))

  (testing "the variadic tail offers nothing (shell falls back to files)"
    (is (= [] (completion/candidates app ["run" "feat-x"] "")))))

;; Variadic payload counting: once a variadic command has its first positional,
;; every remaining word is payload — even dash words and value-option spellings.
(def variadic-app
  {:name "vly"
   :opts [{:key :base-dir
           :long "base-dir"
           :value? true
           :complete ["/tmp" "/srv"]}]
   :commands [{:name "deploy"
               :args [{:key :service}
                      {:key :env
                       :complete ["dev" "prod"]}]
               :variadic {:key :cmd}}
              {:name "run"
               :args [{:key :name}]
               :variadic {:key :cmd}}]})

(deftest candidates-variadic-payload
  (testing "a dash word in the variadic payload counts as a positional"
    (is (= [] (completion/candidates variadic-app ["deploy" "svc" "-x"] ""))))

  (testing "a value-option spelling inside the payload does not swallow the next word"
    (is (= [] (completion/candidates variadic-app ["run" "feat-x" "--base-dir"] "")))))

(deftest candidates-option-value
  (testing "a value-taking long option completes its :complete"
    (is (= ["main" "dev"] (completion/candidates app ["create" "--from"] "")))
    (is (= ["main"] (completion/candidates app ["create" "--from"] "m"))))

  (testing "a value-taking short option resolves the same slot"
    (is (= ["main"] (completion/candidates app ["create" "-f"] "m")))))

(deftest completion-scripts
  (testing "every supported shell yields a script that calls __complete"
    (doseq [shell ["bash" "zsh" "fish"]]
      (let [s (completion/script {:name "wtr"} shell)]
        (is (not (str/blank? s)))
        (is (str/includes? s "__complete")))))

  (testing "the app name is sanitized for shell function ids but kept literal for registration"
    (let [s (completion/script {:name "my-tool"} "bash")]
      (is (str/includes? s "_my_tool_complete"))
      (is (str/includes? s "-F _my_tool_complete my-tool"))))

  (testing "an unknown shell yields nil"
    (is (nil? (completion/script {:name "wtr"} "powershell")))))

(defn- find-completion-command
  [installed]
  (first (filter #(= "completion" (:name %)) (:commands installed))))

(deftest install-command-injection
  (testing "appends a hidden completion command when absent"
    (let [cmd (find-completion-command (completion/install-command app))]
      (is (some? cmd))
      (is (:hidden? cmd))
      (is (fn? (:run cmd)))
      (let [shell-arg (first (:args cmd))]
        (is (= ["bash" "zsh" "fish"] (:complete shell-arg)))
        (is (map? (:validate shell-arg))))))

  (testing "is a no-op when the app already defines a completion command"
    (let [custom (update app :commands conj {:name "completion"
                                             :run (fn [_ctx] :custom)})
          installed (completion/install-command custom)
          completions (filter #(= "completion" (:name %)) (:commands installed))]
      (is (= 1 (count completions)))
      (is (= :custom ((:run (first completions)) {})))))

  (testing "is a no-op when :completion? is false"
    (is (nil? (find-completion-command
                (completion/install-command (assoc app :completion? false))))))

  (testing "candidates on the installed app offers completion and its shells"
    (let [installed (completion/install-command app)]
      (is (= ["bash" "zsh" "fish"] (completion/candidates installed ["completion"] "")))
      (is (some #(= "completion" %) (completion/candidates installed [] ""))))))

#?(:lg
   (do)
   :default
   (let [result (run-tests)]
     (when (pos? (+ (:fail result) (:error result)))
       (System/exit 1))))
