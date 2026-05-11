(ns tiny-cli.core-test
  (:require [tiny-cli.core :as cli]
            #?(:lg [test :as test :refer [deftest is testing run-tests]]
               :default [clojure.test :as test :refer [deftest is testing run-tests]])
            #?(:lg [os])))

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
      (is (some? (re-find #"Options:" text)))
      (is (some? (re-find #"-v, --verbose" text)))
      (is (some? (re-find #"Commands:" text)))
      (is (some? (re-find #"create" text)))
      (is (some? (re-find #"Built-ins:" text)))
      (is (some? (re-find #"--version" text)))))

  (testing "command help renders command sections"
    (let [text (cli/command-help app "create")]
      (is (some? (re-find #"wtr create BRANCH" text)))
      (is (some? (re-find #"Create a worktree for a branch\." text)))
      (is (some? (re-find #"Args:" text)))
      (is (some? (re-find #"BRANCH" text)))
      (is (some? (re-find #"Options:" text)))
      (is (some? (re-find #"-b, --base BASE" text)))
      (is (some? (re-find #"Default: master" text)))
      (is (some? (re-find #"Global Options:" text)))
      (is (some? (re-find #"-v, --verbose" text)))
      (is (some? (re-find #"Built-ins:" text)))
      (is (nil? (re-find #"--version" text))))))

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
      (is (= {:force? true :dry-run? true}
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

#?(:lg
   (do
     (run-tests)
     (when-not test/*test-result*
       (os/exit 1)))
   :default
   (let [result (run-tests)]
     (when (pos? (+ (:fail result) (:error result)))
       (System/exit 1))))
