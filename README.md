# tiny-cli

A zero-dependency CLI library for [let-go](https://github.com/nooga/let-go), 
[Clojure](https://clojure.org/index), and [Babashka](https://github.com/babashka/babashka). 
It is small on purpose: flat commands, Unix-style options, generated help, 
version output, and simple validation.

## Getting Started

Require the core namespace:

```clojure
(ns my.tool
  (:require [tiny-cli.core :as cli]))
```

Use `run!` at the application edge:

```clojure
(cli/run! app argv)
```

`app` is the CLI spec. `argv` is a vector of command-line tokens without the
executable name or script path. Normalize process args before calling
`tiny-cli`:

```clojure
; let-go interpreted script
(cli/run! app (vec (drop 4 os/args)))

; let-go bundled binary
(cli/run! app (vec (rest os/args)))

; Clojure / Babashka
(cli/run! app (vec *command-line-args*))
```

`run!` parses args, prints help, version, and parse errors, invokes command
handlers, and exits for built-ins and parse errors. It does not catch handler
exceptions.

## Minimal let-go Tool

This is a small deploy helper as an interpreted let-go script. It accepts one
command, one required positional arg, one command option, and one global flag.

*deploy.lg*

```clojure
(ns deploy
  (:require [os]
            [tiny-cli.core :as cli]))

(defn non-blank?
  [s]
  (and s (not= "" s)))

(defn deploy-service!
  [{:keys [global args opts]}]
  (let [service (:service args)
        env (:env opts)]
    (if (:dry-run? global)
      (println "would deploy" service "to" env)
      (println "deploying" service "to" env))))

(def app
  {:name "deploy"
   :version "0.1.0"
   :doc "Deploy one service."
   :footer "Run 'deploy <command> --help' for more information on a command."
   :opts [{:key :dry-run?
           :short "n"
           :long "dry-run"
           :doc "Print the deployment plan."}]
   :commands [{:name "service"
               :doc "Deploy a service."
               :args [{:key :service
                       :doc "Service name."
                       :validate {:pred non-blank?
                                  :msg "SERVICE is required."}}]
               :opts [{:key :env
                       :short "e"
                       :long "env"
                       :value? true
                       :default "staging"
                       :doc "Target environment."}]
               :run deploy-service!}]})

(defn- cli-argv [argv]
  "Return args after the `--` separator while developing, or CLI args in bundled mode."
  (if (some #(= % "--") argv)
    (rest (drop-while #(not (= % "--")) argv))
    (rest argv)))

(when-not *compiling-aot* 
  (cli/run! app (cli-argv os/args)))
```

Example:

```bash
lgx run deploy.lg --dry-run service api --env prod
```

Output:

```text
would deploy api to prod
```

## Expected App Spec

The first argument to `run!` is the app spec. It is plain data:

```clojure
{:name "tool-name"
 :version "0.1.0"
 :doc "Short app description."
 :opts [{:key :verbose?
         :short "v"
         :long "verbose"
         :doc "Print extra output."}]
 :commands [{:name "create"
             :doc "Create an item."
             :args [{:key :name
                     :doc "Item name."}]
             :opts [{:key :force?
                     :short "f"
                     :long "force"
                     :doc "Replace an existing item."}]
             :run create!}]}
```

App fields:

| Key | Required? | Description |
|---|---:|---|
| `:name` | yes | Executable name used in help and version output. |
| `:version` | no | Version used by `--version` and unclaimed `-v`. |
| `:doc` | no | Root description shown in help. |
| `:footer` | no | Trailing text shown after commands in root help. |
| `:opts` | no | Global option specs. |
| `:commands` | yes | Flat list of command specs. |

Command fields:

| Key | Required? | Description |
|---|---:|---|
| `:name` | yes | Command token typed by the user. |
| `:doc` | no | Command description shown in help. |
| `:args` | no | Fixed positional args. All declared args are required. |
| `:opts` | no | Command-specific option specs. |
| `:run` | yes | Handler function called with the parsed context. |

Arg fields:

| Key | Required? | Description |
|---|---:|---|
| `:key` | yes | Keyword used in the handler `:args` map. |
| `:doc` | no | Description shown in command help. |
| `:validate` | no | `{:pred fn :msg "message"}` validation spec. |

Option fields:

| Key | Required? | Description |
|---|---:|---|
| `:key` | yes | Keyword used in `:global` or `:opts`. |
| `:short` | no | Short option without `-`, such as `"v"`. |
| `:long` | no | Long option without `--`, such as `"verbose"`. |
| `:value?` | no | `true` means the option requires a value. |
| `:default` | no | Value inserted when the option is absent. |
| `:required?` | no | Requires the option before the handler runs. |
| `:validate` | no | `{:pred fn :msg "message"}` validation spec. |
| `:doc` | no | Description shown in help. |

Each option needs `:short`, `:long`, or both. Duplicate command names, duplicate
arg keys, duplicate option keys, duplicate option spellings, and global/command
option spelling conflicts are spec errors.

## Handler Context

Handlers receive one map:

```clojure
{:global {...}
 :args   {...}
 :opts   {...}}
```

For this command:

```bash
deploy --dry-run service api --env prod
```

The handler receives:

```clojure
{:global {:dry-run? true}
 :args   {:service "api"}
 :opts   {:env "prod"}}
```

CLI values stay as raw strings. `tiny-cli` applies defaults, checks required
options, and runs validation predicates, but it does not coerce types.

## Built-In Commands and Options

`tiny-cli` adds help and version behavior around your app spec.

Root help:

```bash
tool help
tool --help
tool -h
```

Command help:

```bash
tool help command
tool command --help
tool command -h
```

Version:

```bash
tool --version
tool -v
```

`--version` always requests version output. `-v` requests version output only
when it is not claimed by a global option before the command, or by a global or
command option after the command. If version is requested and `:version` is
missing, parsing returns `No version available.`

For the deploy example, root help looks like this:

```text
deploy - Deploy one service.

Usage:
  deploy [options] <command> [args]
  deploy help [command]
  deploy --help

Options:
  -n, --dry-run  Print the deployment plan.

Commands:
  service  Deploy a service.
  help [command]  Show help.
  -h, --help  Show help.
  --version  Print version.

Run 'deploy <command> --help' for more information on a command.
```

Command help looks like this:

```text
deploy service SERVICE - Deploy a service.

Usage:
  deploy service SERVICE
  deploy help service

Args:
  SERVICE  Service name.

Options:
  -e, --env ENV  Target environment. Default: staging

Global Options:
  -n, --dry-run  Print the deployment plan.
  help service  Show help for service.
  -h, --help  Show help for service.
```

## Library Functions

The main function is:

```clojure
(cli/run! app argv)
```

Pure and test-friendly helpers are also public:

```clojure
(cli/parse app argv)
(cli/run-result app argv)
(cli/root-help app)
(cli/command-help app "service")
```

`parse` returns tagged maps with `:status` set to `:ok`, `:help`, `:version`,
or `:error`. `run-result` calls the selected handler for `:ok` results without
exiting, which makes command dispatch easy to test.

## Tests

Run the shared `.cljc` test suite:

```bash
lgx test-all
```
