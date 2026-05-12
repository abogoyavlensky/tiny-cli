# Make `run!` Accept Explicit Args

## Goal

Change `tiny-cli.core/run!` to require an explicit argv vector:

```clojure
(cli/run! app argv)
```

`argv` contains CLI tokens only. It must not include the executable name, entry script path, or host-specific wrapper arguments.

## Design

`run!` should stop discovering process args. The library should not inspect `os/args`, `*command-line-args*`, file extensions, bundle mode, or script paths. Callers own that normalization because only the caller knows how the program was invoked.

Examples:

```clojure
;; let-go direct script or bundle, caller decides what to drop
(cli/run! app (vec (rest os/args)))

;; Clojure and Babashka
(cli/run! app (vec *command-line-args*))
```

`parse` and `run-result` keep their current signatures and semantics:

```clojure
(cli/parse app argv)
(cli/run-result app argv)
```

`run!` becomes a thin interpreter of `run-result`:

- `:ok`: return the handler result.
- `:help`: print help text to stdout and exit `0`.
- `:version`: print version text to stdout and exit `0`.
- `:error`: print the message and optional help text to stderr and exit `2`.

Keep host-specific output and exit helpers because `run!` still prints and exits. Remove host-specific argv helpers, including `current-argv` and script path detection.

## Files

- Modify `src/tiny_cli/core.cljc`
  - Remove `script-path?` and `current-argv`.
  - Change `run!` from one arg to two args.
  - Have `run!` call `(run-result app argv)`.
- Modify `test/tiny_cli/core_test.cljc`
  - Add a test that `(cli/run! app)` is no longer valid if practical without process exit.
  - Keep `run-result` tests for non-exiting handler dispatch.
  - Add or adjust tests to document that `run!` consumes normalized CLI args.
- Modify `README.md`
  - Document `(cli/run! app argv)`.
  - Show caller-owned argv normalization examples.
- Modify `docs/initial_design.md`
  - Update the basic example and public API section.
  - Remove language saying `run!` reads process args.

## Verification

Run:

```bash
make test
LGX_LG=/Users/andrew/Projects/let-go/lg lgx run -b bin/tiny-cli src/tiny_cli/core.cljc
```

Expected:

- Let-Go, Clojure, and Babashka tests pass through `make test`.
- Bundle smoke succeeds.
- No library code reads process argv.

## Notes

This is a breaking API change. That is acceptable for the current v1 work because the library has not shipped a stable release yet.
