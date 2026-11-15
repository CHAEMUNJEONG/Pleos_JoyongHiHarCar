#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export QUIET_DRIVE_KEY_PASSWORD="${QUIET_DRIVE_KEY_PASSWORD:-quiet-drive-local}"
build_root="${QUIET_DRIVE_BUILD_ROOT:-$HOME/.cache/quiet-drive/build}"
if [ ! -f .signing/quiet-drive.jks ]; then
    mkdir -p .signing
    keytool -genkeypair -keystore .signing/quiet-drive.jks \
        -storepass:env QUIET_DRIVE_KEY_PASSWORD -keypass:env QUIET_DRIVE_KEY_PASSWORD \
        -alias quiet-drive -keyalg RSA -keysize 3072 -validity 3650 \
        -dname 'CN=Quiet Drive Local Development, O=Quiet Drive, C=KR'
fi
./scripts/test-policy.sh
./gradlew -PquietDrive.buildRoot="$build_root" :app:assemblePublicAppRelease :app:lintPublicAppRelease --console=plain
mkdir -p apk
cp "$build_root/app/outputs/apk/publicApp/release/app-publicApp-release.apk" apk/JoYongHiHarCar_release.apk
(cd apk && shasum -a 256 JoYongHiHarCar_release.apk > SHA256SUMS)
printf '%s\n' "Release APK: $project_dir/apk/JoYongHiHarCar_release.apk"
