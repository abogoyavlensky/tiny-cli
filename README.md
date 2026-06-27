# tiny-cli

A zero-dependency CLI argument parser for [let-go](https://github.com/nooga/let-go), [Clojure](https://clojure.org/index) and [Babashka](https://github.com/babashka/babashka). 
It is small on purpose: flat commands, Unix-style options, generated help, 
version output, and simple validation.

## Features

- **Plain-data spec.** Declare the whole CLI as one map.
- **Unix-style options.** Short (`-v`) and long (`--verbose`) flags, `--opt value` 
  or `--opt=value`, grouped short flags (`-vf`), global and per-command scopes.
- **Positional and variadic args.** Fixed required positionals, plus an optional
  variadic that collects the rest.
- **Validation without coercion.** Per-arg and per-option `:validate` predicates.
- **Generated help.** `--help`, `-h`, `help <command>`, `-v`, `--version`,
  work with no extra code. Mark a command `:hidden?` to keep it out of help while it
  still runs.
- **Built-in shell completion.** A `completion` command prints a bash, zsh, or
  fish script; commands, options, and `help` complete with zero config. Add
  `:complete` to any arg or option to complete dynamic values.

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

(cli/run! app *command-line-args*)
```

`app` is the CLI spec. `argv` is a vector of command-line tokens without the
executable name or script path:

```clojure
(cli/run! app *command-line-args*))
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

(when-not *compiling-aot* 
  (cli/run! app *command-line-args*))
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

## App Spec Reference

The first argument to `run!` is the app spec, plain data. The smallest valid
spec names the tool and one command:

```clojure
{:name "tool"
 :commands [{:name "greet"
             :run (fn [_ctx] (println "hi"))}]}
```

`:name` and `:commands` are required; every command needs `:name` and `:run`.
Everything else is optional. The annotated reference below shows every key with
its possible values; the sections that follow spell out the rules in detail.

```clojure
{;; Executable name, shown in help, version, and usage lines. Required.
 :name "tool-name"

 ;; Version string for `--version` and an unclaimed `-v`. When it is absent and
 ;; a version is requested, parsing returns "No version available."
 :version "0.1.0"

 ;; Root description shown at the top of root help.
 :doc "Short app description."

 ;; Trailing text shown after the command list in root help.
 :footer "Run 'tool-name <command> --help' for more information on a command."

 ;; Set false to drop the built-in `completion` command and `__complete`
 ;; endpoint. Defaults to on. See Shell Completions.
 :completion? true

 ;; Global option specs. They may appear before the command, and after it
 ;; unless a command option claims the same spelling. Each value lands in the
 ;; handler's :global map.
 :opts
 [{;; Keyword used in the handler's :global map. Required.
   :key :verbose?
   ;; Short spelling without the leading "-". Give :short, :long, or both.
   :short "v"
   ;; Long spelling without the leading "--".
   :long "verbose"
   ;; true consumes the next token as the option's value; omit it for a flag.
   :value? false
   ;; Value placed in :global when the option is absent.
   :default nil
   ;; true rejects parsing when the option is missing.
   :required? false
   ;; {:pred fn :msg "message"}. The predicate receives the raw string value.
   :validate {:pred some? :msg "..."}
   ;; Value-completion candidates: a vector of strings or a (fn [ctx]) returning
   ;; strings. See Shell Completions.
   :complete ["a" "b"]
   ;; Description shown in help.
   :doc "Print extra output."}]

 ;; Flat list of command specs. Required.
 :commands
 [{;; Command token the user types. Required.
   :name "create"
   ;; Command description shown in help.
   :doc "Create an item."
   ;; true hides the command from root help; it still runs, and
   ;; `help <command>` still shows its help.
   :hidden? false
   ;; Fixed positional args, in order. Every declared arg is required.
   :args
   [{;; Keyword used in the handler's :args map. Required.
     :key :name
     ;; Description shown in command help.
     :doc "Item name."
     ;; {:pred fn :msg "message"} validation spec.
     :validate {:pred non-blank? :msg "NAME is required."}
     ;; Value-completion candidates: a vector of strings or a (fn [ctx]).
     :complete (fn [ctx] (candidates ctx))}]
   ;; One arg spec that slurps every token after the fixed :args into a vector.
   ;; At most one per command. See Variadic Trailing Args.
   :variadic {:key :cmd
              :doc "Command to run; omit for a shell."}
   ;; Command-specific option specs, same shape as global :opts. Each value
   ;; lands in the handler's :opts map.
   :opts
   [{:key :force?
     :short "f"
     :long "force"
     :doc "Replace an existing item."}]
   ;; Handler called with the parsed context map. Required. See Handler Context.
   :run create!}]}
```

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

## Shell Completions

Every `tiny-cli` app gets shell completion built in — no extra code. A hidden
`completion` command prints a completion script for `bash`, `zsh`, or `fish`,
and a hidden `__complete` endpoint answers the script's requests on TAB. Out of
the box you get completion for commands, long options, `help`, and the
`completion` command's own shell argument.

Install it by sourcing the script for your shell.

Bash — add to `~/.bashrc`:

```sh
source <(mytool completion bash)
```

Zsh — source it in `~/.zshrc` (after `compinit`), or save it on your `fpath`:

```sh
mkdir -p ~/.zfunc
mytool completion zsh > ~/.zfunc/_mytool
```

and make sure `~/.zshrc` has `fpath+=~/.zfunc` before `compinit` runs. For an
app name with non-identifier characters, prefer the `source` form: zsh autoload
needs the file name to match the script's sanitized function id.

Fish:

```sh
mytool completion fish > ~/.config/fish/completions/mytool.fish
```

### App-specific completions

Add `:complete` to any arg or option spec to complete dynamic values. It is a
vector of strings, or a one-argument function returning a seq of strings.
`tiny-cli` prefix-filters the result by the word being typed, so completers
return unfiltered candidates. The function receives a context map with `:words`
(the words before the cursor), `:cur` (the word being completed), `:command`
(the selected command spec), and `:positionals` (positional values already
given).

```clojure
{:name "remove"
 :doc "Remove a worktree."
 :args [{:key :name
         :doc "Worktree name."
         :complete (fn [_ctx] (existing-worktree-names))}]
 :run remove!}
```

A positional whose value is new each time — a name being created — simply omits
`:complete`, and the shell falls back to its own filename completion. To turn
the whole feature off, set `:completion? false` on the app.

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
