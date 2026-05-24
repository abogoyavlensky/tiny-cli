#!/usr/bin/env bash

set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
cd "$ROOT"

echo "==> let-go"
lgx test

if command -v clojure >/dev/null && clojure -Sdescribe >/dev/null 2>&1; then
  echo
  echo "==> clojure"
  clojure -M -e "(require 'tiny-cli.core-test)"
else
  echo
  echo "==> clojure skipped"
fi

if command -v bb >/dev/null && bb --version >/dev/null 2>&1; then
  echo
  echo "==> babashka"
  bb -cp src:test -e "(require 'tiny-cli.core-test)"
else
  echo
  echo "==> babashka skipped"
fi
