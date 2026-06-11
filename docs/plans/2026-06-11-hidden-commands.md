# Hidden Commands Implementation Plan

> **For agentic workers:** Use executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a command set `:hidden? true` to drop it from the root help's Commands list while keeping it runnable and documented by name.

**Tech Stack:** Clojure `.cljc` for let-go, Clojure, and Babashka, tested with `lgx test` and `lgx test-all`.

---

## Design

A command spec may set `:hidden? true`:

```clojure
{:name "migrate-legacy"
 :doc "Migrate data from the old format."
 :hidden? true
 :run migrate-legacy!}
```

A hidden command behaves like any other command everywhere except the root
help's `Commands:` section:

- It does not appear in the rows rendered by `root-command-usage-rows`.
- It still runs: `mycli migrate-legacy` dispatches normally.
- Its help stays reachable by name: `mycli help migrate-legacy` and
  `mycli migrate-legacy --help` print full command help.
- Spec validation still covers it: a hidden command with a missing `:run`
  or a reserved option spelling still produces a spec error.

Implement this with one private helper:

```clojure
(defn- visible-commands
  [app]
  (remove :hidden? (:commands app)))
```

Use it in `root-command-usage-rows` instead of `(:commands app)`. That is
the only call site that enumerates commands for display; `command-by-name`,
`app-spec-error`, and `parse` keep using the full list, which yields the
behavior above without further changes.

The flag needs no spec validation: any truthy value hides the command,
and an absent or falsy value shows it.

## File Structure

- Modify: `src/tiny_cli/core.cljc` - add `visible-commands`, use it in `root-command-usage-rows`.
- Modify: `test/tiny_cli/core_test.cljc` - cover hidden-command behavior.
- Modify: `README.md` - document `:hidden?` in the command spec.

## Implementation Steps

### Task 1: Hide flagged commands from root help

**Files:**
- Modify: `src/tiny_cli/core.cljc`
- Test: `test/tiny_cli/core_test.cljc`

- [ ] **Step 1: Write the focused tests**
  Add a `hidden-commands` deftest with an app that has one visible and one
  hidden command. Assert:
  - Root help (`root-help`) does not mention the hidden command name.
  - Root help still lists the visible command.
  - `run-result` on the hidden command invokes its `:run` handler.
  - `parse` of `["help" "<hidden>"]` returns a `:help` result whose text
    contains the hidden command name.
  - `parse` of `["<hidden>" "--help"]` returns a `:help` result for the
    hidden command.

- [ ] **Step 2: Run the focused test**
  Run: `lgx test`
  Expected: the new `hidden-commands` assertions fail; existing tests pass.

- [ ] **Step 3: Implement the change**
  Add the private `visible-commands` helper near `command-by-name` and use
  it in `root-command-usage-rows` in place of `(:commands app)`.

- [ ] **Step 4: Run verification**
  Run: `lgx test-all`
  Expected: all tests pass on every runtime.

### Task 2: Document `:hidden?` in the README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add the spec note**
  In the command spec documentation, add `:hidden?` with a one-line
  description: the command is omitted from root help but still runs, and
  `help <command>` still shows its help.

- [ ] **Step 2: Verify rendering**
  Run: `lgx test-all`
  Expected: all tests still pass; README reads correctly.
