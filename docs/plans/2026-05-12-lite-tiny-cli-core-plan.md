# tiny-cli Core Implementation Plan

> **For agentic workers:** Use executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the v1 `tiny-cli` core library as a small `.cljc` CLI helper compatible first with let-go and Clojure, with Babashka compatibility kept unless a concrete blocker appears.

**Tech Stack:** let-go, lgx, Clojure-compatible `.cljc`, embedded let-go `test` namespace, shell test runner.

---

## Design

### API And Architecture

`src/tiny_cli/core.cljc` remains the single implementation namespace for v1. The library is small enough that splitting files now would add resolver and packaging surface without enough benefit. Keep the file internally organized into sections: spec lookup and normalization, token parsing, validation, help rendering, public API, and runner.

Public API:

- `parse` is pure and accepts `app` plus an argv vector that excludes the executable name and entry script path.
- `root-help` is pure and returns deterministic help text for the app.
- `command-help` is pure and accepts `app` plus a command name string, returning deterministic help text for that command.
- `run!` is the convenience runner that reads process args, prints, invokes handlers, and exits for help, version, and user-facing errors.

`parse` returns tagged maps:

- `{:status :ok :command command-spec :context {:global {...} :args {...} :opts {...}}}`
- `{:status :help :command nil-or-command-spec :text "..."}`
- `{:status :version :text "..."}`
- `{:status :error :message "..." :text "...optional help..."}`

The handler API stays as documented in `docs/initial_design.md`: handlers receive `{:global ... :args ... :opts ...}` and CLI-provided values remain raw strings. Defaults are inserted during parsing. Boolean options are included only when supplied or when they have an explicit default. No type coercion is performed.

Use portable `.cljc` for the pure API. Host-specific process behavior belongs only in `run!` helpers. Use reader conditionals with a `:lg` branch for let-go and a `:default` branch for Clojure/Babashka where needed, so Clojure and Babashka can load the namespace without seeing let-go-only `os/*` symbols. If string helpers are required in the namespace form, use reader-conditional requires: let-go's embedded `string` namespace in the `:lg` branch and `clojure.string` in the `:default` branch.

### Parsing And Dispatch

Command selection follows `docs/initial_design.md`: the first non-option token that matches a command name selects the command. Before that token, only global options are parsed. After it, command options and global options are both accepted. If a selected command declares an option spelling already used globally, parsing returns a spec error.

Supported option forms:

- `--flag`
- `--value x`
- `--value=x`
- `-f`
- `-vf` for combined short booleans
- `-b x`
- `--` to stop option parsing

Parsing should be a small loop over tokens. It tracks the selected command, parsed global opts, parsed command opts, positional tokens, and whether option parsing has ended. Long option lookup uses `"--" + :long`; short lookup uses `"-" + :short`. Combined short groups only work when every char is a boolean short option. A value-taking short option inside a group is an error for v1.

Built-ins are handled by `parse` as tagged results:

- Root help: `help`, `--help`, `-h`
- Command help: `help create`, `create --help`, `create -h`
- Version: `--version`, plus `-v` only when `-v` is not declared by the app globally or by the selected command

The design doc uses global `-v` for verbose while also listing built-in version `-v`. User-declared options win within the parser phase that sees them. Before a command token, only global options can claim `-v`; if no global option claims it, `-v` returns the built-in version without looking ahead for a later command. After a command token, command-local and global options can claim `-v`; if neither claims it, `-v` returns the built-in version. `--version` remains available even when `-v` is claimed by user specs.

If argv is empty, or if parsing finishes without a selected command after consuming only valid global options, `parse` returns root help with `{:status :help ...}`. An unknown non-option token still returns `:error`.

If `--version` or built-in `-v` is requested and `app` has no `:version`, `parse` returns `{:status :error :message "No version available."}`.

### Help, Errors, And Validation

Help rendering is deterministic plain text. Root help includes app name, app doc, usage, global options, commands, and built-ins, including help and version when applicable. Command help includes command usage, command doc, args, command options, global options, and command-help built-ins only (`-h`, `--help`, and `help <command>`). Do not list version in command help. Option placeholders derive from `:key`: `:base` becomes `BASE`, `:branch-name` becomes `BRANCH-NAME`, and keys ending in `?` drop the question mark for placeholders.

Normal user mistakes return `{:status :error ...}` from `parse` rather than throwing. Cover unknown command, unknown option, missing option value, malformed combined short options, too few or too many args, missing required options, invalid values, duplicate option spellings, missing required spec fields, and invalid spec shapes where practical.

Validation specs have the documented shape `{:pred f :msg "..."}`. The predicate receives the raw parsed value: a string for value options and positional args, and `true` for boolean options. Validation failure returns the supplied message. Malformed validation specs include missing `:pred`, non-function `:pred`, missing `:msg`, and non-string `:msg`.

`run!` should not catch handler exceptions. Those are application errors, not parse errors.

### Testing Strategy

Tests are let-go tests in `test/tiny_cli/core_test.lg`, using the embedded `test` namespace. The tests should focus on pure helpers first because that is the stable cross-host surface:

- root and command help rendering
- empty argv and global-options-only argv returning root help
- root help, command help, and version tagged parse results
- command selection
- global options before and after the command
- command options before and after positional args
- long value options with space and equals
- short boolean and short value options
- combined short booleans
- `--` end-of-options behavior
- defaults and required options
- positional arity
- validation success and failure
- spec conflicts and malformed specs

Add `test/run.sh` and update `Makefile` so `make test` runs the let-go tests through lgx with the local unreleased let-go binary:

```bash
LGX_LG=/Users/andrew/Projects/let-go/lg lgx run test/tiny_cli/core_test.lg
```

Clojure and Babashka compatibility should be checked with load smoke commands if the corresponding executables are available. These are verification steps, not blockers if the tools are absent:

```bash
clojure -Sdeps '{:paths ["src"]}' -e "(require 'tiny-cli.core)"
bb -cp src -e "(require 'tiny-cli.core)"
```

## File Structure

- Modify `src/tiny_cli/core.cljc`: implement the v1 library.
- Modify `test/tiny_cli/core_test.lg`: add let-go tests for pure API and selected runner-safe behavior.
- Create `test/run.sh`: shell test runner for the repo's `test/` directory.
- Modify `Makefile`: point `make test` at `test/run.sh`, not the currently referenced `tests/run.sh`.
- Optionally modify `README.md`: update the API example only if implementation choices differ from the existing documented example.

## Tasks

### Task 1: Test Harness And Public Contracts

**Files:**
- Modify: `test/tiny_cli/core_test.lg`
- Create: `test/run.sh`
- Modify: `Makefile`
- Modify: `src/tiny_cli/core.cljc`

- [ ] **Step 1: Write failing public contract tests**
  Add tests requiring `[tiny-cli.core :as cli]`. Cover that `root-help`, `command-help`, and `parse` exist, return strings/maps, and that an example app can produce root help, command help, and a version parse result.

- [ ] **Step 2: Run tests to verify failure**
  Run: `LGX_LG=/Users/andrew/Projects/let-go/lg lgx run test/tiny_cli/core_test.lg`
  Expected: FAIL because the public functions are not implemented.

- [ ] **Step 3: Add the repo test runner**
  Create `test/run.sh` with `set -eu`, change to repo root, and run `LGX_LG=/Users/andrew/Projects/let-go/lg lgx run test/tiny_cli/core_test.lg`. Run `chmod +x test/run.sh`. Update `Makefile` so `make test` invokes `bash test/run.sh`.

- [ ] **Step 4: Implement minimal public API stubs**
  Replace the current top-level `prn` in `src/tiny_cli/core.cljc`. Define the namespace only, then minimal `root-help`, `command-help`, `parse`, and `run!` functions. Return enough deterministic data/text to satisfy only the contract tests. Avoid top-level side effects.

- [ ] **Step 5: Run tests to verify pass**
  Run: `make test`
  Expected: PASS for the initial public contract tests.

- [ ] **Step 6: Commit**
  `git commit -m "test: add tiny-cli core test harness"`

### Task 2: Help Rendering

**Files:**
- Modify: `test/tiny_cli/core_test.lg`
- Modify: `src/tiny_cli/core.cljc`

- [ ] **Step 1: Write failing help rendering tests**
  Cover root help for app name, doc, usage, global options, command list, and built-ins. Cover command help for usage, command doc, positional arg placeholders, command options, global options, defaults, and command-help built-ins. Assert command help does not list version.

- [ ] **Step 2: Run tests to verify failure**
  Run: `LGX_LG=/Users/andrew/Projects/let-go/lg lgx run test/tiny_cli/core_test.lg`
  Expected: FAIL on missing or incomplete help text.

- [ ] **Step 3: Implement deterministic help helpers**
  Add private helpers for command lookup, option placeholder derivation, usage strings, option formatting, and line joining. Keep formatting simple and stable; tests should assert meaningful substrings and exact small strings where useful, not fragile whole-screen spacing.

- [ ] **Step 4: Run tests to verify pass**
  Run: `make test`
  Expected: PASS.

- [ ] **Step 5: Commit**
  `git commit -m "feat: render tiny-cli help text"`

### Task 3: Option Parsing

**Files:**
- Modify: `test/tiny_cli/core_test.lg`
- Modify: `src/tiny_cli/core.cljc`

- [ ] **Step 1: Write failing option parser tests**
  Cover boolean globals, command options, options after the command, options after positional args, long values with space and equals, short values with space, combined short booleans, unknown options, missing values, and `--` end-of-options behavior.

- [ ] **Step 2: Run tests to verify failure**
  Run: `LGX_LG=/Users/andrew/Projects/let-go/lg lgx run test/tiny_cli/core_test.lg`
  Expected: FAIL on parser assertions.

- [ ] **Step 3: Implement option lookup and token loop**
  Build long and short option indexes from global and selected command specs. Parse tokens according to the approved rules. Insert parsed values into `:global` or `:opts` by option `:key`. Treat command-local/global spelling collisions for the selected command as spec errors.

- [ ] **Step 4: Run tests to verify pass**
  Run: `make test`
  Expected: PASS.

- [ ] **Step 5: Commit**
  `git commit -m "feat: parse tiny-cli options"`

### Task 4: Command, Args, Defaults, Required Values, And Validation

**Files:**
- Modify: `test/tiny_cli/core_test.lg`
- Modify: `src/tiny_cli/core.cljc`

- [ ] **Step 1: Write failing command and validation tests**
  Cover unknown command, too few args, too many args, fixed positional arg mapping, defaults, required option failures, successful validation, validation failure messages, malformed validation specs, missing command `:run`, missing option names, and duplicate command names.

- [ ] **Step 2: Run tests to verify failure**
  Run: `LGX_LG=/Users/andrew/Projects/let-go/lg lgx run test/tiny_cli/core_test.lg`
  Expected: FAIL on command/validation assertions.

- [ ] **Step 3: Implement app spec checks and final parse assembly**
  Add lightweight spec checks that return `:error` maps. Apply option defaults before required checks. Map positional tokens to declared `:args`. Run validation predicates on option and arg values. Return the final `:ok` result with the selected command spec and handler context.

- [ ] **Step 4: Run tests to verify pass**
  Run: `make test`
  Expected: PASS.

- [ ] **Step 5: Commit**
  `git commit -m "feat: validate tiny-cli commands"`

### Task 5: Built-ins And Runner

**Files:**
- Modify: `test/tiny_cli/core_test.lg`
- Modify: `src/tiny_cli/core.cljc`

- [ ] **Step 1: Write failing built-in and runner tests**
  Cover `parse` results for empty argv, global-options-only argv, `help`, `help <command>`, `<command> --help`, `<command> -h`, `--help`, `-h`, `--version`, and `-v` when unclaimed. Cover that user-declared `-v` wins over built-in version in the phase where the option appears: global `-v` before a command is global, unclaimed `-v` before a command is version, command-local `-v` after a command is command-local, and unclaimed `-v` after a command is version. Cover `--version` error behavior when `:version` is absent. For `run!`, keep tests limited to behavior that does not require asserting process exit; prefer testing the pure interpreter path if one is factored out.

- [ ] **Step 2: Run tests to verify failure**
  Run: `LGX_LG=/Users/andrew/Projects/let-go/lg lgx run test/tiny_cli/core_test.lg`
  Expected: FAIL on built-in result assertions.

- [ ] **Step 3: Implement built-in parse results**
  Add built-in handling to `parse` without invoking handlers. Ensure help/version text is generated by the same pure rendering helpers used by public functions.

- [ ] **Step 4: Implement host-specific `run!`**
  Add reader-conditional host helpers for argv, writing output/error, and exiting. In let-go, use `os/args`, `write!`, and `os/exit`. Let-go argv handling must account for both interpreted and bundled execution: when the second `os/args` entry ends with `.lg` or `.cljc`, drop the executable and script path; otherwise drop only the executable. In the `:default` branch, use `*command-line-args*`, `print`, `binding` to `*err*`, and `System/exit`. `run!` interprets `parse` results, calls command handlers for `:ok`, prints help/version to stdout, prints errors to stderr, and exits non-zero for errors.

- [ ] **Step 5: Run tests to verify pass**
  Run: `make test`
  Expected: PASS.

- [ ] **Step 6: Commit**
  `git commit -m "feat: add tiny-cli built-ins and runner"`

### Task 6: Cross-host Smoke Verification And Docs

**Files:**
- Modify: `README.md` if needed
- Modify: `docs/initial_design.md` only if the implementation intentionally clarifies an ambiguity

- [ ] **Step 1: Run let-go tests**
  Run: `make test`
  Expected: PASS.

- [ ] **Step 2: Run Clojure load smoke if available**
  Run: `command -v clojure >/dev/null && clojure -Sdeps '{:paths ["src"]}' -e "(require 'tiny-cli.core)" || true`
  Expected: no load error when `clojure` is installed; otherwise the command skips.

- [ ] **Step 3: Run Babashka load smoke if available**
  Run: `command -v bb >/dev/null && bb -cp src -e "(require 'tiny-cli.core)" || true`
  Expected: no load error when `bb` is installed; otherwise the command skips.

- [ ] **Step 4: Build let-go bundle smoke**
  Run: `LGX_LG=/Users/andrew/Projects/let-go/lg lgx run -b bin/tiny-cli src/tiny_cli/core.cljc`
  Expected: bundle succeeds without printing from compile-time top-level forms.

- [ ] **Step 5: Update docs if needed**
  If the implemented public API differs from `README.md` or `docs/initial_design.md`, update docs to match. Do not add unrelated roadmap items.

- [ ] **Step 6: Commit**
  `git commit -m "docs: document tiny-cli core api"`

## Verification Checklist

- [ ] `LGX_LG=/Users/andrew/Projects/let-go/lg lgx run test/tiny_cli/core_test.lg`
- [ ] `make test`
- [ ] Optional Clojure load smoke when `clojure` is installed
- [ ] Optional Babashka load smoke when `bb` is installed
- [ ] `LGX_LG=/Users/andrew/Projects/let-go/lg lgx run -b bin/tiny-cli src/tiny_cli/core.cljc`

## Notes For Implementation

- Do not use dependencies.
- Do not add automatic coercion.
- Do not add subcommands, optional args, variadic args, env var support, config files, shell completions, or middleware.
- Avoid top-level side effects in `.cljc`; let-go AOT compilation evaluates top-level forms.
- Prefer plain data and small helpers over protocols or macros.
- Keep tests in `test/tiny_cli/core_test.lg`; Clojure and Babashka checks are smoke commands, not separate test suites for v1.
