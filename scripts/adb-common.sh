# Shared ADB setup for FireFrame's wallpaper and screenshot commands.
setup_adb() {
  adb=$(command -v adb || true)
  if [ -z "$adb" ]; then
    for candidate in "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/platform-tools/adb" \
        /opt/homebrew/share/android-commandlinetools/platform-tools/adb "$HOME/Android/Sdk/platform-tools/adb"; do
      if [ -x "$candidate" ]; then adb=$candidate; break; fi
    done
  fi
  [ -n "$adb" ] || fail "adb was not found. Install Android SDK Platform-Tools."

  device=${1:-${FIREFRAME_DEVICE:-$(git config --local --get fireframe.device 2>/dev/null || true)}}
  if [ -z "$device" ]; then
    device=$("$adb" devices | awk '$2=="device" {id=$1; count++} END {if(count==1) print id}')
    [ -n "$device" ] || fail "Connect one Firestick or set its address: git config --local fireframe.device IP:5555"
  fi
  if [ "$(tv get-state 2>/dev/null || true)" != device ]; then
    case "$device" in
      *:*) "$adb" connect "$device" >/dev/null 2>&1 || true ;;
    esac
  fi
  [ "$(tv get-state 2>/dev/null || true)" = device ] ||
      fail "Firestick $device is unavailable. Check ADB debugging and accept the on-screen prompt if shown."
}

tv() { "$adb" -s "$device" "$@"; }
