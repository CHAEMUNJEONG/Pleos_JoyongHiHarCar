#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
sdk_dir="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb_bin="$sdk_dir/platform-tools/adb"
serial="${1:-emulator-5554}"
"$adb_bin" -s "$serial" install -r "$project_dir/apk/QuietDrive_release.apk"
"$adb_bin" -s "$serial" shell am start --user current -n ai.pleos.quietdrive/.MainActivity
