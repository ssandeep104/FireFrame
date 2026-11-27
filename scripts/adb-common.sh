# Shared ADB setup and APK updates for FireFrame's scripts.
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

install_fireframe() (
  apk=$1
  shift
  package=com.fireframe.home
  [ -s "$apk" ] || fail "APK not found: $apk"
  local_hash=$(shasum -a 256 "$apk" | awk '{print $1}')
  if output=$(tv install -r "$apk" 2>&1); then
    printf '%s\n' "$output"
  else
    printf '%s\n' "$output" >&2
    case "$output" in
      *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
        fail "Signing keys don't match. Set fireframe.keystore to the original signing key and rebuild." ;;
      *) fail "The Fire TV rejected the APK update." ;;
    esac
  fi

  tv shell am start -W -n "$package/.MainActivity" "$@"
  tv shell input keyevent KEYCODE_HOME
  sleep 6

  remote_apk=$(tv shell pm path "$package" | tr -d '\r' | sed -n 's/^package://p' | head -n 1)
  [ -n "$remote_apk" ] || fail "Could not find the installed FireFrame APK."
  remote_hash=$(tv exec-out cat "$remote_apk" | shasum -a 256 | awk '{print $1}')
  [ "$local_hash" = "$remote_hash" ] || fail "The installed APK does not match $apk."
  if ! tv shell dumpsys window windows | grep 'mCurrentFocus.*com\.fireframe\.home' >/dev/null; then
    fail "The APK was installed and verified, but Home did not open FireFrame. Check that its Home button service is enabled."
  fi
)
