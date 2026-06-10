# Options-Before-Positionals Grammar Implementation Plan

> **For agentic workers:** Use executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make command and global options come before positional arguments in every command, so a single ordering rule applies everywhere and variadic commands can finally carry their own options.

**Tech Stack:** `let-go` / Clojure / Babashka (`.cljc`), tested via `lgx test` (let-go) and `lgx test-all` (all three runtimes).

---

## Design

### Background

`tiny-cli.core/parse` (`src/tiny_cli/core.cljc`) walks `argv` with a loop that accumulates parse state. Today:

- For **non-variadic** commands, options permute freely around positionals — `create --base main NAME` and `create NAME --base main` are both accepted (tested at `core_test.cljc:160-161`).
- For **variadic** commands, everything after the fixed args is slurped verbatim, so a command's own options can't survive — the library bans `:variadic` + `:opts` outright (`core.cljc:350-351`).

This plan replaces free ordering with one uniform rule and removes the ban.

### The rule

**Within a command, options come before positionals.** The first positional token ends option parsing for that command; from there every token is positional, taken verbatim.

This is the docker-run grammar: `app [global options] COMMAND [options] <ARG> [VAR...]`. Built-ins (`--help/-h`, `--version/-v`) are options too and follow the same rule — no carve-out.

### Parsing model: a `:phase` state field

Add a per-parse `:phase` to the loop state with three values, and route **all** positional collection through one slurp branch (consolidating today's three separate collecting paths: the greedy-variadic branch at `core.cljc:540-544`, the `--` dump at `:575-578`, and per-token collection at `:617-618`).

- **`:options`** (initial) — before a command is selected, parse global options and the command token; after the command is selected, parse global + command options and built-ins. Stays here until the first positional or `--`.
- **`:args`** — entered at the first non-option token after the command. Every remaining token is positional, verbatim. On a **non-variadic** command an option-like token here is a user error → `Options must appear before arguments: <token>`. On a **variadic** command, option-like tokens are slurped into the variadic vector.
- **`:args-raw`** — entered by an explicit `--`. Same as `:args` but never raises the stray-option error, so a dash-leading positional passes through (e.g. `create -- -weird`).

Transitions while a command is selected and phase is `:options`:

- option-like token → parse as option (merged global+command index), stay `:options`.
- `--help`/`-h` → command help; `--version` or unclaimed `-v` → version.
- `--` → phase `:args-raw`, drop the marker.
- any other token → phase `:args`, collect it as the first positional.

Once phase is `:args`/`:args-raw`, the single slurp branch (placed first in the loop, as the greedy-variadic branch is today) collects every token. `finalize-context` (`core.cljc:448-479`) is unchanged: it splits the collected positionals into fixed args + the variadic vector and enforces too-few / too-many.

### Capability unlock: variadic + opts

Because every option resolves before the first positional, a variadic command can now declare `:opts`. Remove the `(and (:variadic command) (seq (:opts command)))` clause from `command-spec-error` (`core.cljc:350-351`). Options for a variadic command must precede its first positional, exactly like every other command; anything after the first positional is part of the variadic payload.

Semantics to lock in tests:
- `run -d feat-x git status` → `:opts {:detach? true}`, `:args {:name "feat-x" :cmd ["git" "status"]}`.
- `run feat-x -d echo` → `:opts {}`, `:cmd ["-d" "echo"]` (the `-d` is past the first positional, so it is part of the command, not an option).

### Help rendering

Reorder `command-usage` (`core.cljc:90-103`) so the `[options]` segment sits before the args placeholder:

```
deploy [global options] service [options] <SERVICE>
wtr    [global options] run     [options] <NAME> [CMD...]
```

`command-usage-min` (the compact root-listing form, which shows no options) is unchanged.

### Error handling

New: `Options must appear before arguments: <token>` for an option-like token in phase `:args` on a non-variadic command. This prevents the silent footgun where a stray `--flag` would otherwise become a fixed arg's literal string value. Existing errors (Unknown option, Missing value, Too many arguments, Missing argument) are unchanged; a few test inputs move the offending option ahead of the positional so they keep exercising those paths.

### Breaking change

Trailing-option forms now error: `create NAME --base main`, `create NAME --base=main`, `create NAME --verbose`, and `create NAME --help` (help is no longer intercepted after a positional). This is intentional and documented. The library is pre-1.0; the only known consumer, `wtr`, is updated separately (see Out of Scope).

### Testing strategy

Unit tests drive `cli/parse` directly (pure, no exit). Fast loop: `lgx test` (let-go only). Full gate before finishing: `lgx test-all` (let-go + Clojure + Babashka). Format with `lgx fmt`.

## File Structure

- **Modify** `src/tiny_cli/core.cljc` — `parse` loop (`:phase` machine + stray-option error), `command-usage` reorder, remove the `:variadic`+`:opts` spec clause.
- **Modify** `test/tiny_cli/core_test.cljc` — rewrite the free-ordering cases, replace the ban test, add stray-option / variadic-with-opts / usage-order coverage.
- **Modify** `README.md` — "Variadic Trailing Args" section, command-help example, example invocations, a short "Option ordering" note.
- **Modify** `docs/initial_design.md` — the "Parsing Behaviour" ordering claims.

---

## Task 1: Options-first parsing core (`:phase` machine + stray-option error)

**Files:**
- Modify: `src/tiny_cli/core.cljc`
- Test: `test/tiny_cli/core_test.cljc`

- [x] **Step 1: Update and add the failing tests** (in `deftest option-parsing`)
  - Rewrite "parses global option after command" (`:154-157`): keep a valid case where the global flag sits after the command but **before** the positional — `["create" "-v" "feature/login"]` → `:ok`, `:global {:verbose? true}`.
  - Rewrite "parses command option before and after positional args" (`:159-165`): assert the **before** form `["create" "--base" "main" "feature/login"]` is `:ok` with `:base "main"`, and that the **after** form `["create" "feature/login" "--base" "main"]` is now `:error` matching `#"Options must appear before"`.
  - Rewrite "parses long value option with equals" (`:167-170`) to options-first: `["create" "--base=main" "feature/login"]`.
  - Leave "parses short value option with space" (`:172-175`) as-is (already options-first).
  - Rewrite "unknown option is an error" (`:215-218`) to `["create" "--unknown" "feature/login"]` so it still hits `#"Unknown option"`.
  - Rewrite "missing option value is an error" (`:220-223`) to `["create" "--base"]` so it still hits `#"Missing value"`.
  - Leave "end-of-options treats following values as positional" (`:225-233`) as-is (covers `:args-raw`).
  - Add "rejects an option after a positional": `["create" "feature/login" "--base" "main"]` → `:error`, `#"Options must appear before arguments"`.

- [x] **Step 2: Run tests to verify they fail**
  Run: `lgx test`
  Expected: FAIL — rewritten/added cases don't match current free-ordering behavior.

- [x] **Step 3: Implement the `:phase` machine**
  In `parse`, add `:phase :options` to the initial loop state. Restructure the loop so positional collection flows through a single slurp branch placed first: when phase is `:args`/`:args-raw`, append the token verbatim to `:positionals`, except that phase `:args` on a non-variadic command raises `Options must appear before arguments: <token>` for an option-like token. In the command-selected `:options` branch, add the two transitions: `--` → set phase `:args-raw` (drop the marker); first non-option token → set phase `:args` and collect it. Keep the `:variadic`+`:opts` ban untouched in this task. Remove the now-dead greedy-variadic branch, the `--` dump branch, and the trailing per-token positional branch.

- [x] **Step 4: Run tests to verify they pass**
  Run: `lgx test`
  Expected: PASS (all of `deftest option-parsing`, plus the existing `variadic-args` and `command-args-defaults-and-validation` still green).

- [x] **Step 5: Format and commit**
  Run: `lgx fmt`
  `git commit -am "feat: parse command options before positional args"`

## Task 2: Allow variadic commands to declare options

**Files:**
- Modify: `src/tiny_cli/core.cljc`
- Test: `test/tiny_cli/core_test.cljc`

- [x] **Step 1: Replace the ban test with capability tests** (in `deftest variadic-args`)
  - Delete "a command cannot declare both :variadic and :opts" (`:698-705`).
  - Add a `run-app` variant whose `run` command also has `:opts [{:key :detach? :short "d" :long "detach"}]`.
  - Add "parses an option before the variadic fixed arg": `["run" "-d" "feat-x" "git" "status"]` → `:ok`, `:opts {:detach? true}`, `:args {:name "feat-x" :cmd ["git" "status"]}`.
  - Add "an option after the fixed arg is part of the command": `["run" "feat-x" "-d" "echo"]` → `:ok`, `:opts` has no `:detach?`, `:cmd ["-d" "echo"]`.

- [x] **Step 2: Run tests to verify they fail**
  Run: `lgx test`
  Expected: FAIL — the spec ban rejects `:opts` on a variadic command.

- [x] **Step 3: Remove the ban**
  Delete the `(and (:variadic command) (seq (:opts command)))` clause and its `error-result` from `command-spec-error` (`core.cljc:350-351`).

- [x] **Step 4: Run tests to verify they pass**
  Run: `lgx test`
  Expected: PASS.

- [x] **Step 5: Format and commit**
  Run: `lgx fmt`
  `git commit -am "feat: allow variadic commands to declare options"`

## Task 3: Reorder the command-help usage string

**Files:**
- Modify: `src/tiny_cli/core.cljc`
- Test: `test/tiny_cli/core_test.cljc`

- [x] **Step 1: Add usage-order assertions**
  - In `deftest help-rendering` (or alongside the existing command-help assertion near `:472`), assert the full command-usage line places options before the arg, e.g. the `create` command help contains `create [options] <BRANCH>`.
  - In `deftest variadic-args`, assert the `run` command help contains `run [options] <NAME> [CMD...]`.

- [x] **Step 2: Run tests to verify they fail**
  Run: `lgx test`
  Expected: FAIL — `[options]` currently renders last.

- [x] **Step 3: Reorder `command-usage`**
  In `command-usage` (`core.cljc:90-103`), move the `(when (seq (:opts command)) "[options]")` segment to sit immediately after the command name and before the args/variadic placeholders.

- [x] **Step 4: Run tests to verify they pass**
  Run: `lgx test`
  Expected: PASS.

- [x] **Step 5: Format and commit**
  Run: `lgx fmt`
  `git commit -am "feat: render options before args in command usage"`

## Task 4: Update documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/initial_design.md`

- [ ] **Step 1: Update `README.md`**
  - "Variadic Trailing Args": remove the wording that a command's `:opts` cannot combine with / appear after the variadic. State that a variadic command may declare `:opts`, which must precede its first positional, after which everything is slurped into the variadic vector.
  - Update the command-help example (the `deploy [global options] service <SERVICE> [options]` block near line 345) to the new order.
  - Reorder example invocations that put options after a positional (e.g. the `deploy --dry-run service api --env prod` example) into options-first form.
  - Add a short "Option ordering" note: options come before positionals in every command; built-ins (`--help/-h`, `--version/-v`) follow the same rule; `--` ends option parsing.

- [ ] **Step 2: Update `docs/initial_design.md`**
  In "Parsing Behaviour", revise the "Global options can appear before or after the command" example and the "After command token: command options and global options are both accepted" rule to the options-before-positionals rule. Note that variadic commands may now declare options.

- [ ] **Step 3: Re-read both docs for internal consistency**
  Confirm every code example obeys the new ordering and no stale "cannot combine" wording remains. Use /writing-clearly for the new prose.

- [ ] **Step 4: Commit**
  `git commit -am "docs: document options-before-positionals ordering"`

## Task 5: Full cross-runtime verification

- [ ] **Step 1: Format**
  Run: `lgx fmt`
  Expected: no changes (or commit them).

- [ ] **Step 2: Run the full suite on all runtimes**
  Run: `lgx test-all`
  Expected: PASS on let-go, Clojure, and Babashka.

- [ ] **Step 3: Commit any remaining changes**
  `git commit -am "test: verify options-first across runtimes"` (only if Step 1 produced changes).

---

## Out of Scope (follow-up in the `wtr` repo)

`wtr` is a sibling repo and is not modified by this plan. After release, `wtr` needs:
- a `tiny-cli` dep-sha bump,
- usage-string fixes for the breaking change (`wtr create --from X <NAME>`, `wtr remove --force <NAME>`),
- optionally, giving `run` its own options now that variadic + opts is allowed.
