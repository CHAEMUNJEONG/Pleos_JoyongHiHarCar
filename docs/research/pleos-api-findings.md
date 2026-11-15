# Pleos API feasibility findings — 2026-11-15

## Decision summary

The app provides a **controller/state-machine and explicit audio demo**. Standard AAOS gear reads, including Console R/P callbacks, were subsequently verified on the official Pleos emulator using a normal permission. The documented Pleos Vehicle SDK itself does not expose Gear or Audio domains, and cross-app media-volume/fader/balance control remains blocked by privileged audio permissions. Do not silently substitute this with the app's own player volume.

| Requirement | Public Pleos / AAOS evidence | Deliverable status |
|---|---|---|
| Detect R selection | Virtual Console can simulate gear, but Vehicle SDK v2.3.2's published domain list has no Powertrain/Gear domain. Standard AAOS offers `GEAR_SELECTION` with a normal read permission. | **Verified on Pleos emulator via standard AAOS**, including R/P callback delivery; real vehicle support still needs testing. |
| Lower currently playing media/radio while preserving safety tones | No Audio domain is published by Pleos Vehicle SDK. Android's `CarAudioManager` requires signature/privileged audio-control permission. | **Blocked for third-party public app** unless Hyundai explicitly grants an approved integration. |
| Fader/balance | No Pleos Audio domain. AAOS audio-settings control is signature/privileged. | **Blocked for third-party public app** unless Hyundai explicitly grants an approved integration. |
| Background speech/activity detection | Android microphone foreground service can capture after user consent; Pleos STT provides transcription, not a documented VAD/occupant-localization API. | **Short foreground/background capture verified**, including nonzero input; occupant versus playback accuracy remains unverified. |

## Official Pleos evidence

### SDK / emulator baseline

* The currently published Vehicle guide is for **Vehicle SDK 2.3.2 / Vehicle Service 2.3.21**. It requires capability checks before calls and approved permissions; unapproved permissions or calls can fail market review. [Vehicle overview](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro), [SDK API policy](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/).
* Project setup says **Vehicle State** is available without application, but **Vehicle Control** and SpeechToText are listed as `Coming Soon` in the portal status table. Treat this as current portal policy, not an entitlement. [Project & SDK application](https://document.pleos.ai/docs/pleos-only/create-my-project).
* Development baseline: Android API 28+, Gradle 8+, Java/Kotlin; current emulator instructions use **Pleos Connect v2.1.0 / API 34**, cold boot and 4 GB RAM. [Emulator installation](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator).
* Vehicle artifact: repository `https://nexus-playground.pleos.ai/repository/maven-releases/`, dependency `ai.pleos.playground:Vehicle:2.3.2`. [Vehicle import guide](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup).
* Published `Vehicle` getters and API domains include Brake, CarInfo, Display, Door, DrivingMode, HVAC, Seat, etc.; there is **no Gear/Powertrain/Audio** getter or domain. This is strong negative evidence against public-SDK implementation of R or audio routing. [Vehicle API reference](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle/vehicle/entry), [published domains](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/entry).
* The Virtual Console explicitly lets testers simulate **gear** under Motion. This establishes emulator scenario support only; it does not add a public Vehicle SDK getter. [Virtual Console setup](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator).

### Permissions

* Pleos Vehicle APIs require `pleos.car.permission.CAR_*` reads and `CONTROL_CAR_*` writes; API-level `@RequiresPermission` is authoritative. `CAR_DRIVE` is the documented common read permission for `DrivingMode`, `Brake`, and `Odometer`, but the documented API surface does not map it to gear. [Vehicle permissions](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission).
* SpeechToText dependency is `ai.pleos.playground:SpeechToText:3.3.1`; it requires `pleos.car.permission.STT_SERVICE`. [STT import](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/importing-sdk), [STT permission](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/declaring-permissions).

### Speech route

* STT starts vehicle-mic recognition with `request()` and ends it with `stop()`; it is a speech-to-text service, so it is not evidence for a no-transcript VAD API. [Start/stop recognition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/start-and-stop).
* `ON_DEVICE` mode avoids a network and app registration; `HYBRID`/`SERVER` send recognition requests to the remote service. This matters for the stated no-recording/no-conversation-storage requirement. [STT overview](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro).
* The SDK can accept app-held PCM through `sendAudio()` only in ON_DEVICE mode. That could support a local VAD prefilter, but obtaining microphone PCM still needs normal Android microphone access and does not provide seat identity or reliable acoustic separation from radio/music. [Sending audio data](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data).

## AAOS public API evidence (not automatically available to a Pleos third-party app)

* AAOS `VehiclePropertyIds.GEAR_SELECTION` is the selected gear, read-only, on-change, and its reference says it needs `Car.PERMISSION_POWERTRAIN`. It would be the semantically correct R trigger **if** the vehicle image exposes the property and the app is granted access. [Android API reference](https://developer.android.com/reference/android/car/VehiclePropertyIds#GEAR_SELECTION).
* `CarAudioManager` controls car audio volume groups and supports volume callbacks/features, but Android declares `CAR_CONTROL_AUDIO_VOLUME` as a **signature|privileged** permission; `CAR_CONTROL_AUDIO_SETTINGS` is likewise **signature|privileged** for car audio settings. A normal market app cannot acquire those merely by declaring them. [Car permissions](https://developer.android.com/reference/android/car/Car), [CarAudioManager](https://developer.android.com/reference/android/car/media/CarAudioManager).
* Therefore an AAOS fallback based on `CarAudioManager.setGroupVolume`, `setFadeTowardFront`, or `setBalanceTowardRight` must be compiled/used only after a written Hyundai entitlement/approval. It must also verify runtime audio features; AAOS notes OEM Audio Service can alter focus/volume/ducking behavior. [CarAudioManager feature reference](https://developer.android.com/reference/android/car/media/CarAudioManager).

## Background microphone requirements

For an API-34 Pleos AVD, a local VAD service must declare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `RECORD_AUDIO`, and a service type of `microphone`. Android permits ongoing microphone capture in the background through that visible foreground service, but it cannot be *started* while the app is backgrounded because `RECORD_AUDIO` is while-in-use. The feature toggle must therefore start/stop it while the user is interacting with the app and display the required ongoing notification. [Android foreground-service microphone requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone), [Android 14 enforcement](https://developer.android.com/about/versions/14/changes/fgs-types-required).

## Recommended implementation boundary

1. Build the reducer/state machine now: independent `conversationActive`, `reverseActive`, `paused`, and `soloListening` inputs; target volume is computed once from the active condition set (never compounded). Preserve a manual-change generation/version so restoration only occurs if the user has not changed the relevant setting.
2. Implement an **explicit Simulation Adapter** for the UI/demo. It may accept test switches/Virtual Console observations, but it must visibly label audio/gear actions `SIMULATED` until capabilities/entitlements are observed.
3. Implement an **Integration Adapter** that is disabled by default and only enables individual controls after: (a) Pleos `checkXXXCapabilities`, (b) all requested permissions are actually granted, (c) emulator test proves the selected media-only group excludes safety/parking alerts, and (d) Hyundai confirms the audio and gear APIs are approved for this project.
4. For conversation detection, prefer local energy/VAD over retaining text or audio. Show microphone-active status and do not claim occupant/seat discrimination or perfect rejection of speaker playback without an OEM-provided reference/echo-cancellation signal and measured evidence.

## Competition delivery note

The contest accepts mockups/prototypes/screen recordings as a demonstration video and gives additional credit for a GitHub repository/APK. Its README requirement includes the tested Pleos emulator version and display specification. [Submission guide](https://appdevchallenge.auto-hmg.io/guide/submission), [development environment guide](https://appdevchallenge.auto-hmg.io/guide/development).

## Follow-up: standard gear read permission is not privileged

The Android 14 AOSP manifest explicitly declares `android.car.permission.CAR_POWERTRAIN` as **normal**, for reading current gear/ignition/parking-brake state. `CONTROL_CAR_POWERTRAIN` is a separate privileged permission. Thus the public APK declares the read-only permission and capability-tests `CarPropertyManager`; OEM signing is not intrinsically required for standard gear reads. Pleos approval language for its own Vehicle SDK permissions should not be generalized to this standard Android normal permission. Runtime exposure and callback delivery still require emulator/device testing. [Android 14 car manifest](https://android.googlesource.com/platform/packages/services/Car/+/refs/tags/android-14.0.0_r75/service/AndroidManifest.xml#428), [GEAR_SELECTION contract](https://developer.android.com/reference/android/car/VehiclePropertyIds#GEAR_SELECTION).
