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

#?(:lg
   (do
     (run-tests)
     (when-not test/*test-result*
       (os/exit 1)))
   :default
   (let [result (run-tests)]
     (when (pos? (+ (:fail result) (:error result)))
       (System/exit 1))))
