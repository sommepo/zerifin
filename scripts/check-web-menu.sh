#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
fixture_dir=$(mktemp -d)
trap 'rm -rf -- "$fixture_dir"' EXIT
curl --fail --location --silent --show-error --proto '=https' --proto-redir '=https' \
    https://unpkg.com/linkedom@0.18.12/worker.js -o "$fixture_dir/worker.mjs"
printf '%s  %s\n' 196efeb17c260e001979dbc54a3c30e701a881c6e8a3eedaddc5ad83c99ee5ff \
    "$fixture_dir/worker.mjs" | sha256sum --check --status
node "$repo_root/scripts/tests/zerifin-shell.test.mjs" "$fixture_dir/worker.mjs"
