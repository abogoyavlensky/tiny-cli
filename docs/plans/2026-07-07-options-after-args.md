# Options-After-Args (Interleaved Options) Implementation Plan

> **For agentic workers:** Use executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let options appear before, between, or after positional args on regular commands, while variadic commands keep requiring options before the first positional.

**Tech Stack:** `let-go` / Clojure / Babashka (`.cljc`), tested via `lgx test` (let-go) and `lgx test-all` (all three runtimes).

---

## Design

### Background

`tiny-cli.core/parse` (`src/tiny_cli/core.cljc`) walks `argv` with a three-phase state machine (introduced by `docs/plans/2026-06-10-options-before-positionals.md`):

- **`:options`** — parse global/command options and built-ins; the first positional token ends this phase.
- **`:args`** — entered at the first positional. Every remaining token is positional. On a **non-variadic** command an option-like token here errors: `Options must appear before arguments: <token>`. On a **variadic** command it is slurped into the payload.
- **`:args-raw`** — entered via `--`; everything is positional, never errors.

This plan relaxes the rule for non-variadic commands only.

### The new rule

**On a non-variadic command, options and positionals interleave freely** (GNU/getopt style). `deploy service --env prod api`, `deploy service api --env prod`, and mixed forms all parse identically. **On a variadic command, nothing changes:** the first positional starts the slurp, so options must still come before it — a variadic command cannot distinguish its own flags from payload flags.

### Parsing model change

The change is a *removal* of a phase transition, not new machinery:

- The `:else` branch of the loop (first positional while `:phase :options`) sets `:phase :args` **only when `(:variadic command)`**. A non-variadic command stays in `:options` for the whole command line, collecting positionals as they come.
- The non-`:options` phase branch becomes a pure slurp: `:args` is now only ever entered on variadic commands and `:args-raw` already never errors, so the `Options must appear before arguments` error branch is dead code — delete it and the error entirely.
- `--` handling is untouched: while in `:options` it flips to `:args-raw`, which now also works after positionals on non-variadic commands (`deploy service api -- --weird-name`).

`finalize-context`, option indexes, defaults, validation, and help rendering are all unchanged. Global options already merge into the post-command option index, so a global flag after positionals needs no extra code.

### Built-ins follow the same rule

Because a non-variadic command never leaves `:options`, the existing built-in branches now also fire after positionals — no code change, but it is deliberate, documented behavior:

- `deploy service api --help` → command help (was an ordering error).
- `deploy service api --version` (and unclaimed `-v`) → version output.
- On variadic commands these tokens are payload after the first positional, exactly as today.

### Error-behavior deltas

- `Options must appear before arguments` — **removed** (no input can trigger it).
- An unknown flag after positionals on a non-variadic command → `Unknown option: <spelling>` (was the ordering error).
- A value-taking option as the last token → `Missing value for option: <spelling>` regardless of position.
- `Too many arguments` / `Missing argument` unchanged.

Strictly more permissive for non-variadic commands; no accepted input becomes an error, no variadic behavior changes. Not a breaking change.

### Completion follows the parser

Two fixes in `src/tiny_cli/completion.cljc`:

1. **`candidates` flag gate.** The flag branch is gated on `(and (str/starts-with? cur "-") (empty? positionals))`. New gate: offer flags when `cur` starts with `-` and either no positionals have been typed **or** the selected command is a non-variadic command map. Variadic commands keep the old behavior (no flags once the payload starts).
2. **`split-context` variadic payload counting.** Today every `-`-prefixed word is skipped as an option, and a value-taking spelling consumes the next word — even inside a variadic payload. Per the parser, once a variadic command has seen its first positional, every remaining word is a positional. Add that check **before** the `value-opt` and dash checks: when `command` is a map with `:variadic` and `positionals` is non-empty, conj the word onto `positionals`. This fixes miscounted positional slots (e.g. a two-fixed-arg variadic command offering the wrong `:complete`) and stops a payload flag that spells a value option from swallowing the next word.

### Testing strategy

Unit tests drive `cli/parse` and `completion/candidates` directly (pure, no exit). Fast loop: `lgx test` (let-go only). Full gate before finishing: `lgx test-all` (let-go + Clojure + Babashka). Format with `lgx fmt`. The existing `variadic-args` deftest is the regression lock for the unchanged variadic behavior — it must stay green untouched.

## File Structure

- **Modify** `src/tiny_cli/core.cljc` — `parse` loop: variadic-only `:args` transition, delete the ordering-error branch, update the phase comments and any docstring wording.
- **Modify** `src/tiny_cli/completion.cljc` — `candidates` flag gate, `split-context` variadic payload counting (update both docstrings).
- **Modify** `test/tiny_cli/core_test.cljc` — rewrite the rejection test, add interleaved/built-in/`--` coverage.
- **Modify** `test/tiny_cli/completion_test.cljc` — rewrite the "no flags after a positional" test, add variadic completion cases.
- **Modify** `README.md` — "Option Ordering" section rewrite, "Variadic Trailing Args" constraint wording.
- **Modify** `docs/initial_design.md` — any options-before-positionals ordering claims.

---

## Task 1: Interleaved option parsing for non-variadic commands

**Files:**
- Modify: `src/tiny_cli/core.cljc`
- Test: `test/tiny_cli/core_test.cljc`

- [x] **Step 1: Update and add the failing tests** (in `deftest option-parsing`, using the existing `app` fixture with the `create` command)
  - Rewrite "rejects an option after a positional arg" into "parses an option after a positional arg": `["create" "feature/login" "--base" "main"]` → `:ok`, `:args {:branch "feature/login"}`, `:opts` contains `:base "main"`.
  - Add "parses an `=` option after a positional arg": `["create" "feature/login" "--base=main"]` → `:ok`, same result.
  - Add "parses a global option after a positional arg": `["create" "feature/login" "--verbose"]` (or the fixture's actual global flag) → `:ok`, flag lands in `:global`.
  - Add "interleaves options and positionals" on a command with two fixed args (add a small fixture if none exists): `[cmd "a" "--flag" "b"]` → `:ok`, both positionals filled, flag parsed.
  - Add "unknown option after a positional is an error": `["create" "feature/login" "--unknown"]` → `:error`, `#"Unknown option"`.
  - Add "missing option value at the end is an error": `["create" "feature/login" "--base"]` → `:error`, `#"Missing value"`.
  - Add "`--help` after a positional shows command help": `["create" "feature/login" "--help"]` → `:help` for `create`.
  - Add "`--version` after a positional prints version" (use a fixture with `:version`): → `:version`.
  - Add "`--` after a positional still ends option parsing": on the two-fixed-arg fixture, `[cmd "a" "--" "-b"]` → `:ok` with `"-b"` as the second positional.
  - Do **not** touch `deftest variadic-args` — it locks the unchanged variadic behavior.

- [x] **Step 2: Run tests to verify they fail**
  Run: `lgx test`
  Expected: FAIL — the new cases hit `Options must appear before arguments`.

- [x] **Step 3: Implement the parse-loop change**
  In `parse` (`src/tiny_cli/core.cljc`):
  - In the `:else` (first-positional) branch, set `:phase :args` only when `(:variadic command)`; otherwise keep `:phase :options` and just conj the positional.
  - Simplify the `(not= :options (:phase state))` branch to an unconditional positional conj; delete the `option-token?` error check and the `Options must appear before arguments` message.
  - Update the phase comment block above that branch to describe the new model: `:args` is variadic rest-mode, `:args-raw` is post-`--`, non-variadic commands interleave.

- [x] **Step 4: Run tests to verify they pass**
  Run: `lgx test`
  Expected: PASS — including the untouched `variadic-args` deftest.

- [x] **Step 5: Format and commit**
  Run: `lgx fmt`
  `git commit -am "feat: allow options after positional args on non-variadic commands"`

> Deviation: two existing tests asserted the old rejection (the named one plus "parses command option before the positional; rejects it after") — both rewritten to expect `:ok`. Codex review: one P2 (completion still on old rule) — addressed by Task 2 as planned.

## Task 2: Completion offers flags after positionals (non-variadic)

**Files:**
- Modify: `src/tiny_cli/completion.cljc`
- Test: `test/tiny_cli/completion_test.cljc`

- [x] **Step 1: Update and add the failing tests** (in `deftest candidates-flags`)
  - Rewrite "no flags after a positional (tiny-cli rejects options there)" into "flags after a positional on a regular command": same input, now expects the global + command long flags plus `--help`.
  - Add "no flags after the first positional on a variadic command": with a variadic fixture (mirror `run-app` from core_test), words like `["run" "feat-x"]` and cur `"-"` → `[]`.
  - Add "flags before the first positional on a variadic command still complete": words `["run"]`, cur `"-"` → the flag list.

- [x] **Step 2: Run tests to verify they fail**
  Run: `lgx test`
  Expected: FAIL — flags are currently suppressed whenever positionals exist.

- [x] **Step 3: Implement the gate change**
  In `candidates`, change the flag-branch condition from `(and (str/starts-with? cur "-") (empty? positionals))` to also allow the case where `command` is a map without `:variadic`, e.g. `(and (str/starts-with? cur "-") (or (empty? positionals) (and (map? command) (not (:variadic command)))))`. Update the `candidates` docstring if it mentions ordering.

- [x] **Step 4: Run tests to verify they pass**
  Run: `lgx test`
  Expected: PASS.

- [x] **Step 5: Format and commit**
  Run: `lgx fmt`
  `git commit -am "feat: complete flags after positional args in shell completion"`

> Deviation: none. Only one test failed at Step 2 (the two variadic cases already passed under the old gate). The `candidates` docstring doesn't mention ordering, so no docstring change was needed.

## Task 3: Completion counts variadic payload words as positionals

**Files:**
- Modify: `src/tiny_cli/completion.cljc`
- Test: `test/tiny_cli/completion_test.cljc`

- [ ] **Step 1: Add the failing tests**
  - Add a fixture: a variadic command with **two** fixed args where the second fixed arg has a `:complete` list, plus a global value-taking option with a `:complete` list.
  - Add "a dash word in the variadic payload counts as a positional": words `[cmd "a" "-x"]`, cur `""` → the second fixed arg's candidates are **not** offered (the cursor is on the variadic slot), i.e. result `[]` or the variadic's candidates if it has `:complete`.
  - Add "a value-option spelling inside the payload does not swallow the next word": on a one-fixed-arg variadic command, words `[cmd "feat-x" "--base-dir"]`, cur `""` → not the option's `:complete` candidates (the parser treats `--base-dir` as payload), expected `[]`.

- [ ] **Step 2: Run tests to verify they fail**
  Run: `lgx test`
  Expected: FAIL — `split-context` skips dash words and honors value options inside the payload.

- [ ] **Step 3: Implement the payload check**
  In `split-context`, add a branch **before** the `value-opt` and `(str/starts-with? w "-")` checks: when `command` is a map, has `:variadic`, and `positionals` is non-empty, recur with the word conj'd onto `positionals`. Update the docstring to state the variadic-payload rule.

- [ ] **Step 4: Run tests to verify they pass**
  Run: `lgx test`
  Expected: PASS.

- [ ] **Step 5: Format and commit**
  Run: `lgx fmt`
  `git commit -am "fix: treat variadic payload words as positionals in completion"`

## Task 4: Update documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/initial_design.md`

- [ ] **Step 1: Rewrite the README "Option Ordering" section**
  New rule statement: on regular commands, options and positional args interleave freely — before, between, or after (`deploy service api --env prod` now works). Built-ins (`--help/-h`, `--version/-v`) follow the same rule. On variadic commands, options must come before the first positional; after it, every token is payload. `--` still ends option parsing anywhere, letting a positional start with a dash. Replace the current `# error:` example line with a valid interleaved example plus a variadic counter-example.

- [ ] **Step 2: Update the "Variadic Trailing Args" section**
  Adjust the constraints paragraph: options-before-positionals now applies *only* to variadic commands (it currently reads as the universal rule). Keep the rest-mode description as-is.

- [ ] **Step 3: Update `docs/initial_design.md`**
  Revise any "options must come before positionals" parsing-rule claims to the new split rule (interleaved for regular commands, options-first for variadic).

- [ ] **Step 4: Re-read both docs for internal consistency**
  Confirm every example invocation parses under the new rule and no stale ordering-error wording remains. Run `grep -rn "Options must appear before" README.md docs src test` — expected: no matches outside `docs/plans/`. Use /writing-clearly for the new prose.

- [ ] **Step 5: Commit**
  `git commit -am "docs: document interleaved option ordering"`

## Task 5: Full cross-runtime verification

- [ ] **Step 1: Format and lint**
  Run: `lgx fmt` then `lgx lint`
  Expected: no formatting changes, no lint findings.

- [ ] **Step 2: Run the full suite on all runtimes**
  Run: `lgx test-all`
  Expected: PASS on let-go, Clojure, and Babashka.

- [ ] **Step 3: Commit any remaining changes**
  Only if steps 1–2 produced fixes.

---

## Out of Scope

- No changes to help rendering: usage strings keep showing `[options]` before the args placeholders — a conventional summary, not a strict grammar.
- No changes to the `wtr` consumer; the change is purely permissive, so nothing downstream breaks.
