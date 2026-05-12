# tiny-cli Design

## Purpose

`tiny-cli` is a small CLI helper library for `let-go` (also compatible with Clojure and Babashka).

The goal is to let `let-go` users build simple command-line apps with minimal ceremony, good default UX, and a tiny implementation.

The library is intentionally not a full CLI framework. It focuses on flat command-based tools with regular Unix-style options, positional command arguments, generated help, and simple validation.

## Goals

- Minimal, understandable API
- Small implementation
- Flat commands only
- Global options
- Command-specific options
- Fixed positional command arguments
- Generated root help and command help
- Built-in `help`, `--help`, `-h`, and `--version`, `-v` options
- Simple validation
- Predictable handler input shape
- No parsing/coercion magic

## Non-goals for v1

- No subcommands
- No option aliases beyond one short and one long option
- No optional positional arguments
- No variadic/rest positional arguments
- No automatic type coercion
- No environment variable support
- No config files
- No shell completions
- No middleware
- No nested command dispatch

## Basic Example

```clojure
(ns wtr.main
  (:require [tiny-cli.core :as cli]))

(defn valid-branch? [s]
  (and s (not= "" s)))

(defn create! [{:keys [global args opts]}]
  (let [{:keys [verbose?]} global
        {:keys [branch]} args
        {:keys [base]} opts]
    (when verbose?
      (println "Creating worktree" branch "from" base))))

(defn list! [{:keys [global opts args]}]
  (println "Listing worktrees"))

(def app
  {:name "wtr"
   :version "0.1.0"
   :doc "Small git worktree helper."

   :opts
   [{:key :verbose?
     :short "v"
     :long "verbose"
     :doc "Print executed commands."}]

   :commands
   [{:name "create"
     :doc "Create a worktree for a branch."
     :args [{:key :branch
             :doc "Branch name."
             :validate {:pred valid-branch?
                        :msg "BRANCH should be a valid branch name."}}]
     :opts [{:key :base
             :short "b"
             :long "base"
             :value? true
             :default "master"
             :doc "Base branch."}]
     :run create!}

    {:name "list"
     :doc "List worktrees."
     :run list!}]})

(defn main [argv]
  (cli/run! app argv))
```

## User CLI Examples

```bash
wtr help
wtr --help
wtr --version

wtr list
wtr -v list
wtr list -v

wtr create feature/login
wtr create feature/login --base main
wtr create --base main feature/login
wtr -v create feature/login --base main
```

## App Spec

```clojure
{:name "tool-name"
 :version "0.1.0"
 :doc "Short app description."
 :opts [...]
 :commands [...]}
```

### Fields

| Key | Required? | Description |
|---|---:|---|
| `:name` | yes | CLI executable name used in help output |
| `:version` | no | Version printed by `--version` |
| `:doc` | no | Root description shown in help |
| `:opts` | no | Global option specs |
| `:commands` | yes | Flat list of command specs |

## Command Spec

```clojure
{:name "create"
 :doc "Create a worktree."
 :args [{:key :branch
         :doc "Branch name."}]
 :opts [{:key :base
         :long "base"
         :value? true}]
 :run create!}
```

### Fields

| Key | Required? | Description |
|---|---:|---|
| `:name` | yes | Command token typed by the user |
| `:doc` | no | Command description shown in help |
| `:args` | no | Fixed positional argument specs |
| `:opts` | no | Command-specific option specs |
| `:run` | yes | Function called when command is selected |

### Command Names

There is exactly one way to name a command:

```clojure
:name "create"
```

This corresponds directly to:

```bash
tool create
```

No `:id`, no aliases, no derived command names.

## Positional Args

`tiny-cli` v1 supports fixed positional command arguments.

```clojure
:args [{:key :src
        :doc "Source path."}
       {:key :dst
        :doc "Destination path."}]
```

Usage:

```bash
tool copy SRC DST
```

Handler receives:

```clojure
{:args {:src "a.txt"
        :dst "b.txt"}}
```

### Arg Spec

```clojure
{:key :branch
 :doc "Branch name."
 :validate {:pred valid-branch?
            :msg "BRANCH should be a valid branch name."}}
```

### Arg Fields

| Key | Required? | Description |
|---|---:|---|
| `:key` | yes | Keyword used in handler `:args` map |
| `:doc` | no | Description shown in help |
| `:validate` | no | Validation spec |

### Arg Rules

- All declared args are required.
- Optional positional args are not supported in v1.
- Variadic positional args are not supported in v1.
- Too few args is an error.
- Too many args is an error.
- Args are passed as raw strings.

## Option Spec

The same option spec is used for global options and command options.

Boolean option:

```clojure
{:key :verbose?
 :short "v"
 :long "verbose"
 :doc "Print executed commands."}
```

Value option:

```clojure
{:key :base
 :short "b"
 :long "base"
 :value? true
 :default "master"
 :doc "Base branch."}
```

### Option Fields

| Key | Required? | Description |
|---|---:|---|
| `:key` | yes | Keyword used in handler map |
| `:short` | no | Short option without dash, e.g. `"v"` |
| `:long` | no | Long option without dashes, e.g. `"verbose"` |
| `:value?` | no | `true` means option requires a value |
| `:default` | no | Default value when option is absent |
| `:required?` | no | Option must be provided |
| `:validate` | no | Validation spec |
| `:doc` | no | Description shown in help |

At least one of `:short` or `:long` must be provided.

### Boolean Options

If `:value?` is missing or false, the option is boolean.

```clojure
{:key :force?
 :short "f"
 :long "force"}
```

Accepted usage:

```bash
tool remove item --force
tool remove item -f
```

Handler receives:

```clojure
{:opts {:force? true}}
```

### Value Options

If `:value? true`, the option requires a value.

```clojure
{:key :base
 :short "b"
 :long "base"
 :value? true}
```

Accepted usage:

```bash
tool create branch --base main
tool create branch --base=main
tool create branch -b main
```

Handler receives:

```clojure
{:opts {:base "main"}}
```

### Option Placeholders

Help placeholders are derived from the option key.

```clojure
:base        ;; BASE
:branch-name ;; BRANCH-NAME
:input-file  ;; INPUT-FILE
```

Example help:

```text
-b, --base BASE   Base branch. Default: master
```

### Required Options

Required options are supported with `:required? true`.

```clojure
{:key :token
 :long "token"
 :value? true
 :required? true
 :doc "API token."}
```

If missing, parsing fails before the command handler is called.

## Validation

Validation is optional and works on raw string values.

```clojure
:validate {:pred some-fn
           :msg "Error message"}
```

The predicate receives the raw value.

For value options and positional args, this is a string.

For boolean options, validation is probably unnecessary, but if present it receives `true`.

Example:

```clojure
(defn positive-int-string? [s]
  (and s (re-matches #"[0-9]+" s) (< 0 (parse-int s))))

{:key :limit
 :long "limit"
 :value? true
 :validate {:pred positive-int-string?
            :msg "LIMIT should be a positive integer."}}
```

### Validation Rules

- Exactly one validation spec per arg/option in v1.
- Multiple validations are not supported directly.
- Users can compose validations in their own predicate.
- `:validate` does not coerce values.
- Invalid values stop execution and print a user-facing error.

## No Automatic Coercion

`tiny-cli` does not parse strings into ints, keywords, files, enums, or other domain values.

All CLI-provided values are passed as raw strings.

Handlers own domain parsing:

```clojure
(defn serve! [{:keys [opts]}]
  (let [port (parse-int (:port opts))]
    ...))
```

This keeps the library small and avoids a second type system in the CLI spec.

## Handler Context

Command handlers receive one map with three main sections:

```clojure
{:global {...}
 :args   {...}
 :opts   {...}}
```

Example:

```bash
wtr -v create feature/login --base main
```

Handler receives:

```clojure
{:global {:verbose? true}
 :args   {:branch "feature/login"}
 :opts   {:base "main"}}
```

### Context Fields

| Key | Description |
|---|---|
| `:global` | Parsed global options |
| `:args` | Parsed positional command args |
| `:opts` | Parsed command-specific options |

Optional future internal metadata can be added under namespaced keys if needed, for example:

```clojure
{:tiny-cli/command "create"
 :tiny-cli/argv [...]}
```

But the documented v1 handler API should stay focused on `:global`, `:args`, and `:opts`.

## Parsing Behaviour

### Command Selection

The first non-option token that matches a command name selects the command.

```bash
tool -v create item
```

Command is `create`.

Global options can appear before or after the command:

```bash
tool -v create item
tool create item -v
```

Command options are parsed only after the command is selected.

If a flag name exists both globally and on the selected command, command-local parsing should take priority after the command token.

Recommended rule for simplicity:

- Before command token: only global options are parsed.
- After command token: command options and global options are both accepted.
- If the same option spelling exists in both places, it is a spec error.

### Supported Option Forms

Long boolean:

```bash
--verbose
```

Short boolean:

```bash
-v
```

Combined short booleans:

```bash
-vf
```

Long value with space:

```bash
--base main
```

Long value with equals:

```bash
--base=main
```

Short value with space:

```bash
-b main
```

### End of Options

`--` stops option parsing.

```bash
tool create -- --branch-looking-value
```

Everything after `--` is treated as positional args.

## Built-in Help

`tiny-cli` provides built-in help.

Root help:

```bash
tool help
tool --help
tool -h
```

Command help:

```bash
tool help create
tool create --help
tool create -h
```

### Root Help Example

```text
wtr 0.1.0
Small git worktree helper.

Usage:
  wtr [options] <command> [command-options] [args]
  wtr help [command]

Options:
  -v, --verbose   Print executed commands.
      --version   Print version.
  -h, --help      Show help.

Commands:
  create BRANCH   Create a worktree for a branch.
  list            List worktrees.
```

### Command Help Example

```text
Usage:
  wtr create [options] BRANCH

Create a worktree for a branch.

Arguments:
  BRANCH   Branch name.

Options:
  -b, --base BASE   Base branch. Default: master
  -v, --verbose     Print executed commands.
  -h, --help        Show help.
```

## Built-in Version

If `:version` exists, `--version` prints it:

```bash
tool --version
```

Output:

```text
0.1.0
```

If `:version` is missing, `--version` can either print nothing or return an error. Recommended v1 behaviour:

```text
error: version is not configured
```

## Error Behaviour

Errors should be short, clear, and user-facing.

Unknown command:

```text
error: unknown command: creat

Run `wtr help` to see available commands.
```

Missing arg:

```text
error: missing argument: BRANCH

Usage:
  wtr create [options] BRANCH
```

Too many args:

```text
error: too many arguments: extra

Usage:
  wtr create [options] BRANCH
```

Unknown option:

```text
error: unknown option: --bas

Usage:
  wtr create [options] BRANCH
```

Missing option value:

```text
error: missing value for option: --base
```

Validation error:

```text
error: BRANCH should be a valid branch name.
```

## Exit Codes

Recommended default exit codes:

| Situation | Exit code |
|---|---:|
| Successful command | command decides |
| Help printed | 0 |
| Version printed | 0 |
| Parse error | 2 |
| Validation error | 2 |
| Unknown command | 2 |

If handler returns an integer, `tiny-cli` may use it as the process exit code.

If handler returns nil, exit code is 0.

## Public API

Public v1 API:

```clojure
(cli/run! app argv)
(cli/parse app argv)
(cli/root-help app)
(cli/command-help app "create")
(cli/run-result app argv)
```

`run!` accepts normalized CLI args, prints help/version/errors, invokes handlers, and exits for built-ins and parse errors.

`parse`, `root-help`, and `command-help` are pure helpers for tests, debug, and advanced integrations. `run-result` parses argv and invokes the selected handler for `:ok` results without exiting, which makes handler dispatch easy to test.

`run!`, `parse`, and `run-result` receive argv without the executable name or entry script path. Callers own process argv normalization.

OK result:

```clojure
{:status :ok
 :command command-spec
 :context {:global {:verbose? true}
           :args {:branch "feature/login"}
           :opts {:base "main"}}}
```

Help result:

```clojure
{:status :help
 :command nil-or-command-spec
 :text "..."}
```

Version result:

```clojure
{:status :version
 :text "wtr 0.1.0"}
```

Error result:

```clojure
{:status :error
 :message "Unknown option: --bas"}
```

## Internal Implementation Outline

Recommended internal steps:

1. Normalize app spec
2. Validate app spec
3. Add built-in help/version specs
4. Find command
5. Parse global options
6. Parse command options
7. Collect positional args
8. Apply defaults
9. Check required options
10. Validate args and options
11. Build handler context
12. Run handler

### Normalization

Normalize options into maps indexed by short and long spellings.

Example source spec:

```clojure
{:key :base
 :short "b"
 :long "base"
 :value? true}
```

Normalized form:

```clojure
{:key :base
 :short "b"
 :short-token "-b"
 :long "base"
 :long-token "--base"
 :value? true
 :placeholder "BASE"}
```

### Spec Validation

Fail early for invalid app specs:

- App has no `:name`
- Command has no `:name`
- Command has no `:run`
- Duplicate command names
- Option has no `:key`
- Option has neither `:short` nor `:long`
- Duplicate option token in the same scope
- Same option token appears in both global and command scope
- Arg has no `:key`
- Duplicate arg keys in one command
- Duplicate option keys in one option scope

## Design Decisions

### Why `:name` for commands?

Commands are user-facing CLI tokens.

```clojure
:name "create"
```

means exactly:

```bash
tool create
```

There is no separate internal command id in v1.

### Why `:key` for options and args?

Options and args become keys in handler data.

```clojure
{:key :branch}
```

becomes:

```clojure
{:args {:branch "feature/login"}}
```

### Why `:run` instead of `:handler`?

A command is something the CLI runs. `:run` is short, direct, and less framework-like than `:handler`.

### Why `:args` instead of single `:arg`?

Fixed multiple positional args are common enough to support from day one:

```bash
cp SRC DST
mv SRC DST
compare LEFT RIGHT
```

Using `:args` now avoids a future migration from `:arg` to `:args`.

### Why no optional args?

Optional positional args add parsing and help complexity. For v1, all declared args are required.

Optional behaviour can usually be represented with command options instead.

### Why no automatic coercion?

Coercion introduces more API surface and unclear responsibility. `tiny-cli` validates raw CLI input and passes strings to handlers. Handlers parse domain values themselves.

### Why separated handler sections?

A flat handler map is convenient but can hide where values came from.

This is clearer:

```clojure
{:global {:verbose? true}
 :args {:branch "feature/login"}
 :opts {:base "main"}}
```

It also avoids accidental key collisions between global options, command options, and positional args.

## Possible Future Additions

Only add these when real usage proves the need:

- Variadic positional args
- Enum/restrict validation helper
- Built-in parse helpers outside the core spec

These should not be part of v1.
