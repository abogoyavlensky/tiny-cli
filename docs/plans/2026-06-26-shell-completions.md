# Shell Completions Implementation Plan

> **For agentic workers:** Use executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every tiny-cli app built-in shell completion — a `<app> completion <shell>` command that prints a bash/zsh/fish script and a hidden `<app> __complete` endpoint that returns candidates — with command, flag, and help completion working with zero config, and per-arg/option `:complete` hooks for app-specific dynamic values.

**Tech Stack:** Clojure `.cljc` for let-go, Clojure, and Babashka; tested with `lgx test` (let-go) and `lgx test-all` (all three runtimes). Zero runtime dependencies.

---

## Design

### Overview

Completion lives in a new namespace, `tiny-cli.completion`, with two small touch-points in `tiny-cli.core`. The dependency is one-directional — **core requires completion** — and completion reads the app map as plain data, never calling back into core, so there is no cycle. The completion namespace has no dependencies beyond `clojure.string`, preserving tiny-cli's zero-dependency promise.

```
core/run!   ──①──>  if first arg is "__complete" (and not opted out)
                        → completion/complete!  (print candidates, exit 0)
core/parse  ──②──>  app = (completion/install-command app)   ; inject hidden `completion` command

tiny-cli/completion.cljc:
  candidates        ; PURE: app + words-before-cursor + current-word → filtered candidate strings
  script            ; PURE: app + shell → completion script string (bash | zsh | fish), or nil
  install-command   ; PURE: append the hidden `completion` command unless opted out / user-defined
  complete!         ; I/O edge for __complete: build words+cur, call candidates, println each, swallow all errors
```

`candidates`, `script`, and `install-command` are pure and run in the shared `.cljc` test suite. `complete!` is the only I/O and is covered by a manual smoke check, mirroring how wtr validated its endpoint.

### Public API additions

**1. `:complete` on any arg or option spec.** A vector of strings, or a 1-arg function returning a seq of strings. The engine calls it when the cursor sits on that slot and prefix-filters the result by the current word, so completers return *unfiltered* candidates. The function receives a context map:

```clojure
{:words       ["remove"]      ; words typed before the cursor (app name excluded)
 :cur         "fea"           ; the word under the cursor (may be "")
 :command     {...}           ; the selected command spec, or nil
 :positionals []}             ; positional values already completed before the cursor
```

`:global` is intentionally omitted (YAGNI); the map is open and may gain keys later without breaking callers. Example (the wtr case this generalizes):

```clojure
{:name "remove"
 :args [{:key :name
         :doc "Worktree name to remove."
         :complete (fn [_ctx] (into ["main" "master"] (wtr/worktree-names)))}]
 :run cmds/remove}
```

A command whose positional is a *new* name (e.g. `create`) simply omits `:complete`.

**2. A generated `completion <shell>` command**, injected into `:commands` by `parse` (via `install-command`). It is `:hidden? true`, so it does not change any existing app's root-help output, but `<app> help completion`, `<app> completion --help`, and TAB-suggestion still work. Its `shell` arg carries both:
- `:validate` — rejects unknown shells through tiny-cli's existing validation (a parse error, exit 2), so the `:run` handler only ever sees a valid shell.
- `:complete ["bash" "zsh" "fish"]` — so `<app> completion <TAB>` offers the shells, dogfooding the same mechanism.

Its `:run` prints the script (`(print (script app shell))`). The command closes over `app` for its name.

**3. A hidden `__complete` endpoint**, intercepted at the top of `run!` before `parse`. Its argv holds partial and option-like tokens that `parse` would reject, so it cannot be a normal command. It prints prefix-filtered candidates one per line and always exits 0.

**4. An app-level `:completion?` opt-out.** `{:completion? false}` skips both the injected command and the `__complete` intercept. Defining your own `completion` command also overrides the built-in (`install-command` defers when a `completion` command already exists). Default (absent or true) is on.

**5. Light spec validation.** A present `:complete` that is neither a fn nor a seq-of-strings is a spec error ("Invalid :complete spec."), matching how the library already validates `:validate`.

### Candidate engine

`candidates` is a direct generalization of wtr's proven `candidates`/`split-context`, replacing wtr's hardcoded `worktree-arg-commands`/`shells` sets with `:complete` lookups on the specs. It is driven entirely by the app map.

`split-context` walks the words typed before the cursor and returns `{:command :positionals :awaiting-option}`:
- A value-taking option (global or command `--long`/`-s`, matched exactly, i.e. not the `--opt=value` inline form) consumes the *next* word. When that option is the **last** word before the cursor, the cursor is on its value: `:awaiting-option` is set to that option spec.
- Any other word starting with `-` is a flag and is skipped (consumes no slot).
- The first non-option word selects `:command` — a command spec, the keyword `:help` for the `help` built-in, or `:unknown` for an unrecognized token.
- Every later non-option word is appended to `:positionals`.

`candidates` then resolves the cursor position and prefix-filters the result by `cur`:

| Cursor position | Candidates |
|---|---|
| On a value-taking option's value (`:awaiting-option`) | that option's `:complete`, else none |
| `:command` is `:unknown` | none |
| `cur` starts with `-`, **no positional yet**, command selected | command long flags + global long flags + `--help` |
| `cur` starts with `-`, **no positional yet**, no command | global long flags + `--help` + `--version` |
| No command yet (word) | command-name candidates (below) |
| `:command` is `:help` | command-name candidates, minus `help` |
| Command selected, cursor on positional N | the governing spec's `:complete`: the Nth `:args` spec, else `:variadic` once fixed args are filled, else none |

**Command-name candidates** = visible command names (`:hidden?` excluded) ++ `"completion"` *if a `completion` command exists in the app* ++ `"help"`, de-duplicated. So hidden user commands stay hidden, but the built-in `completion` (injected, hidden) is still offered, matching the chosen "hidden from help, present in TAB" behavior.

**Only long flags** are offered (short flags are noise to complete), matching wtr.

Once a positional has been typed, tiny-cli rejects options, so a `-` there falls through to the positional branch (usually none → the shell falls back to file completion). After the variadic begins (e.g. `run feat-x …`), the governing spec is the variadic, whose `:complete` is normally absent → no candidates → file completion. An empty result always means "nothing to offer," and the generated scripts fall back to the shell's default completion.

`candidates` invokes `:complete` functions directly (like core invokes `:validate` `:pred`s). With vector or pure-fn completers it is deterministic and unit-testable; in production wtr's git-reading completer runs inside the already-I/O, try/catch-wrapped `__complete` flow.

### Script generation

`script` fills one template per shell with two values derived from the app:
- **`NAME`** = `(:name app)` — used to register completion and as the literal command name.
- **`ID`** = `(str/replace NAME #"[^A-Za-z0-9_]" "_")` — a safe shell identifier for function names, so an app named `my-tool` yields `_my_tool_complete` registered against `my-tool`.

The three templates are direct generalizations of wtr's working scripts. Each invokes the binary as it was called (`"${COMP_WORDS[0]}"` / `$words[1]`), so they are install-location- and symlink-agnostic. No resource files are needed — strictly less wiring than wtr's `resources/completions/*` plus `:resource-paths`.

**bash** (`NAME`/`ID` substituted):
```bash
# bash completion for NAME.
# Load with: source <(NAME completion bash)
_ID_complete() {
    local cur candidates
    cur="${COMP_WORDS[COMP_CWORD]}"
    candidates="$("${COMP_WORDS[0]}" __complete "${COMP_WORDS[@]:1:COMP_CWORD}" 2>/dev/null)"
    local IFS=$'\n'
    COMPREPLY=($(compgen -W "$candidates" -- "$cur"))
}
# -o default: fall back to filename completion when NAME offers nothing.
complete -o default -F _ID_complete NAME
```

**zsh**:
```zsh
#compdef NAME
# zsh completion for NAME.
# Load with: source <(NAME completion zsh)
# or save on your fpath: NAME completion zsh > ~/.zfunc/_NAME
_ID() {
    local -a candidates
    candidates=("${(@f)$("${words[1]}" __complete "${(@)words[2,CURRENT]}" 2>/dev/null)}")
    if (( ${#candidates[@]} )) && [[ -n "${candidates[1]}" ]]; then
        compadd -Q -a candidates
    else
        _default
    fi
}
# On fpath, #compdef invokes _ID for us; when sourced, register it manually.
if [[ "${funcstack[1]}" == "_ID" ]]; then
    _ID "$@"
else
    compdef _ID NAME
fi
```

**fish**:
```fish
# fish completion for NAME.
# Load with: NAME completion fish | source
# or save it: NAME completion fish > ~/.config/fish/completions/NAME.fish
function __ID_complete
    set -l words (commandline -opc)
    set -l cur (commandline -ct)
    set -g __ID_candidates ($words[1] __complete $words[2..-1] "$cur" 2>/dev/null)
    test (count $__ID_candidates) -gt 0
end
complete -c NAME -f -n '__ID_complete' -a '$__ID_candidates'
complete -c NAME -n 'not __ID_complete' -F
```

`script` returns `nil` for an unknown shell. (In practice the `completion` command's `:validate` blocks unknown shells before `:run` is reached; `nil` is the defensive default for direct callers.)

### Error handling and safety

`complete!` wraps everything in `try`/`catch` → on any error it prints nothing and the process exits 0. A broken completer can never break a user's shell. The `run!` intercept calls `complete!` then `exit!` 0.

### Compatibility and testing

Everything is `.cljc`. The pure parts (`candidates`, `script`, `install-command`, and the new `:complete` spec validation in core) run on let-go, Clojure, and Babashka via `lgx test-all`. The new `test/tiny_cli/completion_test.cljc` self-runs on clj/bb through the same `#?(:lg (do) :default (run-tests…))` tail used by `core_test.cljc`, and must be added to the `test-all` task's Clojure and Babashka invocations (which require test namespaces explicitly). `complete!` and the generated scripts are validated by a manual smoke check against a built binary.

### What wtr becomes afterward (follow-up, out of scope)

Once tiny-cli is released and wtr bumps the dep, wtr deletes `resources/completions/*` (3 files), the `__complete` interception in `main.lg`, the static-script loader, and most of `src/wtr/completion.lg` — keeping only `(fn [_ctx] (into ["main" "master"] (worktree-names)))` wired as `:complete` on the `run`/`switch`/`remove` name args, and dropping its own `completion` command. This migration is **not** part of this plan.

## File Structure

- **Create:** `src/tiny_cli/completion.cljc` — the whole feature: `candidates` (+ private `split-context`, positional/flag/command-name helpers, `resolve-complete`), `script` (+ private per-shell templates and `sanitize`), `install-command` (+ the hidden completion command spec), and the I/O `complete!`. One cohesive, independently testable unit.
- **Modify:** `src/tiny_cli/core.cljc` — require `tiny-cli.completion`; call `completion/install-command` at the top of `parse` (after spec validation); intercept `__complete` at the top of `run!`; add `:complete` validation to `command-spec-error` and `app-spec-error`.
- **Create:** `test/tiny_cli/completion_test.cljc` — unit tests for `candidates`, `script`, and `install-command`.
- **Modify:** `test/tiny_cli/core_test.cljc` — completion command dispatch, opt-out, and `:complete` spec-error cases.
- **Modify:** `lgx.edn` — add `tiny-cli.completion-test` to the `test-all` task's Clojure and Babashka requires.
- **Modify:** `README.md` — document `:complete`, the built-in `completion` command, the `:completion?` field, and per-shell install instructions.

## Implementation Steps

### Task 1: Candidate engine

**Files:**
- Create: `src/tiny_cli/completion.cljc`
- Create: `test/tiny_cli/completion_test.cljc`
- Modify: `lgx.edn`

- [x] **Step 1: Write failing tests for `candidates`**
  Create `completion_test.cljc` with the `ns`/require shape and the
  `#?(:lg (do) :default (let [result (run-tests)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))`
  tail copied from `core_test.cljc`. Use a sample app with: a global value option `--base-dir`; `list` (no args); `create` with a `:name` arg (no `:complete`) and a `--from`/`-f` value option carrying `:complete ["main" "dev"]`; `remove` with a `:name` arg carrying `:complete (fn [_] ["feat-x" "feature/bar"])` and a boolean `--force`; and `run` with a `:name` arg and a `:variadic :cmd`. Cover:
  - `[] ""` → `["list" "create" "remove" "run" "help"]` (no `completion` command in this raw app).
  - prefix: `[] "cr"` → `["create"]`.
  - root flags: `[] "-"` → `["--base-dir" "--help" "--version"]`.
  - command flags: `["remove"] "-"` → `["--base-dir" "--force" "--help"]`.
  - positional fn `:complete`: `["remove"] ""` → `["feat-x" "feature/bar"]`; `["remove"] "fea"` → both; `["remove"] "feature"` → `["feature/bar"]`.
  - a flag does not consume the positional slot: `["remove" "--force"] ""` → `["feat-x" "feature/bar"]`.
  - option-value `:complete`: `["create" "--from"] ""` → `["main" "dev"]`; `["create" "--from"] "m"` → `["main"]`.
  - short value option resolves the same slot: `["create" "-f"] "m"` → `["main"]`.
  - no `:complete` → none: `["create"] ""` → `[]`.
  - after a positional: `["remove" "feat-x"] ""` → `[]`; `["remove" "feat-x"] "-"` → `[]`.
  - variadic tail falls back: `["run" "feat-x"] ""` → `[]`.
  - `help` arg: `["help"] ""` → `["list" "create" "remove" "run"]` (command names, no `help`); `["help"] "ru"` → `["run"]`.
  - unknown command: `["bogus"] ""` → `[]`.

- [x] **Step 2: Wire `completion-test` into `test-all` and run it**
  In `lgx.edn`, change the Clojure step to
  `clojure -M -e "(require 'tiny-cli.core-test) (require 'tiny-cli.completion-test)"`
  and the Babashka step to
  `bb -cp src:test -e "(require 'tiny-cli.core-test) (require 'tiny-cli.completion-test)"`.
  Run: `lgx test`
  Expected: FAIL — `tiny-cli.completion` namespace does not exist yet.

- [x] **Step 3: Implement `candidates`**
  Create `src/tiny_cli/completion.cljc` with the `ns` requiring only
  `#?(:lg [string :as str] :default [clojure.string :as str])`. Implement private
  `find-command`, `long-flags`, `value-option?` (matches a token exactly against
  global∪command value-taking `--long`/`-s` spellings), `split-context`,
  `positional-spec` (Nth fixed arg, else variadic, else nil), `command-name-candidates`
  (visible names ++ `"completion"` when present ++ `"help"`, de-duped), and
  `resolve-complete` (nil → `[]`; fn → call with ctx, nil-guarded; seq → as is).
  Then public `candidates` following the table in the Design. Build the ctx map
  `{:words :cur :command :positionals}` for `:complete` calls. Filter the final
  result with `(filterv #(str/starts-with? % cur) …)`.

- [x] **Step 4: Run verification**
  Run: `lgx test`
  Expected: PASS. Then `lgx test-all` — PASS on let-go, Clojure, and Babashka.

- [x] **Step 5: Commit**
  `git commit -m "feat: add completion candidate engine"`

### Task 2: Shell-script generation

**Files:**
- Modify: `src/tiny_cli/completion.cljc`
- Modify: `test/tiny_cli/completion_test.cljc`

- [ ] **Step 1: Write failing tests for `script`**
  For each shell in `["bash" "zsh" "fish"]`, assert `(script {:name "wtr"} shell)`
  is non-blank and contains `"__complete"`. Assert name sanitization: a `{:name "my-tool"}`
  bash script contains `_my_tool_complete` (function id) and registers against the
  literal `my-tool` (e.g. contains `-F _my_tool_complete my-tool`). Assert
  `(script {:name "wtr"} "powershell")` is `nil`.

- [ ] **Step 2: Run the focused test**
  Run: `lgx test`
  Expected: FAIL — `script` is unbound.

- [ ] **Step 3: Implement `script`**
  Add private `sanitize` (`str/replace name #"[^A-Za-z0-9_]" "_"`), one private
  template fn per shell taking `[name id]` and returning the script string from the
  Design (substitute `NAME`/`ID`), and public `script` dispatching on the shell
  string with a `nil` default.

- [ ] **Step 4: Run verification**
  Run: `lgx test` then `lgx test-all`
  Expected: PASS on all runtimes.

- [ ] **Step 5: Commit**
  `git commit -m "feat: generate bash/zsh/fish completion scripts"`

### Task 3: Command injection and the I/O endpoint

**Files:**
- Modify: `src/tiny_cli/completion.cljc`
- Modify: `test/tiny_cli/completion_test.cljc`

- [ ] **Step 1: Write failing tests for `install-command`**
  Assert that `install-command` on an app without a `completion` command appends one
  that is `:hidden?`, named `"completion"`, has a `shell` arg with `:complete` equal to
  `["bash" "zsh" "fish"]` and a `:validate` map, and a callable `:run`. Assert it is a
  no-op when the app already has a `completion` command (count unchanged, the user's
  command preserved) and when `(:completion? app)` is `false`. Assert that
  `candidates` on the *installed* app offers `"completion"` (e.g. `["completion"] ""`
  → `["bash" "zsh" "fish"]`, and `[] ""` includes `"completion"`).

- [ ] **Step 2: Run the focused test**
  Run: `lgx test`
  Expected: FAIL — `install-command` is unbound.

- [ ] **Step 3: Implement `install-command` and `complete!`**
  Add private `completion-command` building the hidden spec (closing over `app` for
  `(:name app)`; `:run` = `(fn [ctx] (print (script app (:shell (:args ctx)))))`; the
  `shell` arg's `:validate` `:pred` checks membership in `#{"bash" "zsh" "fish"}` with
  `:msg "Shell must be one of: bash, zsh, fish."`). Add public `install-command`:
  return `app` unchanged when `(false? (:completion? app))` or a `completion` command
  already exists; otherwise append the generated command. Add public `complete!`:
  `(try (let [app (install-command app) words (vec (butlast argv)) cur (or (last argv) "")] (doseq [c (candidates app words cur)] (println c))) (catch Exception _ nil))`.

- [ ] **Step 4: Run verification**
  Run: `lgx test` then `lgx test-all`
  Expected: PASS on all runtimes.

- [ ] **Step 5: Commit**
  `git commit -m "feat: inject completion command and __complete endpoint"`

### Task 4: Core integration

**Files:**
- Modify: `src/tiny_cli/core.cljc`
- Modify: `test/tiny_cli/core_test.cljc`

- [ ] **Step 1: Write failing tests in `core_test.cljc`**
  Add a `completion-integration` deftest:
  - `(run-result app ["completion" "bash"])` returns `:ok` and printing it (capture via
    `with-out-str` under `:default`; under `:lg` assert `(= :ok (:status …))`) — keep
    the cross-runtime assertion as `(= :ok (:status (parse app ["completion" "bash"])))`
    and `(= "completion" (:name (:command (parse app ["completion" "bash"]))))`.
  - unknown shell: `(parse app ["completion" "powershell"])` → `:error` with a message
    matching `#"Shell must be one of"`.
  - `help` reaches it: `(parse app ["help" "completion"])` → `:help`, text contains
    `"completion"`.
  - hidden in root help: `(re-find #"\bcompletion\b" (root-help app))` is still `nil`
    (the command is `:hidden?` and `root-help` takes the un-injected app).
  - opt-out: `(parse (assoc app :completion? false) ["completion" "bash"])` → `:error`
    matching `#"Unknown command"`.
  - `:complete` spec validation (command-scoped — there is no app-level `:args`): an
    app with `:commands [{:name "go" :args [{:key :x :complete 42}] :run …}]` →
    `:error` matching `#"Invalid :complete spec"`; the same command with a valid
    `:complete ["a"]` or `:complete (fn [_] [])` parses without that error.

- [ ] **Step 2: Run the focused test**
  Run: `lgx test`
  Expected: FAIL — completion not wired into core.

- [ ] **Step 3: Implement core integration**
  - Add `[tiny-cli.completion :as completion]` to the `ns` require.
  - Add private `bad-complete?` (`(not (or (fn? x) (and (sequential? x) (every? string? x))))`)
    and `first-invalid-complete` over specs; add a `(first-invalid-complete …)` →
    `"Invalid :complete spec."` branch to `command-spec-error` (over
    `(concat (arg-specs command) (:opts command))`) and `app-spec-error` (over `(:opts app)`).
  - In `parse`, after the `app-spec-error` guard passes, rebind
    `app` to `(completion/install-command app)` before indexing options and looping.
  - In `run!`, before `run-result`, intercept:
    `(if (and (not (false? (:completion? app))) (= "__complete" (first argv))) (do (completion/complete! app (rest argv)) (exit! 0)) <existing body>)`.

- [ ] **Step 4: Run verification**
  Run: `lgx test` then `lgx test-all`
  Expected: PASS on all runtimes. If any pre-existing test asserts the full command
  list or that `completion`/`__complete` is unknown, update it — the injected command
  is hidden, so root-help and command-help snapshots should be unaffected.

- [ ] **Step 5: Manual smoke check**
  Build and exercise the endpoint end to end. tiny-cli is a library, so first create a
  scratch `.lg` app (reuse the README "Minimal let-go cli app" deploy sample, adding a
  `:complete` vector to one arg) and build it with `lgx build`, then:
  `source <(<app> completion bash)`; check `<app> __complete ""` lists
  commands; `<app> __complete <prefix>` filters; `<app> completion bash` prints a
  script mentioning `__complete`; `<app> completion powershell` errors with exit 2;
  TAB after the app name suggests commands.

- [ ] **Step 6: Commit**
  `git commit -m "feat: wire built-in completion into core run!/parse"`

### Task 5: Documentation

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Document the API**
  - Add `:complete` rows to the Arg fields and Option fields tables: "Completion
    candidates for this slot — a vector of strings or a `(fn [ctx])` returning
    strings." Note the ctx keys and that results are prefix-filtered.
  - Add a `:completion?` row to the App fields table: "Set `false` to disable the
    built-in `completion` command and `__complete` endpoint. Default on."
  - Add a "Shell completions" section under Built-In Commands: explain the hidden
    `<app> completion <shell>` command and the `__complete` endpoint; show a `:complete`
    example; give per-shell install snippets generalized from wtr (bash
    `source <(<app> completion bash)` in `~/.bashrc`; zsh `source <(<app> completion zsh)`
    or save on `fpath` as `~/.zfunc/_<app>` with the `fpath` note — for an app name
    containing non-identifier characters, prefer the `source` form, since the autoloaded
    file name must match the sanitized function id; fish
    `<app> completion fish > ~/.config/fish/completions/<app>.fish`).
  - Use the /writing-clearly skill for the prose.

- [ ] **Step 2: Verify**
  Run: `lgx test-all`
  Expected: PASS; README reads correctly.

- [ ] **Step 3: Commit**
  `git commit -m "docs: document built-in shell completions"`

## Implementation Summary

_(Filled in after implementation.)_
