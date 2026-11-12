# 통합 설계: 매너볼트 (Manner-Volt)

사용자가 요구사항 승인과 함께 앱 구현을 직접 지시하여(2026-11-11), Workflow Planning, Application Design, Units Generation, Functional/NFR/Infrastructure Design을 이 문서 하나로 압축했다. User Stories는 요구사항(FR-1~7)과 기획서 4항 시나리오가 수용 기준 역할을 충분히 하므로 생략했다.

## 1. 실행 계획

| 단계 | 처리 | 이유 |
|---|---|---|
| User Stories | 생략 | 시나리오 ①~④가 수용 기준을 대신함 |
| Workflow Planning | 이 문서 | 사용자 직접 지시 |
| Application Design | 이 문서 2~4장 | 새 컴포넌트 다수 |
| Units Generation | 이 문서 5장 | 단위 2개 |
| Functional Design | 이 문서 6장 | 예측, 추천, 점수 규칙 |
| NFR / Infra Design | 이 문서 7~8장 | 보안 확장 블로킹 |
| Code Generation | 진행 | |
| Build and Test | 진행 | |

## 2. 확인된 Pleos 환경 (요구사항 2장의 가정 해소)

- 에뮬레이터: `Pleos_Connect_v2.1.0` AVD, Android 14 (API 34), arm64-v8a, 2560x1440, density 160, 가로.
- SDK: `ai.pleos.playground:Vehicle:2.3.2` (저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/`).
- 사용 API: `EvBattery.getEvBatteryStateOfCharge / registerEvBatteryStateOfCharge`, `isEvChargePortOpen / registerEvChargePortOpen`, `getEvChargeSwitchState`, `Gear.getCurrentGearSelectionState / registerGearSelection`. 권한 `pleos.car.permission.CAR_ENERGY`, `CAR_DRIVE`.
- 타입 API가 없는 값: 충전 상태(EvChargeState), 외기 온도, 충전 출력, 지하/실외. 시뮬레이터 값으로 채운다.
- 위치: Android `LocationManager`(AVD `hw.gps=yes`). 기기 안에서 충전 구역 안/밖만 계산한다.

## 3. 구성

```text
+------------------------- 차량 앱 (Kotlin/Compose) --------------------------+
|  UI: 내 충전 | 충전소 배치도 | 주변 충전소 | 매너볼트 | 시뮬레이터          |
|        |                                                                    |
|  AppController (상태 흐름, 시나리오, 알림)                                 |
|        |                       |                         |                  |
|  domain (순수 함수)     VehicleDataSource          MannerVoltBackend        |
|  - ChargePredictor      - PleosVehicleDataSource   - LocalBackend (시연)    |
|  - DepartureRecommender - SimulatedVehicleData     - RemoteBackend (HTTPS)  |
|  - MannerScore          - ZoneMonitor (GPS, 기기)          |                |
|  - DepartureDetector                                       |                |
+------------------------------------------------------------|----------------+
                                                             | TLS, Bearer 토큰
+--------------------- 백엔드 (AWS SAM) ---------------------v----------------+
|  API Gateway (REST, 스로틀링, 접근 로그)                                    |
|     -> ApiFunction (Python 3.12)  -> DynamoDB (SSE-KMS, PITR)              |
|     -> Secrets Manager (공공 API 키, 토큰 서명 키)                          |
|  EventBridge Schedule (1분) -> SweeperFunction (사전 알림, 유예 만료)        |
|  ApiFunction -> 한국환경공단 EvCharger API (getChargerInfo/Status, 캐시)    |
+-----------------------------------------------------------------------------+
```

텍스트 설명: 앱은 순수 도메인 함수, 차량 데이터 소스, 백엔드 인터페이스로 나뉜다. 백엔드는 시연용 LocalBackend(앱 내부, 같은 규칙)와 AWS RemoteBackend 중 하나를 쓴다. `API_BASE_URL`이 비어 있으면 LocalBackend를 쓴다.

## 4. 주요 인터페이스

- `VehicleDataSource { val snapshot: StateFlow<VehicleSnapshot>; start(); stop() }`
- `MannerVoltBackend`: `board(stationId)`, `nearby()`, `upsertSession(...)`, `reportDeparture(...)`, `joinQueue / leaveQueue`, `arrivalCheck(...)`, `me()`, `events(since)`
- 충전기 키: `statId + "-" + chgerId` (FR-7.2)

## 5. 단위 (Units)

| 단위 | 위치 | 내용 |
|---|---|---|
| U1 vehicle-app | `app/` | Compose UI, 도메인, Pleos/시뮬레이터 데이터, LocalBackend, RemoteBackend 클라이언트 |
| U2 backend | `backend/` | SAM 템플릿, Python Lambda (API, Sweeper) |

공통 정책 값: `config/policy.json`(앱 assets로 포함). 백엔드는 `backend/src/policy.json` 사본을 쓰고, 앱 단위 테스트가 두 파일이 같은지 검사한다.

## 6. 기능 설계 (비즈니스 규칙)

### 6.1 충전 완료 예측 (FR-1.1)
- 필요 에너지 `E = 용량kWh × max(0, 목표% − 현재%) / 100`
- 유효 온도 `T = 지하 ? 0.3×외기 + 0.7×15 : 외기`
- 온도 계수: T<0 → 0.75, T<10 → 0.85, T≤30 → 1.0, 그 외 0.95
- 유효 출력 `P = 충전기 출력 × 충전 효율(0.9) × 온도 계수`
- 출력 50kW 이상(급속)이면 80% 초과 구간은 출력 × 0.5
- 완료 시각 = 시작 + E / P (분 단위 올림). 현재%≥목표%이면 시작 시각.
- 성질: 결과 ≥ 시작, 현재%가 높을수록 완료가 늦지 않다(단조), 출력이 클수록 빠르거나 같다.

### 6.2 출차 시각 추천 (FR-1.2, 1.5, 5.4)
- 개인 지연 = 최근 10건의 (실제 출차 − 충전 완료) 분 평균, [0, 60]으로 제한. 기록 없으면 기본 10분.
- 추천 = 완료 + 개인 지연, 5분 단위 올림. 성질: 추천 ≥ 완료, 추천은 5분 배수.

### 6.3 매너볼트 점수 (FR-5.1, 5.2)
- 늦음 L = 실제 − 약속(분). L ≤ 5(허용) → +2. 그 외 −min(20, ceil((L−5)/10) × 3).
- 점수 범위 [0, 150], 초기 100.
- 점수는 참고 정보로만 표시한다. 대기 순번은 찜한 순서이며 점수에 따른 우선권이나 제한은 없다.
- 성질: 범위 유지, L이 커지면 점수 변화가 커지지 않는다(단조 비증가).

### 6.4 출차 판단 (FR-4.1, 4.3)
- 충전기 분리 AND 구역 이탈 → 확정. 구역 이탈만 → 의심(확정 안 함). 그 외 → 주차 중.
- 대기자 확인 "자리 비어 있음"과 판단이 다르면 불일치로 기록한다. 판단이 주차 중인데 "자리 비어 있음"이 2회 이상이면 이용 가능으로 바꾼다.

### 6.5 대기열과 알림 (FR-3, 5.3)
- 충전기당 최대 3명. 정렬은 (등급, 등록 시각).
- 출차 예정 15분 전 사전 알림, 시각 변경 시 알림, 출차 확정 시 알림.
- 출차 예정 + 유예 15분이 지나도 확정이 없으면 대기 1순위에게 같은 단지/주변 이용 가능 충전기를 안내한다.

## 7. NFR 설계 (Security Baseline)

| 규칙 | 설계 |
|---|---|
| SECURITY-01 | DynamoDB SSE(KMS), API Gateway는 HTTPS만, 앱은 `usesCleartextTraffic=false` |
| SECURITY-02 | API Gateway 접근 로그 → CloudWatch Logs |
| SECURITY-03 | Lambda JSON 구조화 로그, 토큰/좌표/키 기록 금지 |
| SECURITY-04 | N/A: HTML을 서비스하지 않음. API 응답에 `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` 추가 |
| SECURITY-05 | 모든 입력을 허용 목록 정규식, 길이, 시각 범위로 검증 |
| SECURITY-06 | 함수별 IAM 정책, 테이블 ARN과 특정 액션만, 비밀은 ARN 단위 `GetSecretValue` |
| SECURITY-07 | N/A: VPC를 쓰지 않는 서버리스 |
| SECURITY-08 | 기기 등록 시 HMAC 서명 토큰 발급, 모든 요청 검증, 세션 소유자만 수정 |
| SECURITY-09 | 일반 오류 메시지, 디버그 정보 없음, fail closed |
| SECURITY-10 | Gradle 버전 고정, `requirements.txt` 없음(표준 라이브러리 + 런타임 boto3), README에 `dependencyCheck`/`pip-audit` 단계 |
| SECURITY-11 | API Gateway 스로틀링, 기기당 쓰기 제한, 아래 오용 시나리오 |
| SECURITY-12 | 공공 API 키와 서명 키는 Secrets Manager. 앱에는 비밀 없음 |
| SECURITY-13 | 출차 시각, 점수 변경은 AUDIT 항목(변경자, 시각, 이전/이후) |
| SECURITY-14 | 로그 보관 90일 이상(`RetentionInDays: 90`) |
| SECURITY-15 | 전역 예외 처리기 → 500 일반 메시지 |

### 오용 시나리오 (SECURITY-11)
1. 허위 출차 확인: 출차 보고는 세션 소유자만 가능. 다른 사람의 "자리 비어 있음"은 1회로 상태를 바꾸지 않고 2회 이상, 서로 다른 기기여야 한다.
2. 대기열 독점: 기기당 동시 대기 1개, 충전기당 최대 3명.
3. 점수 조작: 점수는 서버만 계산. 클라이언트가 보낸 시각은 서버 시각 ±10분 밖이면 거부. 출차 시각 변경은 세션당 5회 제한.
4. 토큰 남용: 토큰 만료 30일, 기기 등록은 API Gateway 스로틀링.

## 8. 인프라 설계

- `backend/template.yaml` (SAM): API Gateway REST(스테이지 `v1`, 스로틀 burst 20 / rate 10), Lambda 2개(Python 3.12, arm64), DynamoDB 1개(PAY_PER_REQUEST, SSE, PITR, TTL `ttl`), Secrets Manager 2개, 로그 그룹 2개(90일), EventBridge Schedule.
- 테이블 키: `pk`, `sk`. 항목: `DEVICE#id/PROFILE`, `CHG#key/SESSION`, `CHG#key/QUEUE#ts#id`, `DEVICE#id/EVENT#ts`, `AUDIT#yyyymmdd/ts#id`, `CACHE#nearby/zcode`.

## 9. PBT 계획 (Partial: PBT-02, 03, 07, 08, 09)
- PBT-09: Kotest Property(`kotest-property-jvm` 5.9.1) + JUnit4.
- PBT-02: DTO JSON 직렬화 왕복.
- PBT-03: 6.1~6.3의 성질(범위, 단조, 5분 배수).
- PBT-07: 도메인 제약을 지키는 생성기(퍼센트 0~100, 출력 3~350kW, 온도 −20~45).
- PBT-08: 실패 시 시드 출력(Kotest 기본), 고정 시드 재현 방법을 테스트 가이드에 기록.
