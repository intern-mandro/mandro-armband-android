> ⚠️ **구버전 문서 (2026-07-22 archive됨)** — Chaquopy 로컬 학습 전환 작업의
> 실행 체크리스트. 전환 자체가 이미 완료되고 현재 아키텍처의 기본 전제가 된
> 지 오래라(HANDOVER.md §2-1 3단계 참고) 이 TODO 목록은 전부 지난 일이 됨.
> 히스토리 보존 목적으로만 남김.

# Chaquopy 로컬 학습 전환 — TODO

`LOCAL_MIGRATION.md` 로드맵 중 "1단계 — Chaquopy 실험"이 끝난 뒤의 실행 체크리스트.

---

## 완료

- [x] Chaquopy 실현 가능성 확인 — numpy/scipy/scikit-learn이 Python 3.10용 wheel로 Chaquopy 저장소에 존재 (scipy/scikit-learn은 cp310까지만 지원, 3.11+ 없음)
- [x] APK 용량 실측 — arm64-v8a 단일 ABI 빌드 기준 **81.5MB** (Chaquopy 넣기 전 대비 큰 폭 증가, 감수하기로 결정)
- [x] TF vs sklearn 학습 품질/속도 벤치마크 — 실제 수집 데이터 2세션 기준
  - 세션1: TF acc 0.9112/F1 0.9080 vs sklearn acc 0.8774/F1 0.8805
  - 세션2: TF acc 0.9323/F1 0.9192 vs sklearn acc 0.9216/F1 0.9171
  - 학습 시간: sklearn이 PC 기준 5~12배 빠름
  - 벤치마크 스크립트: 세션 스크래치패드 `bench_chaquopy.py` (필요 시 재실행 가능)
- [x] `build.gradle.kts` / `app/build.gradle.kts`에 Chaquopy 플러그인 설정 (Python 3.10, arm64-v8a 필터, numpy/scipy/scikit-learn pip install)
- [x] Python 모듈 골격 생성 — `app/src/main/python/training_local.py` + `lib/`(config, preprocessing, pipeline, windowing, training, serialize, features/features.py)

---

## Phase 1 — Python 학습 파이프라인 완성

- [x] `lib/preprocessing.py`: `mandro-backend/app/ml/lib/preprocessing.py`에서 `BP_filter`, `compute_envelope` 복사
- [x] `lib/features/features.py`: `mandro-backend/app/ml/lib/features/features.py`에서 `extract_features`, `extract_features_tsd` 복사
- [x] `lib/pipeline.py`: `extract_features_pipeline`, `fit_scaler`, `apply_scaler` 복사
- [x] `lib/windowing.py`: `get_windows`, `get_action_windows`, `split_train_val` 복사
- [x] `lib/preprocessing.py`의 `preprocess_and_extract()`: `mandro-backend/app/services/training.py::_preprocess_and_extract` 로직 이식
- [x] `lib/training.py`의 `fit_local_model()`: `MLPClassifier` 학습 코드 작성 (docstring에 가이드 있음)
- [x] `lib/serialize.py`의 `serialize_weights()`: 가중치+스케일러 → 53,304바이트 바이너리 직렬화 (mean/std 264개 포함, NN 가중치만이면 52,248바이트)

## Phase 2 — Python 모듈 단독 검증 (Kotlin 붙이기 전)

- [x] PC에서 `training_local.run_training_local()`을 직접 호출해 실제 세션 데이터(`mandro-backend/storage/data/`)로 돌려보고, 출력 바이트 길이 53,304 확인
- [x] `bench_chaquopy.py`와 정확도 비교해서 이식 과정에서 실수 없었는지 검증
  - 세션1: ported acc=0.8774/f1=0.8805 vs bench acc=0.8774/f1=0.8805 (diff 0)
  - 세션2: ported acc=0.9216/f1=0.9171 vs bench acc=0.9216/f1=0.9171 (diff 0)
  - 완전 일치 — 이식 과정 로직 실수 없음

## Phase 3 — Kotlin ↔ Chaquopy 연동

- [x] `MandroApplication.onCreate()`에서 `Python.start(AndroidPlatform(this))` 초기화
- [x] `LocalTrainingRepository`/`LocalTrainingRepositoryImpl` 신설 — `EmgSerialization.kt`의 공용 `serializeBatch()`로 EMG/라벨 `ByteArray` 변환
- [x] `Dispatchers.IO`에서 `Python.getInstance().getModule("training_local").callAttr("run_training_local", ...)` 호출 → `ByteArray` 수신, 크기(53,304) 검증
- [x] `TrainingProgressViewModel`을 서버 업로드(`uploadAndTrain`) 대신 로컬 학습으로 교체, 결과를 `files/models/$userId/weights.bin`에 저장
- [ ] 실제 폰(에뮬레이터 아님)에서 학습 시간 실측 — Chaquopy는 폰 CPU로 도니까 PC 벤치마크(1~3.6초)와 다를 수 있음

## 서버 코드 전체 제거 (예정보다 앞당겨 완료)

로컬 전환 방향이 확정되면서 서버 의존 코드를 이번에 전부 걷어냄:

- [x] `UserRepositoryImpl` → 로컬 Room(`UserEntity`/`UserDao`)으로 전환, 유저 생성 시 UUID 로컬 생성
- [x] `EmgRepository`에서 `uploadTake`/`uploadAndTrain`/`getSessionHistory`/`downloadSessionFirmware`/`saveHeaderFiles`/`getLatestSession` 제거
- [x] `FirmwareViewModel` — 서버 세션 대신 로컬 `weights.bin` 파일 존재 여부로 판단
- [x] `CollectViewModel`의 랩마다 서버 업로드 호출 제거
- [x] History 화면(서버 전용, 로컬 대응 없음) 전체 삭제
- [x] `MandrApiService`/DTO/`NetworkModule`/Retrofit·OkHttp 의존성 제거

## Phase 4 — 가중치 전송 프로토콜 (USB + BLE) — 완료

방향 확정: **A안(LittleFS)**. `docs/FIRMWARE_PROTOCOL.md`의 모델 플래시 방식(9절, 펌웨어 통째 재컴파일)은 서버 기반 구방식 문서이고, 이 문서(`LOCAL_MIGRATION.md`)가 로컬 전환 후 신방식. `UsbRepositoryImpl.kt`에 있던 `MDRO`+`model.tflite`+`scaler.bin` 프로토콜은 옛날 코드였음 — 폐기.

- [x] `serialize_weights(model, scaler)`로 스케일러(mean/std 264개, 1,056바이트) 포함하도록 수정 — NN 가중치만으로는 펌웨어가 입력 정규화를 못 함
- [x] `UsbRepositoryImpl.kt` 재작성: `[4B 매직넘버 0xDEADBEEF][4B 길이][53,304B 가중치+스케일러][4B CRC32]` 단일 페이로드 프로토콜, `flash(weightsBytes: ByteArray)` 시그니처로 단순화
- [x] `FirmwareViewModel`/`LocalTrainingRepositoryImpl`/`TrainingProgressViewModel`의 바이트 상수 53,304로 갱신
- [x] BLE 경유 전송 구현 — `BleManager.kt::sendWeights()`. USB와 동일한 페이로드를 MTU 기준 244바이트씩(~219개) 순차 Write Request로 전송, 신규 Characteristic(`...58`, WRITE+NOTIFY)의 NOTIFY로 `OK:WEIGHTS`/`ERR:*` 응답 대기 (10초 타임아웃). 상세 스펙: `docs/FIRMWARE_PROTOCOL.md` 4-1절
- [x] `WeightTransferState`(Idle/Sending/Done/Error) 신설 — `BleRepository`/`BleRepositoryImpl`/`MockBleRepository`에 배선
- [x] UI 연결 완료 — `FirmwareViewModel`이 `usbRepository` 대신 `bleRepository`를 쓰도록 변경 (`bleState`로 연결 확인, `sendWeights()`로 전송, `weightTransferState`로 진행률/완료/에러 반영). USB는 아래 "USB 상태" 참고 — 코드는 남겨뒀지만 이 화면에서는 더 이상 안 씀. `FirmwareScreen.kt`의 체크리스트도 "USB 케이블 연결됨"+"암밴드 감지됨" 2개 → "암밴드 연결됨" 1개로 통합

## Phase 5 — 펌웨어 쪽 — 완료 (2026-07-10, 상대방 담당이었으나 이번엔 같이 구현함)

- [x] `MODEL.h`에서 가중치 배열 제거, 토폴로지 상수만 유지
- [x] `nn.cpp`에 `loadFromLittleFS()` 구현 (W0 b0 W1 b1 W2 b2 means stds 순서로 읽기, 총 13,326 floats).
  기존 `predict()`는 가중치를 "W 전부 이어붙인 배열 + b 전부 이어붙인 배열"로 나눠 저장하는
  레이아웃이라, 파일의 interleaved 순서를 읽으면서 이 레이아웃으로 재배치하는 게 핵심이었음.
  means/stds는 `Preprocessor::setStandardizer()`로 별도 전달 (스케일러는 `NeuralNet`이 아니라
  `Preprocessor`가 씀).
- [x] `.ino`에 LittleFS 초기화 + USB/BLE 수신 모드(매직넘버 0xDEADBEEF 감지 → LittleFS 저장 →
  재부팅) 추가
- [x] BLE GATT Characteristic `abcd1234-5678-1234-5678-abcdef123458` (WRITE+NOTIFY) 구현 —
  `WeightsCharCallbacks::onWrite()`가 244바이트 청크를 누적 버퍼에 재조립, 매직넘버/LENGTH/CRC32
  다 모이면 저장. 스펙은 `docs/FIRMWARE_PROTOCOL.md` 4-1절.
- [x] 임시 파일 안전장치 — `saveWeightsIfCrcOk()`가 `/weights.tmp`에 먼저 쓰고 CRC 통과 시에만
  `/weights.bin`으로 rename. USB/BLE 공용 함수로 구현해서 검증 로직 중복 없음.
- [x] **BLE 실기기 테스트 성공** — 파이썬 `bleak` 라이브러리로 더미 가중치(53,304바이트,
  219개 청크)를 실제 BLE로 전송 → CRC 통과 → `OK:WEIGHTS` NOTIFY 응답 → 재부팅까지 확인됨.
  청크 재조립/CRC/저장 파이프라인이 실기기에서 정상 동작함을 검증 완료.
- [ ] **USB 상태: 미완료, 보류.** USB Serial(`Serial.readBytes()` 연속 스트림)로 53KB
  더미 데이터를 보내면 매번 PAYLOAD 수신 도중 타임아웃남 (청크 나눠보내기+타임아웃 5초로
  늘려도 37,925/53,304에서 실패). ESP32-S3 네이티브 USB CDC가 대용량 연속 전송에서 멈추는
  알려진 코어 버그([espressif/arduino-esp32#10836](https://github.com/espressif/arduino-esp32/issues/10836))로
  추정 — USB 프로토콜에 BLE처럼 청크 단위 확인(ACK) 절차가 없는 게 근본 원인일 가능성 높음.
  Android 앱이 지금 BLE 위주로 가기로 결정(위 Phase 4 참고)해서 지금은 안 고침. 나중에 USB를
  다시 살리려면 청크마다 짧은 ACK를 주고받는 프로토콜로 바꿔야 함. 코드는 삭제하지 않고 남겨둠.
- [x] LittleFS 파티션 용량 확인 — 이 펌웨어는 `partitions.csv`가 따로 없어 보드 기본
  스킴(≈1.4MB LittleFS 파티션)을 그대로 씀. 실측(53,304B 저장 시 `사용 중` 65,536B)
  기준으로 256KB 요구사항은 이미 여유롭게 충족 — 추가 작업 불필요.

## Phase 6 — 통합 테스트

- [ ] BLE 수집 → 로컬 학습 → BLE로 가중치 전송 → 펌웨어 재부팅 → 추론까지 전체 플로우 실기기로 확인
- [ ] 실측 학습 정확도가 기존 서버 학습 대비 사용자가 체감할 만큼 떨어지는지 확인 (Phase 2 벤치마크 기준 세션별 1~3.4%p 차이 — 실사용에서 문제없는 수준인지 재확인)
