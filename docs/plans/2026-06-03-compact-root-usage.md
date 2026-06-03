# Compact Root Usage Implementation Plan ✅ Completed

> **For agentic workers:** Use executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make root help (`tool --help`) show compact per-command usage patterns in the `Usage:` section and remove the separate `Commands:` section.

**Tech Stack:** Clojure `.cljc` (cross-target: let-go, Clojure, Babashka), tested via `lgx test-all`, formatted via `lgx fmt-check`.

---

## Design

### Summary

`tiny-cli` currently renders root help as a generic usage grammar followed by a
separate `Commands:` list:

```text
Usage:
  wtr [global options] <command> [args] [options]
  wtr help [command]
  wtr --help

Commands:
  create          Create a worktree for a branch.
  help [command]  Show help.
```

That forces users to combine two sections mentally, and often still pushes them
to run command-specific help just to learn the basic command shape. The new root
help should make `Usage:` a compact command cheat sheet, similar to `lgx`:

```text
Usage:
  wtr create <BRANCH>  Create a worktree for a branch.
  wtr help [command]   Show a command help.
  wtr --help           Show the tool help.
  wtr --version        Print version.
```

The compact root rows use command names plus positional and variadic
placeholders only. They intentionally omit `[global options]` and command
`[options]`; command-specific help remains the place for exact option details,
defaults, arg docs, and the full command usage grammar.

### Rendering

`src/tiny_cli/core.cljc` already has the pieces needed for this without changing
the app spec or parser:

- `command-usage-min` renders `app command <ARGS> [VARIADIC...]` and omits
  global/command option markers.
- `align-rows` renders `[label doc]` pairs with docs aligned to a shared column.
- `root-command-built-in-rows` and `root-option-built-in-rows` already model
  built-in help/version rows, but they are split across `Commands:` and
  `Global Options:`.

Replace the root help body so the `Usage:` section is assembled from aligned
rows:

- User commands: label is `(command-usage-min app command)`, doc is
  `(:doc command)`.
- Built-ins:
  - `<app> help [command]` -> `Show a command help.`
  - `<app> --help` -> `Show the tool help.`
  - `<app> --version` -> `Print version.` only when `(:version app)` is present.

Remove the root `Commands:` section entirely. Root `Global Options:` should list
only user-defined `(:opts app)` rows. If there are no user-defined global
options, omit the whole `Global Options:` section so root help does not contain
an empty heading.

### Scope Guard

This plan does not change parsing, command dispatch, validation, command help,
or the app spec. `command-help` should keep its current full usage output:

```text
wtr [global options] create <BRANCH> [options]
```

It should also keep its command-help built-ins line:

```text
wtr create -h, --help  Show help for create.
```

### Error Handling

No new runtime error handling is needed. This is a pure rendering change over
already-validated app data. Existing spec validation continues to reject
reserved command names, invalid options, duplicate keys, and invalid command
definitions before help rendering is used through `parse` or `run!`.

### Testing Strategy

Use focused rendering tests around `root-help`:

- Root help includes compact command usage rows with fixed args.
- Root help includes compact variadic rows such as `wtr run <NAME> [CMD...]`.
- Root help omits the generic grammar row and the `Commands:` heading.
- Root usage docs remain aligned across user command rows and built-in rows.
- Built-in help/version rows appear in `Usage:`, while `Global Options:` lists
  only user-defined global options.
- Negative assertions about built-ins in `Global Options:` should inspect only
  the `Global Options:` section, because `--help` and `--version` should still
  appear in `Usage:`.
- `Global Options:` is omitted when the app has no user-defined global options.
- `command-help` output remains unchanged for the existing command-help tests.

## File Structure

- `src/tiny_cli/core.cljc` - update root-help rendering helpers and `root-help`
  assembly. Reuse existing `command-usage-min` and `align-rows`; do not change
  parsing or command-help behavior.
- `test/tiny_cli/core_test.cljc` - update root-help expectations and add focused
  tests for compact command rows, variadic rows, built-in rows, alignment, and
  omission of empty `Global Options:`.
- `README.md` - update the root-help example output so it matches the new
  compact `Usage:` layout and the removal of the `Commands:` block.

## Tasks

### Task 1: Render Compact Command Rows In Root Usage

**Files:**
- Modify: `src/tiny_cli/core.cljc`
- Test: `test/tiny_cli/core_test.cljc`

- [x] **Step 1: Write failing root-help tests**
  Update `help-rendering` so `(cli/root-help app)` no longer expects
  `Commands:` and instead expects a compact command row matching
  `wtr create <BRANCH>`.

  Add focused assertions that root help:
  - Does not contain `wtr [global options] <command> [args] [options]`.
  - Does not contain a `Commands:` heading.
  - Contains `wtr create <BRANCH>` with the command doc.
  - Contains `wtr help [command]`, `wtr --help`, and `wtr --version` usage rows.
  - Still contains the user-defined global option row `-v, --verbose`.
  - Does not list `-h, --help` or `--version` inside `Global Options:`. Make
    this assertion section-scoped by extracting the `Global Options:` block
    rather than searching the whole help text, since those built-ins should
    still appear in `Usage:`.

- [x] **Step 2: Add failing variadic and empty-global-options tests**
  In the existing `variadic-args` test group or a nearby help-rendering test,
  assert `(cli/root-help run-app)` contains `wtr run <NAME> [CMD...]`.

  Add a small app with `:commands` but no `:opts`, then assert its root help does
  not contain `Global Options:`. This guards against an empty heading after
  built-in help/version rows move into `Usage:`.

  Update the existing root help alignment test so it locates full compact usage
  labels such as `  wtr ls`, `  wtr create-branch`, and
  `  wtr help [command]`, rather than the old bare command labels.

- [x] **Step 3: Run tests to verify they fail**
  Run: `lgx test`
  Expected: FAIL because root help still renders the generic usage line and
  separate `Commands:` section.

- [x] **Step 4: Implement the root usage row helpers**
  In `src/tiny_cli/core.cljc`, add or refactor private helpers near the existing
  root built-in helpers:
  - A helper that returns `[compact-command-usage doc]` for each command by
    calling `command-usage-min`.
  - A helper that returns root usage built-in rows for `<app> help [command]`,
    `<app> --help`, and conditionally `<app> --version`.
  - A helper or conditional block for rendering `Global Options:` only when
    `(seq (:opts app))`.

  Remove or stop using the old root `Commands:` built-in helper and root option
  built-in rows in root help.

- [x] **Step 5: Wire `root-help` to the new layout**
  Replace the hard-coded generic `Usage:` lines and the separate `Commands:`
  block with one aligned `Usage:` block:
  - User command usage rows first, in the app spec's command order.
  - Built-in usage rows after user command rows.
  - `Global Options:` after `Usage:` only when user-defined global options
    exist.
  - Existing `footer-lines` remains last.

- [x] **Step 6: Run tests to verify they pass**
  Run: `lgx test`
  Expected: PASS.

- [x] **Step 7: Run full cross-target verification**
  Run: `lgx test-all`
  Expected: PASS across let-go, Clojure, and Babashka.

- [x] **Step 8: Check formatting**
  Run: `lgx fmt-check`
  Expected: clean. If it fails, run `lgx fmt` and then rerun `lgx fmt-check`.

- [x] **Step 9: Commit**
  `git commit -m "Render compact command usage in root help"`

### Task 2: Update README Help Examples

**Files:**
- Modify: `README.md`

- [x] **Step 1: Update the root-help example**
  In the "For the deploy example, root help looks like this" block, replace the
  old generic usage and `Commands:` sections with compact usage rows:

  ```text
  Usage:
    deploy service <SERVICE>  Deploy a service.
    deploy help [command]     Show a command help.
    deploy --help             Show the tool help.
    deploy --version          Print version.

  Global Options:
    -n, --dry-run  Print the deployment plan.
  ```

  Keep the existing footer as the final block after `Global Options:`.

- [x] **Step 2: Verify README example against rendered output**
  Use the deploy app spec in the README and compare the expected root-help block
  to the new `root-help` behavior. At minimum, verify the row labels and section
  order match the implementation.

- [x] **Step 3: Commit**
  `git commit -m "Update README root help example"`

## Verification

Run from the repo root:

```bash
lgx test
lgx test-all
lgx fmt-check
```

Expected final result: all tests pass, formatting is clean, root help has no
`Commands:` section, and command-specific help remains unchanged.

## Summary

Both tasks are complete.

**Task 1 — Rendering (`src/tiny_cli/core.cljc`, `test/tiny_cli/core_test.cljc`):**
- `root-help` now assembles `Usage:` from aligned `[label doc]` rows: one
  compact `command-usage-min` row per user command, followed by built-in rows
  `<app> help [command]`, `<app> --help`, and (only when `:version` is set)
  `<app> --version`.
- The generic `[global options] <command> [args] [options]` grammar line and the
  separate `Commands:` section are gone.
- `Global Options:` lists only user-defined `(:opts app)` and is omitted entirely
  when there are none.
- Removed now-dead helpers: `command-row`, `root-option-built-in-rows`,
  `root-command-built-in-rows`. Parsing, dispatch, validation, and `command-help`
  are untouched (scope guard held — `command-help` still emits the full grammar
  and its `-h, --help` built-in line).
- Tests updated/added: compact command rows, compact variadic row
  (`wtr run <NAME> [CMD...]`), built-in usage rows in `Usage:`, section-scoped
  `Global Options:` assertions (via a new `section` test helper), omission of an
  empty `Global Options:` block, and shared-column alignment across command and
  built-in rows. Final suite: 10 tests, 191 assertions, 0 failures across let-go,
  Clojure, and Babashka; `lgx fmt-check` clean.

**Task 2 — Docs (`README.md`):** replaced the deploy root-help example with the
new compact layout and verified it byte-for-byte against rendered `root-help`
output (only difference was a trailing newline from the extraction).

**Review note:** the codex second-opinion review raised one P2 — root `-h` is no
longer surfaced in root help even though `parse` still accepts it. Per a user
decision, we kept the plan's deliberate compact `<app> --help` row; `-h` still
works and remains documented in command-specific help. No code change made for
this finding.
