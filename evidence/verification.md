# 검증 기록 · 2026-11-15

## 실행 환경

Pleos Connect v2.1.0 공식 ARM64 이미지, Android 14 / API 34, 2560×1440, Mac Apple Silicon. 실제 차량에서는 시험하지 않았다. SDK·이미지 무결성·시작 방법은 `docs/research/emulator-setup.md`에 기록했다.

## 자동 검증

| 검증 | 결과 | 증거 |
| --- | --- | --- |
| 정책 엔진 | 80 assertion 통과, Java 17 `-Xlint:all -Werror` | `policy-tests.txt` |
| Pleos 서비스 통합 | 26 check 통과, 명시적 시연 입력 사용 | `device-tests.txt` |
| 마이크 권한 거부 | 기능 토글 롤백·캡처 차단 2 check 통과 | `microphone-denied.txt` |
| 마이크 서비스 | 시작·비영 PCM 입력·짧은 백그라운드 유지·명시적 중지 확인 | `microphone-probe-software.txt` |
| release APK | 빌드, Android lint, APK 서명 검증 통과 | `apk-signature.txt`, `apk/SHA256SUMS` |
| Virtual Console 기어 | 브라우저 세션 heartbeat 후 DDS 활성, R/P 변경 HTTP200 및 실제 Console 상태 확인 | `console-bridge.txt` |
| 앱 기어 콜백 | 표준 읽기 권한 연결, 공식 Console의 R/P가 앱까지 전달되는 3 check 통과 | `gear-app-callback.txt`, `gear-console-drive.txt` |
| 설치용 APK 터치 | 상태 대화상자 → 시연 전환 → 세 토글 → 대화/R 입력 → 동시 감소 7/40 및 운전석 집중 시각화, P단에서 설정의 네 슬라이더 확인 | `quiet-drive-demo.png`, `demo-ui.xml`, `quiet-drive-settings.png`, `settings-ui.xml` |

release Android lint는 오류 0개, 경고 5개다. 경고는 가로 방향 고정, Android 12+ 백업 규칙 명시, 한국어 전용 문자열 조합에 관한 항목이다. 실제 배포 전 다국어·다양한 화면 형태·백업 정책을 정리할 수 있다.

## 확인한 동작

- 대화/R 중첩 시 감소량을 곱하지 않고 더 낮은 목표를 한 번 적용.
- 대화 침묵 대기와 R 해제 홀드, 재개 시 타이머 갱신, 시간 기반 램프.
- R단인 채 기능을 켜거나 일시 중지를 해제할 때 현재 기어 재적용.
- 음량 수동 변경 후 이전 값 강제 복원 방지, 0 음량 강제 해제 방지.
- 소리 위치 축별 수동 우선, 설정 변경·일시 중지·재개 시 소유권 보존.
- 일시 중지 중 새로 켠 ‘나만 듣기’는 재개할 때 적용.
- 오디오 API 확인 지연 시 임의 음량으로 기준값을 만들지 않음.
- 공개 앱의 표준 기어 읽기 연결 및 초기 P단 확인. 오디오 특권 미부여는 명확히 미지원으로 표시.

## 실차 기능과 구분할 것

- 앱의 볼륨·페이더/밸런스 시연 수치는 실제 차량 오디오에 쓰지 않는다.
- 공개 설치 APK에 `CAR_CONTROL_AUDIO_VOLUME`/`CAR_CONTROL_AUDIO_SETTINGS`가 부여되지 않아 실제 차량 음량·페이더/밸런스는 검증하지 않았다.
- Console R/P → 앱의 실제 AAOS 콜백 전달을 검증했다. 이때 미디어 음량 쓰기는 특권 미부여로 차단되어 있다.
- 마이크 시험은 짧은 캡처 생명주기 확인이다. 실제 발화 정확도·전체 좌석·장시간 백그라운드·보컬/라디오/내비 오인 방지 성능은 미검증이다. 해당 이미지에는 AEC/NS가 제공되지 않았다.
- 정상 중지는 복원을 시도하지만 연결 단절·강제 종료·전원 차단 시 복원을 보장하지 않는다.

## 수정·재검증한 결함

읽기 불가능한 차량 음량으로 기준값을 정하던 문제, 활성 R 재개 누락, 최소 음량 정규화, 축별 수동 우선의 pause/resume 손실, 마이크 시작 실패 후 토글 잔류, 모드 전환 후 서비스 잔류를 수정했다. 일시 중지 중 새로 선택한 집중 모드가 재개되지 않는 재현 시험은 실패 확인 후 수정하여 통합 시험에서 통과했다.

## 환경에서 발견한 문제

초기 host GPU 경로는 gfxstream 정리 중 SIGSEGV가 발생했다. software/SwiftShader에서는 Pleos 기본 DrivingView가 반복 ANR·종료를 일으켜 입력 확인에 영향을 주었다. 최종 `-gpu host -feature -Vulkan`에서 기본 화면과 입력이 정상화되었고 설치용 앱의 대화상자·시연 토글·설정 화면을 실제 터치로 확인했다. 환경 기록 및 최종 화면을 참고한다.
