#!/bin/sh
set -eu
resolver_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ ! -d "$resolver_root/.runtime/vendor/yt_dlp" ]; then
    echo 'First run: python3 companion/youtube/install.py' >&2
    exit 1
fi
command -v node >/dev/null || { echo 'Node 22 or newer is required.' >&2; exit 1; }
PYTHONPATH="$resolver_root/.runtime/vendor" exec python3 "$resolver_root/resolver.py" "$@"
