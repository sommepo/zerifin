#!/bin/sh
set -eu
resolver_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$resolver_root/start.py" "$@"
