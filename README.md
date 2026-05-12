# tiny-cli

A zero-dependency CLI lib for let-go/Clojure/Babashka. It tries to be minimal and 
straightforward with good defaults and helpful built-ins.

## API

Require the core namespace:

```clojure
(ns my.tool
  (:require [tiny-cli.core :as cli]))
```

One main function:

- `(cli/run! app argv)` interprets CLI args, prints help/version/errors, calls handlers, and exits for built-ins and parse errors.

`argv` - a vector of string CLI tokens only. Normalize process args at the application edge:

```clojure
; let-go interpreted script
(cli/run! app (vec (drop 2 os/args)))

; let-go bundled binary
(cli/run! app (vec (rest os/args)))

; Clojure / Babashka
(cli/run! app (vec *command-line-args*))
```

Handlers receive:

```clojure
{:global {...}
 :args   {...}
 :opts   {...}}
```

CLI values stay as raw strings. `tiny-cli` applies defaults, checks required options, and runs validation predicates, but it does not coerce types.

## Tests

Run the shared `.cljc` test suite:

```bash
make test
```
