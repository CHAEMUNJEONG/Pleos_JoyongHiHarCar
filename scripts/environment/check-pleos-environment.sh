#!/usr/bin/env bash
set -u

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
studio_jdk="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
system_jdk="/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home"

print_path_status() {
  local label="$1"
  local path="$2"
  if [[ -e "$path" ]]; then
    printf 'OK   %-24s %s\n' "$label" "$path"
  else
    printf 'MISS %-24s %s\n' "$label" "$path"
  fi
}

printf 'Pleos Connect development environment\n'
printf 'Host architecture: %s\n' "$(uname -m)"
printf 'SDK root: %s\n\n' "$sdk_root"

print_path_status "Android Studio" "/Applications/Android Studio.app"
print_path_status "Android SDK" "$sdk_root"
print_path_status "adb" "$sdk_root/platform-tools/adb"
print_path_status "emulator" "$sdk_root/emulator/emulator"
print_path_status "sdkmanager" "$sdk_root/cmdline-tools/latest/bin/sdkmanager"
print_path_status "API 34 android.jar" "$sdk_root/platforms/android-34/android.jar"
print_path_status "API 37 android.jar" "$sdk_root/platforms/android-37.0/android.jar"
pleos_image="$sdk_root/system-images/android-34/android-automotive/arm64-v8a"
print_path_status "Pleos v2.1 image" "$pleos_image"
print_path_status "Java 17" "$system_jdk/bin/java"
print_path_status "Studio JBR" "$studio_jdk/bin/java"

printf '\nInstalled Android packages:\n'
if [[ -x "$sdk_root/cmdline-tools/latest/bin/sdkmanager" ]]; then
  "$sdk_root/cmdline-tools/latest/bin/sdkmanager" --list_installed 2>/dev/null |
    grep -E '^(Path|[[:space:]]*(platforms[/;]android-|build-tools[/;]|platform-tools|emulator|system-images[/;]android-34[/;]android-automotive))' || true
else
  printf 'Unavailable until Android Studio setup completes.\n'
fi

printf '\nConfigured AVDs:\n'
if [[ -x "$sdk_root/emulator/emulator" ]]; then
  "$sdk_root/emulator/emulator" -list-avds || true
else
  printf 'Unavailable until the Android Emulator package is installed.\n'
fi

printf '\nADB devices:\n'
if [[ -x "$sdk_root/platform-tools/adb" ]]; then
  "$sdk_root/platform-tools/adb" devices -l
else
  printf 'Unavailable until Android SDK Platform-Tools is installed.\n'
fi
