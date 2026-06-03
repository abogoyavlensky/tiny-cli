# Align Help Docs to a Shared Column Implementation Plan

**Status:** Not started.

> **For agentic workers:** Use executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pad every list row's label to its section's widest label so the docs in Commands, Global Options, Options, and Args line up at a single column instead of trailing each label with a fixed two-space gap.

**Tech Stack:** Clojure `.cljc` (cross-target: let-go, Clojure, Babashka), tested via `lgx test-all`, formatted via `lgx fmt`.

---

## Design

### Summary

`tiny-cli` renders help text from a plain-data app spec. Each list row is built
by a small formatter — `format-command-row`, `format-option`, `format-arg`
(`src/tiny_cli/core.cljc:52-68`) — that glues `label` and `doc` with a fixed
two-space `"  "` separator and **no padding**, so docs never line up:

```text
Commands:
  list  List worktrees.
  create  Create a new worktree.
  help [command]  Show help.
```

The fix: split each row into a `[label doc]` pair and render the whole section
through one aligner that pads every label to the section's widest label before
the gutter. Width is computed **per section** (Commands, Global Options,
Options, and Args each get their own column):

```text
Commands:
  list            List worktrees.
  create          Create a new worktree.
  help [command]  Show help.
```

The built-in rows (`help [command]`, `-h, --help`, `--version`) join the same
column as the user's commands/options.

### Components

Two new private helpers in `core.cljc`, next to the existing section helpers:

```clojure
(defn- pad-right
  [s width]
  (str s (apply str (repeat (- width (count s)) " "))))

(defn- align-rows
  "Render [label doc] pairs as indented rows with docs aligned to a shared
   column (two-space gutter after the widest label). Rows without a doc emit
   just the indented label, so no trailing whitespace."
  [pairs]
  (let [width (reduce max 0 (map (comp count first) pairs))]
    (map (fn [[label doc]]
           (if (seq doc)
             (str "  " (pad-right label width) "  " doc)
             (str "  " label)))
         pairs)))
```

`pad-right` is cross-target safe — `(apply str (repeat n " "))`, no `format` or
`String` interop — so it works under let-go, Clojure, and Babashka.
`(repeat 0 " ")` yields `()` → `""`, so an already-max-width label gets no
padding. `(reduce max 0 …)` returns `0` for an empty section.

### Data flow

Refactor the three row formatters to return `[label doc]` pairs instead of
finished strings (preserving today's "empty/absent doc ⇒ no gutter" behaviour,
since `(seq "")` and `(seq nil)` are both falsey):

- `command-row` → `[(:name command) (:doc command)]`
- `option-row` → `[(option-label opt) (option-doc opt)]`
- `arg-row` → `[(key-placeholder (:key arg)) (:doc arg)]`

Refactor the built-ins to return pairs too, so they align in their section:

- `root-option-built-in-rows` → `[["-h, --help" "Show help."]]` plus
  `["--version" "Print version."]` when `(:version app)`
- `root-command-built-in-rows` → `[["help [command]" "Show help."]]`

Wire each section by `concat`-ing the user rows and built-in rows, then wrapping
in `align-rows`:

- `root-help` (`src/tiny_cli/core.cljc:470`):
  - `Global Options:` → `(align-rows (concat (map option-row (:opts app)) (root-option-built-in-rows app)))`
  - `Commands:` → `(align-rows (concat (map command-row (:commands app)) (root-command-built-in-rows app)))`
- `command-help` (`src/tiny_cli/core.cljc:488`):
  - `Args:` → `(align-rows (map arg-row (arg-specs command)))`
  - `Options:` → `(align-rows (map option-row (:opts command)))`
  - `Global Options:` → `(align-rows (map option-row (:opts app)))`

### Scope guard

`command-option-built-ins` (`src/tiny_cli/core.cljc:125`) — the
`wtr create -h, --help  Show help for create.` line — is **not** changed. It
lives in the Usage block, not a list, so it must not be column-aligned.
`summary-line`, parsing, and validation are untouched.

### Error handling

None added. This is a pure formatting change over existing data; no new failure
modes. `join-lines` still filters `nil`.

### Testing strategy

Existing `re-find` help tests keep passing (alignment only adds spaces between
label and doc). Add one focused `deftest` over a multi-row app that asserts docs
share a column by comparing the **doc start index** across a short-label row, a
long-label row, and a built-in row — proving alignment, not mere presence.

## File Structure

- `src/tiny_cli/core.cljc` — add `pad-right` and `align-rows`; convert
  `format-command-row`/`format-option`/`format-arg` and the two `root-*-built-ins`
  helpers to return `[label doc]` pairs; route every list section through
  `align-rows` in `root-help` and `command-help`. `command-option-built-ins`
  unchanged.
- `test/tiny_cli/core_test.cljc` — add a `clojure.string` reader-conditional
  require and a `help-doc-alignment` deftest.
- `README.md` — update the root-help example output (Global Options + Commands
  blocks) to the aligned layout.

## Implementation Steps

### Task 1: Align list-section docs in help output

**Files:**
- Modify: `src/tiny_cli/core.cljc`
- Test: `test/tiny_cli/core_test.cljc`

- [ ] **Step 1: Write the failing alignment test**
  In `core_test.cljc`, add a `clojure.string` require via reader conditional
  matching `core.cljc`'s pattern (`#?(:lg [string :as str] :default [clojure.string :as str])`).
  Define a local `align-app` with `:version` and at least two commands whose
  name lengths differ, e.g. `{:name "ls" :doc "List things."}` and
  `{:name "create-branch" :doc "Create a branch."}`. Add a `help-doc-alignment`
  deftest asserting, for `(cli/root-help align-app)`:
  - Split into lines (`str/split-lines`); locate the `ls` row, the
    `create-branch` row, and the `help [command]` built-in row (e.g. via
    `str/starts-with?` on the `"  <label>"` prefix).
  - `(str/index-of <row> <its-doc-text>)` is **equal** across all three rows
    (docs share a column), and that index equals `2 + maxlabel + 2` where
    `maxlabel` is the width of `help [command]` (14) → column 18.
  - A short-label row is actually padded: assert the `ls` row matches
    `#"^  ls {14}List things\.$"` (2-char label padded to 14, plus the 2-space
    gutter ⇒ 14 spaces). This guards the gutter math, not just equality.
  Optionally add a second `testing` block doing the same index-equality check on
  a `Global Options:` section (e.g. a verbose option alongside the `-h, --help`
  and `--version` built-ins).

- [ ] **Step 2: Run the test to verify it fails**
  Run: `lgx test`
  Expected: FAIL — docs are not yet aligned, so the index-equality and padded-row
  assertions fail.

- [ ] **Step 3: Implement `pad-right`, `align-rows`, and the pair refactor**
  Add `pad-right` and `align-rows` as designed. Replace `format-command-row`,
  `format-option`, `format-arg` with `command-row`, `option-row`, `arg-row`
  returning `[label doc]` pairs. Replace `root-option-built-ins` /
  `root-command-built-ins` with `root-option-built-in-rows` /
  `root-command-built-in-rows` returning pairs. Route every list section in
  `root-help` and `command-help` through `align-rows` per the Data flow section.
  Leave `command-option-built-ins`, `summary-line`, parsing, and validation
  untouched.

- [ ] **Step 4: Run the full cross-target suite**
  Run: `lgx test-all`
  Expected: PASS on let-go, Clojure, and Babashka — the new alignment test plus
  all existing `re-find` help tests.

- [ ] **Step 5: Check formatting**
  Run: `lgx fmt-check`
  Expected: clean (run `lgx fmt` to fix if needed).

- [ ] **Step 6: Commit**
  `git commit -m "Align help docs to a shared column"`

### Task 2: Update README help-output examples

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Re-align the root-help example output**
  In the deploy root-help example (`README.md:332-339`), update the
  `Global Options:` and `Commands:` blocks to the aligned layout. Labels and
  their resulting columns:
  - Global Options (widest label `-n, --dry-run` = 13 ⇒ docs at column 17):
    ```text
    Global Options:
      -n, --dry-run  Print the deployment plan.
      -h, --help     Show help.
      --version      Print version.
    ```
  - Commands (widest label `help [command]` = 14 ⇒ docs at column 18):
    ```text
    Commands:
      service         Deploy a service.
      help [command]  Show help.
    ```
  The command-help example (`README.md:354-362`) has single-row sections, so its
  columns are unchanged — leave it as is.

- [ ] **Step 2: Verify the README block matches real output**
  Run: `lgx run` against the deploy example if available, or eyeball that each
  doc in the updated blocks starts at the column noted above.
  Expected: README output matches what `root-help` now renders.

- [ ] **Step 3: Commit**
  `git commit -m "Update README help examples for aligned docs"`

## Verification

Run from the repo root:

```bash
lgx test-all
lgx fmt-check
```

Expected:
- All `.cljc` tests pass on let-go, Clojure, and Babashka, including the new
  `help-doc-alignment` deftest.
- Formatting check is clean.
- In every list section, docs align to one column; rows without a doc carry no
  trailing whitespace; the `command-option-built-ins` usage line is unchanged.

## Notes

- DRY: one `align-rows` aligner drives every list section; one `pad-right`.
- YAGNI: per-section width only (no global column), no configurable gutter, no
  min/max column clamp.
- Backwards compatible: same content, only inter-column spacing changes; loose
  `re-find` consumers are unaffected.
