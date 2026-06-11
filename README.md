# tiny-cli

A zero-dependency CLI argument parser for [let-go](https://github.com/nooga/let-go), compatible with 
[Clojure](https://clojure.org/index) and [Babashka](https://github.com/babashka/babashka). 
It is small on purpose: flat commands, Unix-style options, generated help, 
version output, and simple validation.

## Getting Started

> [!NOTE]
> [lgx](https://github.com/abogoyavlensky/lgx) is a dependency and project management tool for let-go.

> [!IMPORTANT]
> Requrements: `let-go >= 0.10.0`


Add `tiny-cli` to your dependencies at `lgx.edn` file:

```clojure
{:deps {abogoyavlensky/tiny-cli {:git/url "https://github.com/abogoyavlensky/tiny-cli"
                                 :git/sha "0.1.0"}}}
```

Require the core namespace and use `run!` at the application edge:

```clojure
(ns my.tool
  (:require [tiny-cli.core :as cli]))

(defn do-something!
  [{:keys [global args opts]}]
  (println "Doing something with" args "and" opts "and global options" global))

(def app
  {:name "mycli"
   :version "0.1.0"
   :doc "My awesome CLI tool."
   :commands [{:name "do-something"
               :doc "Do something useful."
               :run do-something!}]})

(cli/run! app argv)
```

`app` is the CLI spec. `argv` is a vector of command-line tokens without the
executable name or script path. Normalize process args before calling
`tiny-cli`:

```clojure
; let-go
(cli/run! app (vec (rest os/args)))

; Clojure / Babashka
(cli/run! app (vec *command-line-args*))
```

`run!` parses args, prints help, version, and parse errors, invokes command
handlers, and exits for built-ins and parse errors. It does not catch handler
exceptions.

## Minimal let-go cli app

This is a small deploy helper as an interpreted let-go script. It accepts one
command, one required positional arg, one command option, and one global flag.

*main.lg*

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
lgx run -- --dry-run service --env prod api
```

Output:

```text
would deploy api to prod
```

Or build it and run binary:

```bash
lgx build
deploy --dry-run service --env prod api
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
| `:variadic` | no | A single arg spec collecting all trailing tokens into a vector. See [Variadic Trailing Args](#variadic-trailing-args). |
| `:opts` | no | Command-specific option specs. |
| `:run` | yes | Handler function called with the parsed context. |
| `:hidden?` | no | `true` omits the command from root help. It still runs, and `help <command>` still shows its help. |

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
deploy --dry-run service --env prod api
```

The handler receives:

```clojure
{:global {:dry-run? true}
 :args   {:service "api"}
 :opts   {:env "prod"}}
```

CLI values stay as raw strings. `tiny-cli` applies defaults, checks required
options, and runs validation predicates, but it does not coerce types.

## Option Ordering

Options come before positional arguments. The first positional token ends
option parsing for the command, so every token after it is a positional value:

```bash
deploy --dry-run service --env prod api   ; ok
deploy service api --env prod             ; error: Options must appear before arguments: --env
```

Global options, command options, and the built-ins (`--help`, `-h`,
`--version`, `-v`) all follow this rule. A global option may also sit before
the command (`deploy --dry-run service ...`). Use `--` to end option parsing
explicitly, which lets a positional value start with a dash:

```bash
deploy service -- --weird-name
```

## Variadic Trailing Args

A command may declare a single `:variadic` arg to collect everything after its
fixed `:args` into a vector. This is what `run`/`exec`-style commands need.

```clojure
{:name "run"
 :doc "Run a command in a worktree."
 :args [{:key :name :doc "Worktree name."}]
 :variadic {:key :cmd :doc "Command to run; omit for a shell."}
 :run run!}
```

Once the fixed args are filled, parsing switches to *rest mode*: every remaining
token is appended verbatim — including option-like tokens and a literal `--` —
so you don't need a `--` separator to pass flags through:

```bash
tool run feat-x npm test            ; {:name "feat-x" :cmd ["npm" "test"]}
tool run feat-x git status -s       ; :cmd ["git" "status" "-s"]
tool run feat-x git checkout -- f   ; :cmd ["git" "checkout" "--" "f"]
tool run feat-x                     ; :cmd []
```

The variadic key lands in the handler's `:args` map alongside the fixed args.
Constraints: the variadic must be the only one per command. A command may
declare its own `:opts`, but every option — global or command — must come
before the first positional; once the fixed args start, every remaining token
is slurped into the variadic vector. The fixed args remain required; omitting
them is still a `Missing argument` error.

## Running Under lgx (`--` and `LGX_RUN`)

A tool built with [lgx](https://github.com/abogoyavlensky/lgx) runs two ways:
as a bundled binary (`tool run …`) and in development via `lgx run -- run …`.
`lgx run` injects a `--` marker before your app args, so the conventional way to
recover them is:

```clojure
(rest (drop-while #(not= "--" %) (os/args)))   ; dev: drop up to lgx's marker
```

But that idiom is wrong for a *bundled* binary, where there is no marker and a
`--` may legitimately appear inside the user's command (e.g. `git checkout --`).
Detect the mode out-of-band instead of sniffing for `--`. `lgx run` sets
`LGX_RUN=1` in the spawned process, so:

```clojure
(defn- strip-runner-args
  "Application args from a raw argv, in both run modes."
  [argv lgx-run?]
  (if lgx-run?
    (rest (drop-while #(not= "--" %) argv))   ; dev: drop up to & incl marker
    (rest argv)))                              ; bundled: drop argv[0]

(strip-runner-args (os/args) (not (str/blank? (os/getenv "LGX_RUN"))))
```

This keeps a literal `--` inside a variadic command intact when running as a
binary. See lgx's README for the `LGX_RUN` contract.

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
  deploy [global options] <command> [options] [args]

Commands:
  deploy service <SERVICE>  Deploy a service.
  deploy help [command]     Show a command help.
  deploy --help             Show the tool help.
  deploy --version          Print version.

Global Options:
  -n, --dry-run  Print the deployment plan.

Run 'deploy <command> --help' for more information on a command.
```

Command help looks like this:

```text
deploy service <SERVICE> - Deploy a service.

Usage:
  deploy [global options] service [options] <SERVICE>
  deploy help service
  deploy service -h, --help  Show help for service.

Args:
  SERVICE  Service name.

Options:
  -e, --env ENV  Target environment. Default: staging

Global Options:
  -n, --dry-run  Print the deployment plan.
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

## License
MIT License
Copyright (c) 2026 Andrey Bogoyavlenskiy
