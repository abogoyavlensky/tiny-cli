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

  (testing "unknown command completes nothing"
    (is (= [] (completion/candidates app ["bogus"] "")))))

(deftest candidates-flags
  (testing "root flags when the word starts with a dash"
    (is (= ["--base-dir" "--help" "--version"]
           (completion/candidates app [] "-"))))

  (testing "command flags include globals, the command's own, and help"
    (is (= ["--base-dir" "--force" "--help"]
           (completion/candidates app ["remove"] "-"))))

  (testing "no flags after a positional (tiny-cli rejects options there)"
    (is (= [] (completion/candidates app ["remove" "feat-x"] "-")))))

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

(deftest candidates-option-value
  (testing "a value-taking long option completes its :complete"
    (is (= ["main" "dev"] (completion/candidates app ["create" "--from"] "")))
    (is (= ["main"] (completion/candidates app ["create" "--from"] "m"))))

  (testing "a value-taking short option resolves the same slot"
    (is (= ["main"] (completion/candidates app ["create" "-f"] "m")))))

#?(:lg
   (do)
   :default
   (let [result (run-tests)]
     (when (pos? (+ (:fail result) (:error result)))
       (System/exit 1))))
