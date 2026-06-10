# Root Help Usage and Commands Implementation Plan

> **For agentic workers:** Use executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split root help into a tiny `Usage:` grammar followed by a `Commands:` section that keeps the existing compact command rows.

**Tech Stack:** Clojure `.cljc` for let-go, Clojure, and Babashka, tested with `lgx test` and `lgx test-all`.

---

## Design

Root help currently uses `Usage:` as a compact command cheat sheet:

```text
Usage:
  wtr create <BRANCH>  Create a worktree for a branch.
  wtr help [command]   Show a command help.
  wtr --help           Show the tool help.
  wtr --version        Print version.
```

The approved design keeps those compact rows but labels them as `Commands:`.
It adds a small `Usage:` section before the command list:

```text
Usage:
  wtr [global options] <command> [options] [args]

Commands:
  wtr create <BRANCH>  Create a worktree for a branch.
  wtr help [command]   Show a command help.
  wtr --help           Show the tool help.
  wtr --version        Print version.
```

Use the app's `:name` in the usage grammar, so the generic shape is:

```text
<app> [global options] <command> [options] [args]
```

Render that grammar as a single indented line. Keep the `Global Options:`
section conditional on user-defined global options, as it is today. The grammar
line still includes `[global options]` because it is a concise invocation shape,
not a generated list of available options.

Keep root command rows exactly as they are today:

- User commands use `command-usage-min`, so they include the app name, command
  name, positional placeholders, and variadic placeholders.
- Built-ins stay in the same list: `<app> help [command]`, `<app> --help`, and
  `<app> --version` when `:version` exists.
- `align-rows` still aligns docs across user command rows and built-in rows.

This plan does not change parsing, validation, command dispatch, command help,
or the app spec. It also does not rename private helpers such as
`root-command-usage-rows` or `root-usage-built-in-rows`; preserving them keeps
the code change small.

No new error handling is needed. The change only affects pure help rendering
over already-validated app data.

Testing should focus on `root-help`:

- Root help includes the new one-line `Usage:` grammar.
- Root help has a `Commands:` section containing the existing compact command
  rows.
- Command rows and built-ins remain aligned.
- Built-in help/version rows appear in `Commands:`, not `Global Options:`.
- `Global Options:` remains conditional on user-defined global options.
- Existing command-help tests remain unchanged.

## File Structure

- `src/tiny_cli/core.cljc` - update `root-help` layout only.
- `test/tiny_cli/core_test.cljc` - update root-help expectations and alignment
  test wording.
- `README.md` - update the root-help example to show the new `Usage:` and
  `Commands:` sections.

## Implementation Steps

### Task 1: Split Root Help Usage And Commands

**Files:**
- Modify: `src/tiny_cli/core.cljc`
- Test: `test/tiny_cli/core_test.cljc`

- [x] **Step 1: Update the focused root-help tests**
  In `test/tiny_cli/core_test.cljc`, change the root help expectations so
  `(cli/root-help app)` must contain:

  - `Usage:` followed by `wtr [global options] <command> [options] [args]`.
  - `Commands:` followed by the existing compact rows such as
    `wtr create <BRANCH>  Create a worktree for a branch.`.
  - Built-in command rows `wtr help [command]`, `wtr --help`, and
    `wtr --version`.

  Remove or invert the existing assertion that root help omits `Commands:`.
  Keep the `Global Options:` assertions scoped to the `Global Options:` block.

- [x] **Step 2: Update the alignment test labels**
  In `help-doc-alignment`, rename the test description from usage alignment to
  command alignment. Keep the row-level assertions for `wtr ls`,
  `wtr create-branch`, `wtr help [command]`, `wtr --help`, and
  `wtr --version`.

- [x] **Step 3: Run tests to verify they fail**
  Run: `lgx test`
  Expected: FAIL because `root-help` still renders compact command rows under
  `Usage:` and does not render a `Commands:` heading.

- [x] **Step 4: Implement the root-help layout**
  In `src/tiny_cli/core.cljc`, update `root-help` so it renders:

  - Summary line.
  - Blank line.
  - `Usage:`.
  - `  <app> [global options] <command> [options] [args]`.
  - Blank line.
  - `Commands:`.
  - Existing aligned rows from `(concat (root-command-usage-rows app)
    (root-usage-built-in-rows app))`.
  - Existing conditional `Global Options:` section.
  - Existing footer lines.

  Keep `command-help` unchanged.

- [x] **Step 5: Run focused verification**
  Run: `lgx test`
  Expected: PASS.

- [x] **Step 6: Run full verification**
  Run: `lgx test-all`
  Expected: PASS across let-go, Clojure, and Babashka.

- [x] **Step 7: Check formatting**
  Run: `lgx fmt-check`
  Expected: PASS. If it fails, run `lgx fmt`, then rerun `lgx fmt-check`.

- [x] **Step 8: Commit**
  Run: `git commit -am "Split root help usage and commands"`

### Task 2: Update README Root Help Example

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the root-help example**
  In the "For the deploy example, root help looks like this" block, change the
  output to:

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

- [ ] **Step 2: Compare docs against rendered output**
  After Task 1, use the deploy app spec in the README as the reference and check
  that the section order, labels, and spacing match `root-help`.

- [ ] **Step 3: Commit**
  Run: `git commit -am "Update README root help example"`

## Final Verification

- [ ] Run `lgx test-all`.
- [ ] Run `lgx fmt-check`.
- [ ] Confirm root help uses `Usage:` for the generic grammar and `Commands:`
  for compact command rows.
- [ ] Confirm command help remains unchanged.
