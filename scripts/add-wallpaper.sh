#!/bin/sh
# Add an image from app/assets/wallpapers and build and install the updated APK.
set -eu
script_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)
cd "$script_dir/.."

fail() { printf '%s\n' "$*" >&2; exit 1; }
usage() { printf '%s\n' 'Usage: ./scripts/add-wallpaper.sh [--build-only] "filename.jpg"'; }

build_only=false
case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  --build-only) build_only=true; shift ;;
esac
[ "$#" -eq 1 ] || { usage >&2; exit 1; }
name=$1
case "$name" in
  ''|.|..|*/*|*\\*|*[[:cntrl:]]*) fail "Pass just the filename from app/assets/wallpapers." ;;
esac

folder=app/assets/wallpapers
registry=$folder/list.tsv
source=$folder/$name
[ -f "$source" ] || fail "Image not found: $source"
[ -f "$registry" ] || fail "Wallpaper list not found: $registry"
command -v sips >/dev/null 2>&1 || fail "This script needs macOS sips to prepare the image."

stem=${name%.*}
key=$(printf '%s' "$stem" | LC_ALL=C tr '[:upper:]' '[:lower:]' |
    LC_ALL=C sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//')
case "$key" in
  ''|custom) fail "Use a filename with letters or numbers other than 'custom'." ;;
esac
label=$(printf '%s\n' "$stem" | tr '_-' '  ' |
    awk '{$1=$1; for (i=1;i<=NF;i++) $i=toupper(substr($i,1,1)) substr($i,2); print}')
target=$folder/$key.jpg

# Prevent two runs from overwriting the generated APK or wallpaper list at the same time.
archive=wallpaper-originals
mkdir -p "$archive"
lock=$archive/.lock
mkdir "$lock" 2>/dev/null || fail "Another wallpaper run is active. If it stopped unexpectedly, remove $lock and try again."
work=
cleanup() {
  [ -z "$work" ] || rm -rf "$work"
  rmdir "$lock"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
work=$(mktemp -d "$archive/.work.XXXXXX")

info=$(sips -g format -g pixelWidth -g pixelHeight "$source" 2>/dev/null) ||
    fail "macOS could not read this image: $name"
kind=$(printf '%s\n' "$info" | awk '$1=="format:" {print $2}')
width=$(printf '%s\n' "$info" | awk '$1=="pixelWidth:" {print $2}')
height=$(printf '%s\n' "$info" | awk '$1=="pixelHeight:" {print $2}')
case "$width:$height" in
  :*|*:|*[!0-9:]*) fail "Could not read the image size: $name" ;;
esac

# Keep the aspect ratio. FireFrame continues to center-crop only when it displays the image.
if [ "$kind" = jpeg ] && [ "$width" -le 1920 ] && [ "$height" -le 1920 ]; then
  cp "$source" "$work/wallpaper.jpg"
else
  sips -s format jpeg -s formatOptions 95 -Z 1920 "$source" --out "$work/wallpaper.jpg" >/dev/null
fi
[ -s "$work/wallpaper.jpg" ] || fail "Could not prepare a JPEG for $name."

if ! cmp -s "$work/wallpaper.jpg" "$target"; then
  if [ -f "$target" ]; then
    previous_hash=$(shasum -a 256 "$target" | awk '{print $1}')
    backup=$archive/$key/$previous_hash
    mkdir -p "$backup"
    cp -p "$target" "$backup/$key.jpg"
    printf 'Previous image saved at %s\n' "$backup/$key.jpg"
  fi
  mv -f "$work/wallpaper.jpg" "$target"
fi
if ! awk -F '\t' -v key="$key" '$1==key {found=1} END {exit !found}' "$registry"; then
  printf '%s\t%s\n' "$key" "$label" >> "$registry"
fi
label=$(awk -F '\t' -v key="$key" '$1==key {print $2; exit}' "$registry")

# The configured key must match the APK already on the Fire TV.
KEYSTORE=${KEYSTORE:-$(git config --local --get fireframe.keystore 2>/dev/null || true)}
if [ -n "$KEYSTORE" ]; then
  [ -f "$KEYSTORE" ] || fail "Configured signing key was not found: $KEYSTORE"
  export KEYSTORE
fi
./scripts/build.sh
apk=out/fireframe.apk
printf 'Ready: %s (%s)\n' "$apk" "$label"
[ "$build_only" = false ] || exit 0

. "$script_dir/adb-common.sh"
setup_adb
tv install -r "$apk"
tv shell am start -W -n com.fireframe.home/.MainActivity --es wallpaper "$key"
tv shell input keyevent KEYCODE_HOME
sleep 6

remote_apk=$(tv shell pm path com.fireframe.home | tr -d '\r' | sed -n 's/^package://p' | head -n 1)
[ -n "$remote_apk" ] || fail "Could not find the installed FireFrame APK."
local_hash=$(shasum -a 256 "$apk" | awk '{print $1}')
remote_hash=$(tv exec-out cat "$remote_apk" | shasum -a 256 | awk '{print $1}')
[ "$local_hash" = "$remote_hash" ] || fail "The installed APK does not match $apk."
if ! tv shell dumpsys window windows | grep 'mCurrentFocus.*com.fireframe.home' >/dev/null; then
  fail "The APK is installed and verified. FireFrame did not gain focus after Home."
fi
shot=$("$script_dir/screenshot.sh" "$device")
printf 'Installed and verified: %s\nTV screenshot: %s\n' "$label" "$shot"
