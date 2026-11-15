#!/usr/bin/env bash
set -euo pipefail

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
emulator="$sdk_root/emulator/emulator"
adb="$sdk_root/platform-tools/adb"
avd_root="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
gpu_mode="${PLEOS_EMULATOR_GPU_MODE:-host}"
vulkan_mode="${PLEOS_EMULATOR_VULKAN_MODE:-off}"

if [[ ! -x "$emulator" || ! -x "$adb" ]]; then
  printf 'Android Emulator or adb is missing under %s. Complete Android Studio setup first.\n' "$sdk_root" >&2
  exit 1
fi

requested_avd="${1:-}"
if [[ -z "$requested_avd" ]]; then
  while IFS= read -r ini; do
    if grep -Eqi 'pleos|pleos_car_emulator' "$ini"; then
      requested_avd="$(basename "${ini%.ini}")"
      break
    fi
  done < <(find "$avd_root" -maxdepth 1 -type f -name '*.ini' 2>/dev/null | sort)
fi

if [[ -z "$requested_avd" ]]; then
  printf 'No Pleos AVD was found. Create one from the API 34 Pleos Connect v2.1.0 ARM64 image.\n' >&2
  exit 1
fi

if "$adb" devices | awk 'NR > 1 && $2 == "device" { found=1 } END { exit !found }'; then
  printf 'An Android device is already connected; leaving it untouched.\n'
else
  printf 'Starting %s with a cold boot...\n' "$requested_avd"
  emulator_args=(-avd "$requested_avd" -no-snapshot-load -gpu "$gpu_mode")
  if [[ "$vulkan_mode" == "off" ]]; then
    emulator_args+=(-feature -Vulkan)
  fi
  if [[ "$(uname -s)" == "Darwin" ]] && command -v launchctl >/dev/null; then
    launch_label="ai.pleos.emulator.$(printf '%s' "$requested_avd" | tr -c '[:alnum:].-' '-')"
    launchctl remove "$launch_label" 2>/dev/null || true
    launchctl submit -l "$launch_label" \
      -o /tmp/pleos-emulator.log -e /tmp/pleos-emulator.log \
      -- "$emulator" "${emulator_args[@]}"
  else
    nohup "$emulator" "${emulator_args[@]}" \
      </dev/null >/tmp/pleos-emulator.log 2>&1 &
  fi
fi

"$adb" wait-for-device
until [[ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  sleep 2
done

"$adb" forward tcp:8088 tcp:8088
printf 'Pleos emulator is ready. Virtual Console: http://127.0.0.1:8088/\n'
"$adb" devices -l
