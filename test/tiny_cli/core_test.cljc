(ns tiny-cli.core-test
  (:require #?(:lg [os])
            #?(:lg [string :as str]
               :default [clojure.string :as str])
            #?(:lg [test :as test :refer [deftest is testing run-tests]]
               :default [clojure.test :as test :refer [deftest is testing run-tests]])
            [tiny-cli.core :as cli]))

(defn create! [_ctx] :created)

(def app
  {:name "wtr"
   :version "0.1.0"
   :doc "Small git worktree helper."
   :opts [{:key :verbose?
           :short "v"
           :long "verbose"
           :doc "Print executed commands."}]
   :commands [{:name "create"
               :doc "Create a worktree for a branch."
               :args [{:key :branch
                       :doc "Branch name."}]
               :opts [{:key :base
                       :short "b"
                       :long "base"
                       :value? true
                       :default "master"
                       :doc "Base branch."}]
               :run create!}]})

(deftest public-contract
  (testing "root-help returns text for the app"
    (let [text (cli/root-help app)]
      (is (string? text))
      (is (some? (re-find #"wtr" text)))))

  (testing "command-help returns text for a command"
    (let [text (cli/command-help app "create")]
      (is (string? text))
      (is (some? (re-find #"create" text)))))

  (testing "parse returns tagged version result"
    (let [result (cli/parse app ["--version"])]
      (is (map? result))
      (is (= :version (:status result)))
      (is (= "wtr 0.1.0" (:text result))))))

(deftest help-rendering
  (testing "root help renders app sections"
    (let [text (cli/root-help app)]
      (is (some? (re-find #"wtr" text)))
      (is (some? (re-find #"Small git worktree helper\." text)))
      (is (some? (re-find #"Usage:" text)))
      (is (some? (re-find #"Global Options:" text)))
      (is (some? (re-find #"-v, --verbose" text)))
      (is (some? (re-find #"Commands:" text)))
      (is (some? (re-find #"create" text)))
      (is (some? (re-find #"--version" text)))))

  (testing "command help renders command sections"
    (let [text (cli/command-help app "create")]
      (is (some? (re-find #"wtr create <BRANCH>" text)))
      (is (some? (re-find #"Create a worktree for a branch\." text)))
      (is (some? (re-find #"Args:" text)))
      (is (some? (re-find #"BRANCH" text)))
      (is (some? (re-find #"Options:" text)))
      (is (some? (re-find #"-b, --base BASE" text)))
      (is (some? (re-find #"Default: master" text)))
      (is (some? (re-find #"Global Options:" text)))
      (is (some? (re-find #"-v, --verbose" text)))
      (is (nil? (re-find #"--version" text))))))

(def align-app
  {:name "wtr"
   :version "0.1.0"
   :opts [{:key :verbose?
           :short "v"
           :long "verbose"
           :doc "Print executed commands."}]
   :commands [{:name "ls"
               :doc "List things."}
              {:name "create-branch"
               :doc "Create a branch."}]})

(deftest help-doc-alignment
  (testing "command docs align to a shared column in root help"
    (let [lines (str/split (cli/root-help align-app) #"\n")
          row (fn [prefix] (first (filter #(str/starts-with? % prefix) lines)))
          ls-line (row "  ls ")
          cb-line (row "  create-branch")
          help-line (row "  help [command]")]
      ;; widest label is "help [command]" (14) => docs start at column 18
      (is (= 18 (str/index-of ls-line "List things.")))
      (is (= 18 (str/index-of cb-line "Create a branch.")))
      (is (= 18 (str/index-of help-line "Show help.")))
      ;; short label is padded: "ls" + 12 pad + 2 gutter = 14 spaces before doc
      (is (some? (re-find #"^  ls {14}List things\.$" ls-line)))))

  (testing "option docs align to a shared column in root help"
    (let [lines (str/split (cli/root-help align-app) #"\n")
          row (fn [prefix] (first (filter #(str/starts-with? % prefix) lines)))
          verbose-line (row "  -v, --verbose")
          help-line (row "  -h, --help")
          version-line (row "  --version")]
      ;; widest label is "-v, --verbose" (13) => docs start at column 17
      (is (= 17 (str/index-of verbose-line "Print executed commands.")))
      (is (= 17 (str/index-of help-line "Show help.")))
      (is (= 17 (str/index-of version-line "Print version."))))))

(def bool-app
  {:name "pkg"
   :version "1.2.3"
   :commands [{:name "ship"
               :doc "Ship a package."
               :args [{:key :box}]
               :opts [{:key :force?
                       :short "f"
                       :long "force"}
                      {:key :dry-run?
                       :short "d"
                       :long "dry-run"}]
               :run create!}]})

(deftest option-parsing
  (testing "parses global option before command"
    (let [result (cli/parse app ["-v" "create" "feature/login"])]
      (is (= :ok (:status result)))
      (is (= {:verbose? true} (get-in result [:context :global])))
      (is (= {:branch "feature/login"} (get-in result [:context :args])))))

  (testing "parses global option after command"
    (let [result (cli/parse app ["create" "feature/login" "--verbose"])]
      (is (= :ok (:status result)))
      (is (= {:verbose? true} (get-in result [:context :global])))))

  (testing "parses command option before and after positional args"
    (let [before (cli/parse app ["create" "--base" "main" "feature/login"])
          after (cli/parse app ["create" "feature/login" "--base" "main"])]
      (is (= :ok (:status before)))
      (is (= "main" (get-in before [:context :opts :base])))
      (is (= :ok (:status after)))
      (is (= "main" (get-in after [:context :opts :base])))))

  (testing "parses long value option with equals"
    (let [result (cli/parse app ["create" "feature/login" "--base=main"])]
      (is (= :ok (:status result)))
      (is (= "main" (get-in result [:context :opts :base])))))

  (testing "parses short value option with space"
    (let [result (cli/parse app ["create" "-b" "main" "feature/login"])]
      (is (= :ok (:status result)))
      (is (= "main" (get-in result [:context :opts :base])))))

  (testing "parses combined short booleans"
    (let [result (cli/parse bool-app ["ship" "-fd" "box-1"])]
      (is (= :ok (:status result)))
      (is (= {:force? true
              :dry-run? true}
             (get-in result [:context :opts])))))

  (testing "parses mixed global and command short booleans after command"
    (let [mixed-app {:name "mix"
                     :opts [{:key :verbose?
                             :short "v"
                             :long "verbose"}]
                     :commands [{:name "ship"
                                 :args [{:key :box}]
                                 :opts [{:key :force?
                                         :short "f"
                                         :long "force"}]
                                 :run create!}]}
          result (cli/parse mixed-app ["ship" "-vf" "box-1"])]
      (is (= :ok (:status result)))
      (is (= {:verbose? true} (get-in result [:context :global])))
      (is (= {:force? true} (get-in result [:context :opts])))))

  (testing "global and command option spelling collision is an error"
    (let [conflict-app {:name "mix"
                        :opts [{:key :global-force?
                                :short "f"
                                :long "force"}]
                        :commands [{:name "ship"
                                    :args [{:key :box}]
                                    :opts [{:key :force?
                                            :short "f"
                                            :long "force"}]
                                    :run create!}]}
          result (cli/parse conflict-app ["ship" "box-1"])]
      (is (= :error (:status result)))
      (is (some? (re-find #"Option conflict" (:message result))))))

  (testing "unknown option is an error"
    (let [result (cli/parse app ["create" "feature/login" "--unknown"])]
      (is (= :error (:status result)))
      (is (some? (re-find #"Unknown option" (:message result))))))

  (testing "missing option value is an error"
    (let [result (cli/parse app ["create" "feature/login" "--base"])]
      (is (= :error (:status result)))
      (is (some? (re-find #"Missing value" (:message result))))))

  (testing "end-of-options treats following values as positional"
    (let [literal-app {:name "lit"
                       :commands [{:name "show"
                                   :args [{:key :value}]
                                   :run create!}]}
          result (cli/parse literal-app ["show" "--" "--not-an-option"])]
      (is (= :ok (:status result)))
      (is (= {:value "--not-an-option"}
             (get-in result [:context :args]))))))

(defn non-blank?
  [s]
  (not= "" s))

(deftest command-args-defaults-and-validation
  (testing "unknown command is an error"
    (let [result (cli/parse app ["missing"])]
      (is (= :error (:status result)))
      (is (some? (re-find #"Unknown command" (:message result))))))

  (testing "too few positional args is an error"
    (let [result (cli/parse app ["create"])]
      (is (= :error (:status result)))
      (is (some? (re-find #"Missing argument" (:message result))))))

  (testing "too many positional args is an error"
    (let [result (cli/parse app ["create" "one" "two"])]
      (is (= :error (:status result)))
      (is (some? (re-find #"Too many arguments" (:message result))))))

  (testing "option defaults are applied"
    (let [result (cli/parse app ["create" "feature/login"])]
      (is (= :ok (:status result)))
      (is (= {:base "master"} (get-in result [:context :opts])))))

  (testing "required options must be provided"
    (let [token-app {:name "api"
                     :commands [{:name "call"
                                 :args [{:key :path}]
                                 :opts [{:key :token
                                         :long "token"
                                         :value? true
                                         :required? true}]
                                 :run create!}]}
          result (cli/parse token-app ["call" "/v1"])]
      (is (= :error (:status result)))
      (is (some? (re-find #"Missing required option" (:message result))))))

  (testing "validation passes and fails with supplied message"
    (let [valid-app {:name "check"
                     :commands [{:name "branch"
                                 :args [{:key :name
                                         :validate {:pred non-blank?
                                                    :msg "NAME is required."}}]
                                 :opts [{:key :base
                                         :long "base"
                                         :value? true
                                         :validate {:pred non-blank?
                                                    :msg "BASE is required."}}]
                                 :run create!}]}
          ok (cli/parse valid-app ["branch" "feature" "--base" "main"])
          bad-arg (cli/parse valid-app ["branch" "" "--base" "main"])
          bad-opt (cli/parse valid-app ["branch" "feature" "--base" ""])]
      (is (= :ok (:status ok)))
      (is (= :error (:status bad-arg)))
      (is (= "NAME is required." (:message bad-arg)))
      (is (= :error (:status bad-opt)))
      (is (= "BASE is required." (:message bad-opt)))))

  (testing "malformed validation spec is an error"
    (let [bad-app {:name "bad"
                   :commands [{:name "go"
                               :args [{:key :name
                                       :validate {:msg "Missing predicate."}}]
                               :run create!}]}
          result (cli/parse bad-app ["go" "x"])]
      (is (= :error (:status result)))
      (is (some? (re-find #"Invalid validation" (:message result))))))

  (testing "missing command run is a spec error"
    (let [bad-app {:name "bad"
                   :commands [{:name "go"}]}
          result (cli/parse bad-app ["go"])]
      (is (= :error (:status result)))
      (is (some? (re-find #"Missing command runner" (:message result))))))

  (testing "missing option spelling is a spec error"
    (let [bad-app {:name "bad"
                   :commands [{:name "go"
                               :opts [{:key :force?}]
                               :run create!}]}
          result (cli/parse bad-app ["go"])]
      (is (= :error (:status result)))
      (is (some? (re-find #"Option requires" (:message result))))))

  (testing "missing arg or option key is a spec error"
    (let [bad-arg-app {:name "bad"
                       :commands [{:name "go"
                                   :args [{}]
                                   :run create!}]}
          bad-opt-app {:name "bad"
                       :commands [{:name "go"
                                   :opts [{:long "force"}]
                                   :run create!}]}
          bad-arg (cli/parse bad-arg-app ["go" "x"])
          bad-opt (cli/parse bad-opt-app ["go" "--force"])]
      (is (= :error (:status bad-arg)))
      (is (some? (re-find #"Arg requires :key" (:message bad-arg))))
      (is (= :error (:status bad-opt)))
      (is (some? (re-find #"Option requires :key" (:message bad-opt))))))

  (testing "duplicate arg keys and option keys are spec errors"
    (let [bad-args-app {:name "bad"
                        :commands [{:name "go"
                                    :args [{:key :x} {:key :x}]
                                    :run create!}]}
          bad-opts-app {:name "bad"
                        :commands [{:name "go"
                                    :opts [{:key :force?
                                            :long "force"}
                                           {:key :force?
                                            :long "really-force"}]
                                    :run create!}]}
          bad-args (cli/parse bad-args-app ["go" "a" "b"])
          bad-opts (cli/parse bad-opts-app ["go" "--force"])]
      (is (= :error (:status bad-args)))
      (is (some? (re-find #"Duplicate arg key" (:message bad-args))))
      (is (= :error (:status bad-opts)))
      (is (some? (re-find #"Duplicate option key" (:message bad-opts))))))

  (testing "duplicate option spelling in one scope is a spec error"
    (let [bad-app {:name "bad"
                   :commands [{:name "go"
                               :opts [{:key :first?
                                       :long "force"}
                                      {:key :second?
                                       :long "force"}]
                               :run create!}]}
          result (cli/parse bad-app ["go" "--force"])]
      (is (= :error (:status result)))
      (is (some? (re-find #"Duplicate option spelling" (:message result))))))

  (testing "duplicate command names are a spec error"
    (let [bad-app {:name "bad"
                   :commands [{:name "go"
                               :run create!}
                              {:name "go"
                               :run create!}]}
          result (cli/parse bad-app ["go"])]
      (is (= :error (:status result)))
      (is (some? (re-find #"Duplicate command" (:message result))))))

  (testing "app-level spec errors are reported before dispatch"
    (let [duplicate-commands {:name "bad"
                              :commands [{:name "go"
                                          :run create!}
                                         {:name "go"
                                          :run create!}]}
          duplicate-globals {:name "bad"
                             :opts [{:key :one?
                                     :long "flag"}
                                    {:key :two?
                                     :long "flag"}]
                             :commands [{:name "go"
                                         :run create!}]}
          duplicate-commands-result (cli/parse duplicate-commands [])
          duplicate-globals-result (cli/parse duplicate-globals ["--flag"])]
      (is (= :error (:status duplicate-commands-result)))
      (is (some? (re-find #"Duplicate command" (:message duplicate-commands-result))))
      (is (= :error (:status duplicate-globals-result)))
      (is (some? (re-find #"Duplicate option spelling" (:message duplicate-globals-result))))))

  (testing "app required fields are spec errors"
    (let [missing-name {:commands [{:name "go"
                                    :run create!}]}
          missing-commands {:name "bad"}
          missing-name-result (cli/parse missing-name [])
          missing-commands-result (cli/parse missing-commands [])]
      (is (= :error (:status missing-name-result)))
      (is (some? (re-find #"App requires :name" (:message missing-name-result))))
      (is (= :error (:status missing-commands-result)))
      (is (some? (re-find #"App requires :commands" (:message missing-commands-result))))))

  (testing "command spec errors are reported before root help"
    (let [missing-run {:name "bad"
                       :commands [{:name "go"}]}
          missing-name {:name "bad"
                        :commands [{:run create!}]}
          duplicate-args {:name "bad"
                          :commands [{:name "go"
                                      :args [{:key :x} {:key :x}]
                                      :run create!}]}
          duplicate-spellings {:name "bad"
                               :commands [{:name "go"
                                           :opts [{:key :one?
                                                   :long "flag"}
                                                  {:key :two?
                                                   :long "flag"}]
                                           :run create!}]}
          missing-run-result (cli/parse missing-run [])
          missing-name-result (cli/parse missing-name [])
          duplicate-args-result (cli/parse duplicate-args [])
          duplicate-spellings-result (cli/parse duplicate-spellings [])]
      (is (= :error (:status missing-run-result)))
      (is (some? (re-find #"Missing command runner" (:message missing-run-result))))
      (is (= :error (:status missing-name-result)))
      (is (some? (re-find #"Command requires :name" (:message missing-name-result))))
      (is (= :error (:status duplicate-args-result)))
      (is (some? (re-find #"Duplicate arg key" (:message duplicate-args-result))))
      (is (= :error (:status duplicate-spellings-result)))
      (is (some? (re-find #"Duplicate option spelling" (:message duplicate-spellings-result))))))

  (testing "global command option conflicts are reported before root help"
    (let [conflict-app {:name "bad"
                        :opts [{:key :global?
                                :long "force"}]
                        :commands [{:name "go"
                                    :opts [{:key :local?
                                            :long "force"}]
                                    :run create!}]}
          result (cli/parse conflict-app [])]
      (is (= :error (:status result)))
      (is (some? (re-find #"Option conflict" (:message result)))))))

(deftest built-ins-and-runner
  (testing "empty argv and global-options-only argv return root help"
    (let [empty-result (cli/parse app [])
          global-result (cli/parse app ["--verbose"])]
      (is (= :help (:status empty-result)))
      (is (= nil (:command empty-result)))
      (is (some? (re-find #"Usage:" (:text empty-result))))
      (is (= :help (:status global-result)))
      (is (some? (re-find #"Usage:" (:text global-result))))))

  (testing "help built-ins return tagged help results"
    (let [root-help-result (cli/parse app ["help"])
          root-long-help-result (cli/parse app ["--help"])
          root-short-help-result (cli/parse app ["-h"])
          command-help-result (cli/parse app ["help" "create"])
          command-long-help-result (cli/parse app ["create" "--help"])
          command-short-help-result (cli/parse app ["create" "-h"])
          unknown-help-result (cli/parse app ["help" "missing"])]
      (is (= :help (:status root-help-result)))
      (is (= :help (:status root-long-help-result)))
      (is (= :help (:status root-short-help-result)))
      (is (= :help (:status command-help-result)))
      (is (= "create" (:name (:command command-help-result))))
      (is (some? (re-find #"wtr create <BRANCH>" (:text command-help-result))))
      (is (= :help (:status command-long-help-result)))
      (is (= :help (:status command-short-help-result)))
      (is (= :error (:status unknown-help-result)))
      (is (some? (re-find #"Unknown command" (:message unknown-help-result))))))

  (testing "version built-ins respect claimed -v"
    (let [version-app {:name "ver"
                       :version "9.0.0"
                       :commands [{:name "show"
                                   :run create!}]}
          command-v-app {:name "ver"
                         :version "9.0.0"
                         :commands [{:name "show"
                                     :opts [{:key :verbose?
                                             :short "v"
                                             :long "verbose"}]
                                     :run create!}]}
          no-version-app {:name "ver"
                          :commands [{:name "show"
                                      :run create!}]}
          long-result (cli/parse version-app ["--version"])
          short-before-result (cli/parse version-app ["-v" "show"])
          global-claimed-result (cli/parse app ["-v" "create" "feature/login"])
          command-claimed-result (cli/parse command-v-app ["show" "-v"])
          short-after-result (cli/parse version-app ["show" "-v"])
          missing-version-result (cli/parse no-version-app ["--version"])]
      (is (= :version (:status long-result)))
      (is (= "ver 9.0.0" (:text long-result)))
      (is (= :version (:status short-before-result)))
      (is (= :ok (:status global-claimed-result)))
      (is (= {:verbose? true} (get-in global-claimed-result [:context :global])))
      (is (= :ok (:status command-claimed-result)))
      (is (= {:verbose? true} (get-in command-claimed-result [:context :opts])))
      (is (= :version (:status short-after-result)))
      (is (= :error (:status missing-version-result)))
      (is (some? (re-find #"No version available" (:message missing-version-result))))))

  (testing "run-result invokes command handler without exiting"
    (let [called (atom nil)
          run-app {:name "run"
                   :commands [{:name "go"
                               :args [{:key :value}]
                               :run (fn [ctx]
                                      (reset! called ctx)
                                      :done)}]}
          result (cli/run-result run-app ["go" "x"])]
      (is (= :ok (:status result)))
      (is (= :done (:result result)))
      (is (= {:global {}
              :args {:value "x"}
              :opts {}}
             @called))))

  (testing "run! accepts normalized CLI args"
    (let [called (atom nil)
          run-app {:name "run"
                   :commands [{:name "go"
                               :args [{:key :value}]
                               :run (fn [ctx]
                                      (reset! called ctx)
                                      :done)}]}
          result (cli/run! run-app ["go" "x"])]
      (is (= :done result))
      (is (= {:global {}
              :args {:value "x"}
              :opts {}}
             @called)))))

(deftest reserved-and-spelling-validation
  (testing "command named help is a reserved-name spec error"
    (let [bad-app {:name "bad"
                   :commands [{:name "help"
                               :run create!}]}
          result (cli/parse bad-app [])]
      (is (= :error (:status result)))
      (is (some? (re-find #"Reserved command name" (:message result))))))

  (testing "built-in option spellings are reserved spec errors"
    (let [long-help {:name "bad"
                     :commands [{:name "go"
                                 :opts [{:key :help?
                                         :long "help"}]
                                 :run create!}]}
          short-h {:name "bad"
                   :commands [{:name "go"
                               :opts [{:key :host
                                       :short "h"}]
                               :run create!}]}
          long-version {:name "bad"
                        :opts [{:key :version?
                                :long "version"}]
                        :commands [{:name "go"
                                    :run create!}]}
          long-help-result (cli/parse long-help [])
          short-h-result (cli/parse short-h [])
          long-version-result (cli/parse long-version [])]
      (is (= :error (:status long-help-result)))
      (is (some? (re-find #"Reserved option spelling: --help" (:message long-help-result))))
      (is (= :error (:status short-h-result)))
      (is (some? (re-find #"Reserved option spelling: -h" (:message short-h-result))))
      (is (= :error (:status long-version-result)))
      (is (some? (re-find #"Reserved option spelling: --version" (:message long-version-result))))))

  (testing "short option spelling must be a single character"
    (let [multi {:name "bad"
                 :commands [{:name "go"
                             :opts [{:key :force?
                                     :short "foo"}]
                             :run create!}]}
          empty {:name "bad"
                 :commands [{:name "go"
                             :opts [{:key :force?
                                     :short ""}]
                             :run create!}]}
          multi-result (cli/parse multi [])
          empty-result (cli/parse empty [])]
      (is (= :error (:status multi-result)))
      (is (some? (re-find #"single character" (:message multi-result))))
      (is (= :error (:status empty-result)))
      (is (some? (re-find #"single character" (:message empty-result))))))

  (testing "help built-in rejects extra arguments"
    (let [result (cli/parse app ["help" "create" "extra"])]
      (is (= :error (:status result)))
      (is (some? (re-find #"Too many arguments for help" (:message result)))))))

(deftest optional-docs-render-cleanly
  (testing "missing app and command docs do not produce dangling separators"
    (let [no-doc-app {:name "bare"
                      :commands [{:name "go"
                                  :run create!}]}
          root-text (cli/root-help no-doc-app)
          command-text (cli/command-help no-doc-app "go")]
      (is (some? (re-find #"^bare\n" root-text)))
      (is (nil? (re-find #"bare - " root-text)))
      (is (some? (re-find #"^bare go\n" command-text)))
      (is (nil? (re-find #"bare go - " command-text))))))

(deftest footer-rendering
  (testing "root help renders the footer after all sections"
    (let [footer-app (assoc app :footer "Run 'wtr <command> --help' for more info.")
          text (cli/root-help footer-app)]
      (is (some? (re-find #"\n\nRun 'wtr <command> --help' for more info\." text)))
      (is (some? (re-find #"for more info\.\z" text)))))

  (testing "command help never renders the footer"
    (let [footer-app (assoc app :footer "Run 'wtr <command> --help' for more info.")
          text (cli/command-help footer-app "create")]
      (is (nil? (re-find #"for more info" text)))))

  (testing "absent footer produces no trailing blank line"
    (let [text (cli/root-help app)]
      (is (nil? (re-find #"for more info" text)))
      (is (nil? (re-find #"\n\n\z" text)))))

  (testing "empty footer renders identically to no footer"
    (is (= (cli/root-help app)
           (cli/root-help (assoc app :footer ""))))))

(def run-app
  {:name "wtr"
   :version "0.1.0"
   :opts [{:key :base-dir
           :long "base-dir"
           :value? true
           :doc "Base dir."}]
   :commands [{:name "run"
               :doc "Run a command in a worktree."
               :args [{:key :name
                       :doc "Worktree name."}]
               :variadic {:key :cmd
                          :doc "Command to run; omit for a shell."}
               :run (fn [_] :ran)}]})

(deftest variadic-args
  (testing "collects trailing tokens into a vector"
    (let [result (cli/parse run-app ["run" "feat-x" "npm" "test"])]
      (is (= :ok (:status result)))
      (is (= "feat-x" (get-in result [:context :args :name])))
      (is (= ["npm" "test"] (get-in result [:context :args :cmd])))))

  (testing "slurps option-like tokens after the fixed arg (no -- needed)"
    (let [result (cli/parse run-app ["run" "feat-x" "git" "status" "-s"])]
      (is (= :ok (:status result)))
      (is (= ["git" "status" "-s"] (get-in result [:context :args :cmd])))))

  (testing "slurps a literal -- inside the command"
    (let [result (cli/parse run-app ["run" "feat-x" "git" "checkout" "--" "file"])]
      (is (= :ok (:status result)))
      (is (= ["git" "checkout" "--" "file"] (get-in result [:context :args :cmd])))))

  (testing "empty command yields an empty vector"
    (let [result (cli/parse run-app ["run" "feat-x"])]
      (is (= :ok (:status result)))
      (is (= "feat-x" (get-in result [:context :args :name])))
      (is (= [] (get-in result [:context :args :cmd])))))

  (testing "missing the required fixed arg errors"
    (let [result (cli/parse run-app ["run"])]
      (is (= :error (:status result)))
      (is (some? (re-find #"Missing argument" (:message result))))))

  (testing "--help before the fixed arg still shows command help"
    (let [result (cli/parse run-app ["run" "--help"])]
      (is (= :help (:status result)))
      (is (some? (re-find #"run" (:text result))))))

  (testing "command help renders the variadic placeholder"
    (let [text (cli/command-help run-app "run")]
      (is (some? (re-find #"\[CMD\.\.\.\]" text)))))

  (testing "a :validate on the variadic is enforced"
    (let [vapp (assoc-in run-app [:commands 0 :variadic :validate]
                         {:pred seq
                          :msg "command is required"})
          ok (cli/parse vapp ["run" "feat-x" "echo" "hi"])
          bad (cli/parse vapp ["run" "feat-x"])]
      (is (= :ok (:status ok)))
      (is (= :error (:status bad)))
      (is (some? (re-find #"command is required" (:message bad))))))

  (testing "a command cannot declare both :variadic and :opts"
    (let [bad-app (assoc-in run-app [:commands 0 :opts]
                            [{:key :force?
                              :short "f"
                              :long "force"}])
          result (cli/parse bad-app ["run" "feat-x"])]
      (is (= :error (:status result)))
      (is (some? (re-find #":variadic" (:message result)))))))

#?(:lg
   (do)
   :default
   (let [result (run-tests)]
     (when (pos? (+ (:fail result) (:error result)))
       (System/exit 1))))
