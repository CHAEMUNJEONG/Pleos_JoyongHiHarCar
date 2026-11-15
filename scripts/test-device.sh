#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
sdk_dir="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
build_root="${QUIET_DRIVE_BUILD_ROOT:-$HOME/.cache/quiet-drive/build}"
adb_bin="$sdk_dir/platform-tools/adb"
serial="${1:-emulator-5554}"
./gradlew -PquietDrive.buildRoot="$build_root" :app:assemblePublicAppDebug :app:assemblePublicAppDebugAndroidTest --console=plain
"$adb_bin" -s "$serial" install -r "$build_root/app/outputs/apk/publicApp/debug/app-publicApp-debug.apk"
"$adb_bin" -s "$serial" install -r "$build_root/app/outputs/apk/androidTest/publicApp/debug/app-publicApp-debug-androidTest.apk"
mkdir -p evidence
"$adb_bin" -s "$serial" shell am instrument -w -r ai.pleos.quietdrive.test/ai.pleos.quietdrive.SmokeInstrumentation > evidence/device-tests.txt
cat evidence/device-tests.txt
# am instrument may exit zero even when an instrumentation test fails.
if ! rg -q '26 checks passed' evidence/device-tests.txt; then
    printf '%s\n' 'Device scenario verification failed.' >&2
    exit 1
fi
