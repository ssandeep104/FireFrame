#!/bin/sh
# Build and update FireFrame on a TV that already opens FireFrame from Home.
set -eu
script_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)
cd "$script_dir/.."

fail() { printf '%s\n' "$*" >&2; exit 1; }
usage() { printf '%s\n' 'Usage: ./scripts/build-and-install.sh [device:port]'; }

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
esac
[ "$#" -le 1 ] || { usage >&2; exit 1; }

. "$script_dir/adb-common.sh"
setup_adb "${1:-}"
"$script_dir/build.sh"
apk=out/fireframe.apk
install_fireframe "$apk"
printf 'Installed and verified: %s\n' "$apk"
