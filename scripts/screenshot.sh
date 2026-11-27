#!/bin/sh
# Capture whatever the Fire TV is showing right now.
set -eu
script_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)
cd "$script_dir/.."

fail() { printf '%s\n' "$*" >&2; exit 1; }
usage() { printf '%s\n' 'Usage: ./scripts/screenshot.sh [device:port]'; }

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
esac
[ "$#" -le 1 ] || { usage >&2; exit 1; }

. "$script_dir/adb-common.sh"
setup_adb "${1:-}"

umask 077
folder=$PWD/screenshots
mkdir -p "$folder"
stamp=$(date '+%d-%m-%Y_%H-%M-%S')
capture=$(mktemp "$folder/.capture.XXXXXX")
trap 'rm -f "$capture"' EXIT
trap 'exit 1' HUP INT TERM

tv exec-out screencap -p > "$capture" || fail "Could not capture the screen from $device."
signature=$(od -An -v -tx1 -N 8 "$capture" | tr -d '[:space:]')
[ "$signature" = 89504e470d0a1a0a ] || fail "The Firestick did not return a PNG screenshot."

# Allocate a new filename even when another capture starts in the same second.
stem=$folder/fireframe-$stamp
target=$stem.png
suffix=1
until ln "$capture" "$target" 2>/dev/null; do
  [ -e "$target" ] || [ -L "$target" ] || fail "Could not save the screenshot in $folder."
  suffix=$((suffix + 1))
  target=$stem-$suffix.png
done
printf '%s\n' "$target"
