# Pleos Connect emulator setup

Verified on 2026-11-15 (Asia/Seoul) on an Apple Silicon Mac.

## Installed environment

| Component | Installed value |
| --- | --- |
| Android SDK root | `/Users/jeongchaemun/Library/Android/sdk` |
| Android Studio | 2026.2, build `AI-262.10968.63.2622.16840690` |
| Platform Tools | 37.0.1 |
| Emulator | 37.2.13.0, ARM64 host binary |
| Build platform | Android API 34 revision 3 |
| Build Tools | 34.0.0 and 36.0.0 |
| Build JDK | `/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home` (17.0.15) |
| Android Studio JBR | `/Applications/Android Studio.app/Contents/jbr/Contents/Home` (25.0.4) |

API 34 and Build Tools 34 are the reproducible app-build target. API 37.0 is also installed by Android Studio, but Pleos Connect v2.1.0 does not require it.

## Pleos image and AVD

Official Apple Silicon repository:

`https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`

| Field | Verified value |
| --- | --- |
| Release | Pleos Connect v2.1.0 |
| Android | 14 / API 34 |
| Repository package | `system-images;android-34;pleos_car_emulator;arm64-v8a` |
| Local system-image tag | `system-images/android-34/android-automotive/arm64-v8a` |
| Archive size | 4,669,762,816 bytes |
| Expected and actual SHA-1 | `24d28eb2f18eba5e87c861a7eb2bdccebb049419` |
| AVD | `Pleos_Connect_v2_1_0` |
| RAM | 4096 MB |
| Display | 2560 x 1440 at 160 dpi |
| Boot | Cold boot (`-no-snapshot-load`) |

The extracted image identifies vendor `42dot`, tag `android-automotive`, and API 34. Android's AVD tooling normalizes its local package path to `android-automotive`; it remains the SHA-verified Pleos image rather than a Google system image. Its included device profile is `42dot / Pleos Connect v2.1.0`.

The booted device reports:

```text
emulator-5554 device product:crp_avd_arm64
model:Car_on_arm64_emulator device:crp_avd_arm64
```

```text
Pleos/crp_avd_arm64/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys
```

## Start and inspect

```bash
scripts/environment/start-pleos-emulator.sh Pleos_Connect_v2_1_0
scripts/environment/check-pleos-environment.sh
```

The start script uses a cold boot, host GLES, and explicitly disables guest Vulkan. On macOS it launches through `launchctl`, so the emulator stays alive after the invoking shell exits. It waits for `sys.boot_completed=1` and forwards the Virtual Console to:

`http://127.0.0.1:8088/`

Cold boot is intentional: the official Pleos guide warns that Quick Boot can prevent VHAL from working correctly.

### Host stability note

Android Emulator 37.2.13 crashed once during a microphone probe. The macOS report `qemu-system-aarch64-2026-11-15-220826.ips` records `SIGSEGV / EXC_BAD_ACCESS` in gfxstream `EglContext` cleanup. It is not a macOS TCC termination and the faulting stack contains no microphone or CoreAudio call. The same userdata was preserved.

After switching to the software renderer, the final app microphone probe successfully started capture, observed nonzero PCM, remained running with the home app in front for 2.2 seconds, and stopped explicitly. This verifies short foreground-service capture continuity only. It does not establish speech/music separation, all-seat capture, or long-duration VAD reliability. See `evidence/microphone-probe-software.txt`. The emulator reported no AEC or noise-suppression effect.

Software-family renderers are not suitable for the complete Pleos UI: `-gpu software` made the bundled Unreal DrivingView repeatedly ANR during startup, while `-gpu swiftshader` made its GameThread terminate with SIGSEGV. Both failures left a focused activity with no focused input window. The final stable UI configuration is `-gpu host -feature -Vulkan`, matching the image's host-GPU preference while avoiding guest Vulkan. Under this configuration DrivingView remained alive with a real SurfaceView and input channels, no new render ANR/fatal occurred, and an adb tap on the navigation search control changed the visible UI to the recent-search panel. `PLEOS_EMULATOR_GPU_MODE` and `PLEOS_EMULATOR_VULKAN_MODE` can override the script defaults for diagnostics.

## Runtime capabilities

These observations come from the booted Pleos image, not a generic Android emulator.

### Reverse gear

- Android Automotive VHAL exposes `GEAR_SELECTION` (`0x11400400`, decimal `289408000`) and `CURRENT_GEAR` (`0x11400401`).
- Supported gear values include Park, Reverse, Neutral, and Drive.
- The standard read permission `android.car.permission.CAR_POWERTRAIN` is `normal`; it is materially different from privileged audio-control permissions.
- The forwarded Virtual Console returned HTTP 200 with title `Pleos Connect Virtual Console` and exposes `/vehicle/state` plus `/vehicle/gear?gear=...`.
- The public APK successfully connected the standard AAOS gear read adapter with the normal permission. Initial gear/parking state can be read, and the UI labels the source as emulator data.
- The console's DDS bridge is intentionally inactive without a live browser. Send `POST /browser/session?client=<id>` immediately and every second; the advertised timeout is 3000 ms.
- With client `quiet-drive-verification` active, `/vehicle/state` changed to `gear.bridge_ready=true`, `availability.strict_success=true`, and DDS transport available. `PUT /vehicle/gear?gear=Reverse` then returned HTTP 200 with `actual.value=Reverse`; `PUT ...gear=Park` returned HTTP 200 with `actual.value=Park`.
- The bounded client was closed with `POST /browser/session?client=quiet-drive-verification&closing=1`. Four seconds later the bridge was inactive and the retained actual gear was Park with speed and pedals at zero. Full evidence is in `evidence/console-bridge.txt`.
- This proves the Virtual Console-to-emulator gear bridge. App callback delivery remains a separate app integration assertion; controller tests still use explicit simulated input unless that callback is observed.

### Media volume and safety audio

Android Car Audio is enabled with three audio zones. The primary zone exposes separate buses:

| Bus | Context |
| --- | --- |
| `bus0_media_out` | media |
| `bus1_navigation_out` | navigation guidance |
| `bus2_voice_command_out` | voice command |
| `bus3_call_ring_out` / `bus4_call_out` | calls |
| `bus5_alarm_out` | alarm |
| `bus6_notification_out` | notifications |
| `bus7_system_sound_out` | system sounds |
| `bus8_vehicle_sound_out` | vehicle and safety sounds |

This proves the platform can separate media from navigation and safety contexts. It does **not** grant an ordinary contest APK permission to control those groups. On this image both permissions below are `signature|privileged`:

- `android.car.permission.CAR_CONTROL_AUDIO_VOLUME`
- `android.car.permission.CAR_CONTROL_AUDIO_SETTINGS`

Direct `CarAudioManager` group-volume, fade, and balance control therefore needs Pleos/vehicle signing or privileged allowlisting. An adb-installed APK cannot silently assume it. Product behavior must explicitly distinguish privileged vehicle control, best-effort public `AudioManager` behavior, and screen-only simulation.

### Microphone and background detection

- The image advertises `android.hardware.microphone`.
- Android 14 supports `RECORD_AUDIO` and the microphone foreground-service type.
- Host microphone input must be enabled and separately proven before VAD is called emulator-backed.
- Android 14 does not allow an app to start a microphone foreground service from an arbitrary background state. The user must grant permission and enable the feature from an allowed foreground state.

## Read-only verification commands

```bash
adb -s emulator-5554 shell getprop ro.build.fingerprint
adb -s emulator-5554 shell wm size
adb -s emulator-5554 shell dumpsys car_service
adb -s emulator-5554 shell dumpsys audio
adb -s emulator-5554 forward tcp:8088 tcp:8088
curl http://127.0.0.1:8088/vehicle/state
```

Sources:

- [Official Pleos emulator installation guide](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- [Official Pleos emulator configuration guide](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator)
- [Official Pleos vehicle emulator guide](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)
- [App challenge development environment](https://appdevchallenge.auto-hmg.io/guide/development)
