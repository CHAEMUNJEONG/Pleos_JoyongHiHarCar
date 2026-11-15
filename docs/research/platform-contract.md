# 차량·마이크 플랫폼 연동 계약

## VehicleBridge

`VehicleBridge`는 `android.car`가 없는 일반 Android에서도 로드될 수 있도록 선택적 차량 라이브러리를 런타임에 확인한다. 이름이 공개된 Android Automotive 클래스와 메서드만 조회하며, 권한 상승이나 비공개 필드 접근을 하지 않는다. Android Lint는 `android.*` 문자열 리플렉션을 일괄적으로 `PrivateApi`로 분류하므로 해당 오탐만 코드 설명과 함께 억제했다. 런타임에 문서화된 메서드나 권한이 없으면 capability는 닫힌 상태를 유지한다.

실제 미디어 음량 제어는 다음 조건을 모두 만족해야 활성화된다.

1. 장치가 Android Automotive로 식별되고 Car Service가 연결되어 있다.
2. `android.car.permission.CAR_CONTROL_AUDIO_VOLUME`이 실제로 부여되어 있다.
3. `USAGE_MEDIA`의 차량 음량 그룹을 읽을 수 있다.
4. 그 그룹의 모든 usage가 `USAGE_MEDIA` 또는 `USAGE_GAME`이다. 내비게이션, 음성 안내, 안전음 등 다른 usage가 하나라도 공유되면 차단한다.
5. 현재·최소·최대 음량 값이 유효하다.

권한은 선언 여부가 아니라 런타임 grant로 검사하며 각 읽기/쓰기 전에 다시 확인한다. 제어 API 오류가 발생하면 해당 세션의 실제 연동을 비활성화한다. 페이더와 밸런스는 현재 값을 신뢰할 수 있게 읽고 복원할 공개 계약이 확인되지 않아 제공하지 않는다.

기어 연동은 `GEAR_SELECTION`과 `CAR_POWERTRAIN` 권한이 실제로 제공될 때만 활성화한다. 최초 값과 이후 이벤트 모두 `CarPropertyValue.STATUS_AVAILABLE`인 경우에만 `onGear(reverse, parked)`를 전달한다. unavailable/error를 D/P로 가정하지 않고 읽기 기능을 비활성화하여 자동 음량이 잘못 복원되는 일을 막는다.

반환 값 계약:

- `canControlMedia`, `canReadGear`, `canReadParking`: 현재 사용 가능한 실제 연동만 `true`.
- `volume`, `minVolume`, `maxVolume`: 지원되지 않거나 읽기 실패 시 `-1`.
- `setMediaVolume`: 유효 범위로 제한하며 UI 효과음 플래그를 발생시키지 않는다.
- 상태 문자열은 실제 연결, 권한 미승인, 안전하지 않은 공유 그룹, 연결 종료를 구분한다.

## VoiceMonitor

`VoiceMonitor`는 16 kHz, mono, PCM16 `VOICE_COMMUNICATION` 입력을 메모리에서만 분석한다. 녹음 파일, 전사 결과, 네트워크 요청을 만들지 않는다. 중지 시 PCM 버퍼를 0으로 덮고 `AudioRecord`와 오디오 효과를 해제한다.

시작 전 `RECORD_AUDIO` grant와 시스템 마이크 음소거 상태를 검사한다. Android 14의 foreground-service 제약 때문에 호출자는 화면이 보이는 동안 `microphone` 유형 foreground service를 먼저 시작해야 한다. 모니터 자체는 서비스를 몰래 시작하지 않는다.

발화 추정은 적응형 RMS 에너지, 제한된 zero-crossing rate, 2-frame onset, 600ms hangover로 구성된다. AEC와 noise suppression은 장치가 제공할 때만 활성화한다. 상태에는 항상 `실험용 마이크 감지`와 AEC/NS 제공 여부, 음성 분리 정확도를 보장하지 않는다는 문구를 표시한다. 이 방식은 좌석 위치를 식별하지 않으며 음악 보컬·라디오·내비 안내를 완전히 배제하지 못한다.

마이크 권한 거부, 시스템 음소거, dead object, 연속 무음 입력, 읽기 오류가 발생하면 캡처를 종료하고 최종 `onSpeech(false)`를 전달한다. 캡처가 비정상 종료된 경우에만 `Listener.onStopped(status)`를 메인 스레드에서 호출하므로 서비스는 대화 기능을 끄고 foreground-service 유형을 안전하게 낮출 수 있다. 명시적 `stop()`은 `onStopped`를 호출하지 않는다.

각 캡처는 증가하는 세대 번호를 가진다. 중지 직후 다시 시작해도 이전 스레드의 `finally`와 이미 큐에 들어간 콜백은 새 `AudioRecord`, 상태, 발화 판단을 변경하지 못한다.
