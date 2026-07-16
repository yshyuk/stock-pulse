# 설계: Part 2 — 장중 실행 엔진 (Intraday Execution Engine)
**작성일**: 2026-07-16 · **작성**: architect agent · **상태**: Proposed
**입력 계약**: `plan/YYYY-MM-DD.json` (Part 1 산출, `schemaVersion=1`)

## 결정된 전제 (사용자 확정)
- **증권사 API**: 한국투자증권 **KIS** (실시간 시세 + 주문 + 모의투자).
- **주문 자율성(최종 목표)**: **완전 자동** — 리스크 가드 한도 내에서 사람 개입 없이 주문.
- **첫 마일스톤**: **모의투자 자동주문** (KIS 모의투자 계좌, 관찰 모드 생략).

---

## 0. 설계 요약 (TL;DR)

1. **런타임 분리** — Part 2는 Part 1(1회 실행 배치)과 **다른 생명주기**의 장시간 프로세스.
   장 시간(09:00~15:30) 동안 살아있다가 종료. 같은 리포지토리, 별도 프로파일(`intraday`)로 구동. (ADR-005)
2. **BrokerClient 추상화** — `getQuote / placeOrder / getPositions / getBalance` 인터페이스.
   `KisBrokerClient`가 모의/실전을 **설정(URL·tr_id)** 으로 전환. OAuth 토큰 캐시. (ADR-006)
3. **실행 안전 모델** — 멱등 주문(client order id), 리스크 가드(일 손실·종목당·총액·최대 포지션),
   재시작 리컨실리에이션, 킬 스위치, 모의→실전 명시적 게이팅. **완전 자동일수록 가드가 생명선.** (ADR-007)
4. **플랜 소비 계약** — `candidates[].entry/exit/sizing` + `constraints`만 사용, **`advisory` 무시**.
   진입/청산은 폴링 시세와 규칙 비교. (ADR-008)
5. **폴링 루프** — `@Scheduled`(기본 90초) 장중 게이트. REST 시세 폴링(웹소켓 불필요, 1~2분 주기라 충분).

```
08:50 launchd 기동 → 부팅 → [휴장일이면 즉시 종료] → 오늘 플랜 로드(없으면 청산전용 모드)
   → 포지션 리컨실(broker+store, 이월 포지션의 청산기준 포함 복원)
   └ loop(≈90s, 09:00~15:30):
        후보별: 시세 조회 → 진입조건 충족 & 미주문 → [가드 통과 시] 매수 → 기록 → 알림
        보유별: 시세 조회 → 목표/손절 도달 → 매도 → 기록 → 알림
        일손실 한도 초과 → 킬스위치 → 신규주문 중단 → 알림
15:25 → 미체결 주문 전량 취소 (EOD 클린업)
15:35 → 폴러 정지 → 당일 요약 알림 → 종료
```

---

## 1. ADR-005: 런타임 · 배포 — 같은 리포지토리, 별도 프로파일의 장시간 프로세스

### 상태
Proposed

### 컨텍스트
- Part 1은 "부팅 → 1회 실행 → `System.exit`"의 배치. `@Scheduled` 없음.
- Part 2는 **장중 상시 폴링**(주기 실행)이 필요 — 정반대 생명주기.
- 공유 자산이 많다: 플랜 JSON 스키마(`com.stockpulse.plan`), 알림(`Notifier`), 도메인, 향후 KIS 시세는
  Part 1 가격 소스로도 재사용 가능. → 코드 중복을 피하려면 한 리포지토리가 유리.

### 결정
**같은 Gradle 프로젝트, 모듈러 모놀리스 유지. Part 2는 `intraday` 스프링 프로파일로 구동하는 별도 실행 경로.**

- 새 패키지 `com.stockpulse.intraday` (Part 2 전용). Part 1 패키지는 건드리지 않음.
- 진입: `IntradayRunner`(ApplicationRunner) — `@Profile("intraday")`. 배치용 `BatchRunner`는
  `@Profile("!intraday")` 또는 `stockpulse.batch.auto-run`으로 상호 배타.
- 폴링: `@Scheduled(fixedDelayString=...)` 컴포넌트(`@Profile("intraday")`). `@EnableScheduling`도
  intraday 프로파일에서만 활성 → Part 1의 "no @Scheduled" 원칙 불변.
- **생명주기**: launchd가 08:50 기동. 프로세스는 장중 살아있고, `MarketClock`이 15:35 이후 정상 종료
  (`System.exit(0)`)를 트리거. launchd `KeepAlive=false`(1회 기동 후 종료 == 정상), 매 거래일 재기동.
- 웹 서버: 조회/헬스용으로 최소 유지(Part 1과 동일). 킬스위치 토글 엔드포인트를 여기에 얹을 수 있음.

### 대안
1. **별도 리포지토리/마이크로서비스** — 장점: 완전 격리. 단점: 플랜 스키마·알림·도메인 중복 또는
   공유 라이브러리화 필요, 셀프호스팅 개인 프로젝트엔 과함. 기각(오버엔지니어링).
2. **Part 1 배치에 장중 로직 흡수** — 생명주기가 근본적으로 다름(1회 vs 상시). 혼합 시 복잡·위험. 기각.
3. **멀티모듈 Gradle(batch/intraday/shared 분리)** — 경계는 깔끔하나 지금 규모엔 과함. 패키지 경계 +
   프로파일로 충분. 단일 모듈 유지, 규모 커지면 재검토(부채 #P2-1).

### 결과
- (+) 공유 코드 재사용, 배포 단순(jar 하나, 프로파일로 모드 선택).
- (−) 한 jar에 두 모드 공존 → 프로파일 오설정 위험. 기동 로그에 활성 모드 명시 + 프로파일 가드로 완화.
- 후속: launchd plist 2개(배치 06:00, 장중 08:50), `MarketClock`(장 시간·영업일 판정) 설계.
- 배포 주의: 프로파일은 조합으로 활성(`--spring.profiles.active=prod,intraday`) — DB 설정은
  prod에서 상속. 장중에 배치를 수동 재실행(`--stockpulse.run-date`)하면 intraday 웹서버와
  8080 포트가 충돌하므로 `--server.port=0`을 함께 줄 것.

---

## 2. ADR-006: BrokerClient 추상화 + KIS 구현 (모의/실전 설정 전환)

### 상태
Proposed

### 컨텍스트
- KIS는 OAuth 접근토큰(≈24h 유효), 주문 시 `hashkey`, 모의/실전이 **다른 도메인·다른 tr_id**를 씀.
- 실행 로직을 특정 증권사에 강결합하면 테스트·교체가 어려움. Part 1의 ★인터페이스 컨벤션과 일관되게 추상화.

### 결정
`BrokerClient` 인터페이스(★) + `KisBrokerClient` 구현.

```
interface BrokerClient {
    Quote getQuote(String symbol);                 // 현재가/호가
    OrderResult placeOrder(OrderRequest request);  // 매수/매도 (client order id 포함)
    List<Position> getPositions();                 // 계좌 보유 종목
    AccountBalance getBalance();                    // 예수금/평가금액
}
```
- `KisBrokerClient`: WebClient 재사용. `KisTokenManager`가 접근토큰 발급·갱신.
  **토큰은 파일/DB에 영속화** — KIS는 토큰 발급 자체에 레이트리밋이 있어, 장중 재시작(리컨실
  시나리오) 시 재발급이 거부될 수 있다. 메모리 캐시만으로는 불충분; 유효 토큰은 재사용한다.
- **모드는 3단계**: `stockpulse.broker.kis.mode = dry-run | paper | live` (기본 `dry-run`).
  - `dry-run`: 시세 조회는 실제, **주문은 전송하지 않고 로그·기록만** (shadow 검증용, 1급 모드)
  - `paper`: KIS 모의투자 도메인·tr_id로 실제 주문
  - `live`: 실전 — `live-confirmed=true` 동반 필수, 없으면 부팅 거부(ADR-007과 연동)
- 주문 요청엔 **client order id** 포함 → 멱등 키(중복 주문 차단). 키는 **결정적**이어야 한다:
  진입은 종목당 1일 1회이므로 `{planDate}-{symbol}-BUY` (재시도 seq 금지 — seq가 붙는 순간
  멱등성이 깨진다). 청산은 `{planDate}-{symbol}-SELL-{reason}` (reason: target|stop|eod).
- 외부 호출 복원력: 타임아웃, 재시도(멱등 조회만 자동 재시도; **주문은 자동 재시도 금지**, 미확인 시
  조회로 사후 확인), 레이트리밋 준수(KIS 초당 호출 제한).

### 대안
1. **KIS SDK/코드 직결(추상화 없음)** — 빠르지만 테스트에 모킹 어렵고 교체 불가. 기각.
2. **웹소켓 실시간 시세** — 1~2분 폴링엔 불필요, 연결관리 복잡. REST 폴링 채택. 대량 종목·초단타로 가면 재검토(부채 #P2-2).

### 결과
- (+) 모의투자로 전 구간 검증 후 URL만 바꿔 실전. 테스트에 `FakeBrokerClient` 주입 가능.
- (−) KIS 응답 스키마·tr_id 실검증 필요(Part 1 비공식 소스와 달리 공식이나 문서 정독 필수).
- 후속: `KisTokenManager` 토큰 캐시 전략, tr_id 매핑표(모의/실전 × 매수/매도/조회), 레이트리밋 가드.

---

## 3. ADR-007: 실행 안전 모델 — 멱등·리스크가드·리컨실·킬스위치·모의→실전 게이팅

### 상태
Proposed

### 컨텍스트
완전 자동 주문은 **실시간으로 손실이 발생**하는 시스템. 버그·중복·폭주·재시작이 곧 금전 손실.
안전장치가 기능이 아니라 **1급 요구사항**.

### 결정
다섯 겹 방어:

1. **멱등 주문** — 모든 주문은 client order id 기반. `order` 테이블에 의도 기록 후 전송,
   같은 키 재전송 금지. 재시작·재시도로 인한 중복 매수 차단.
2. **리스크 가드(`RiskGuard`)** — 매 주문 전 강제 검사, 하나라도 위반 시 주문 거부:
   - 종목당 최대 투입(`constraints.maxBudgetPerSymbolKrw`)
   - 총 투입 한도(`maxTotalBudgetKrw`)
   - 최대 동시 보유 종목 수
   - **일 손실 한도**(당일 실현+평가손실이 한도 초과 시 신규 주문 전면 중단)
   - 종목당 1일 1회(같은 후보 재진입 방지) — 플랜의 `validUntil` 준수
3. **재시작 리컨실리에이션** — 부팅 시 `broker.getPositions()` + 로컬 `order/position` 스토어를
   대조해 실제 상태 복원. 엔진이 장중 죽었다 살아나도 유령 주문·중복 없이 이어감.
   **이월 포지션의 청산 기준 포함**: `position` 테이블이 원본 플랜의 목표/손절가를 복사·영속화
   하므로(§5 데이터 모델), 다음 거래일 엔진이 새 플랜을 로드해도 어제 산 포지션을 계속 관리할
   수 있다 — 플랜의 `validUntil`은 진입만 막고, 청산 모니터링은 포지션이 닫힐 때까지 지속된다.
3b. **EOD 클린업** — 15:25에 미체결 주문(부분 체결 잔량 포함) **전량 취소**. 전송된 미체결
   주문은 멱등키로 막을 수 없는 유일한 이월 경로이므로, 장 마감 전 반드시 정리한다.
   취소 실패는 알림 + 다음 기동 시 리컨실에서 재확인.
4. **킬 스위치** — 파일 플래그(`STOCKPULSE_KILL_FILE`) 또는 설정/엔드포인트로 **즉시 신규 주문 중단**.
   일 손실 한도 초과 시 자동 발동. (청산까지 자동으로 할지는 M2에서 정책 결정 — 기본은 신규 중단만.)
5. **모의→실전 게이팅** — `mode=live`는 `stockpulse.broker.kis.live-confirmed=true` 동반 필수,
   아니면 부팅 거부. 실전 첫 전환은 소액 한도 강제.

- **로그·알림 필수**: 모든 주문 의도·체결·거부·가드 발동을 기록하고 Telegram/Discord로 통지(조용한 실패 금지 — Part 1 원칙 승계).

### 대안
1. **가드를 주문 로직에 인라인** — 누락·우회 위험. 단일 `RiskGuard` 게이트로 강제(모든 주문 경로가 통과). 채택.
2. **자동 청산까지 킬스위치에 포함** — 급락장 자동 손절이 오히려 손실 확정시킬 수 있어 기본은 신규 중단만,
   청산 정책은 별도 결정. 보수적 기본값 채택.

### 결과
- (+) 완전 자동의 폭주·중복·재시작 리스크를 구조로 차단. 모의로 전 구간 리허설.
- (−) 가드·리컨실 로직이 상당한 복잡도. → M1은 핵심 가드만, M2에서 리컨실·킬스위치 하드닝.
- 후속: 일 손실 계산(실현+평가) 소스, 킬스위치 자동 발동 임계, 실전 게이팅 체크리스트.

---

## 4. ADR-008: 플랜 소비 계약 & 진입/청산 평가

### 상태
Proposed

### 컨텍스트
Part 1 플랜(`plan-only`)을 Part 2가 주문으로 전환. 계약을 어기면 두 파트가 어긋남.

### 결정
- **로드**: `PlanLoader`가 `plan/YYYY-MM-DD.json`(또는 DB `trading_plan`) 로드,
  `schemaVersion` **미지원 시 거부**(부팅 실패로 안전 정지). `mode`가 `plan-only`가 아니면 거부.
- **플랜 부재 시(청산 전용 모드)**: Part 1이 degraded로 플랜을 스킵한 날(F-13) 등 플랜이 없으면
  엔진은 중단이 아니라 **청산 전용 모드**로 기동 — 신규 진입 없음, 이월 포지션의 목표/손절
  모니터링만 수행. 기동 시 "플랜 없음, 청산 전용" 알림 필수. (이월 포지션도 없으면 즉시 종료.)
- **사용 필드**: `candidates[].entry`(진입 기준가/유형), `exit`(목표/손절), `sizing`(종목당 한도),
  `constraints`(전역 한도·`validUntil`). **`advisory`는 절대 사용 안 함**(참고 전용 계약).
- **진입 평가(`SignalEvaluator`)**: 폴링 시세 vs `entry`. 지정가면 현재가가 진입가 이하 도달 시 매수 트리거
  (규칙은 결정적). `validUntil` 경과 후엔 신규 진입 없음.
- **청산 모니터링(`PositionManager`)**: 보유 포지션의 현재가가 `exit.targetPriceKrw` 이상 → 익절,
  `exit.stopLossPriceKrw` 이하 → 손절 매도.
- **수량 산정**: `sizing.maxBudgetKrw / 진입가` = 매수 수량(정수, 최소 1주 미만이면 스킵). 호가단위·최소주문 고려.

### 대안
1. **Part 2가 지표를 재계산해 자체 판단** — 계약 위반·이중 로직. 플랜의 결정적 필드만 집행. 기각.
2. **advisory를 진입 강도에 반영** — 무판단·격리 원칙 위반, AI가 집행에 개입. 기각(구조로 금지).

### 결과
- (+) 두 파트의 책임 분리 명확: Part 1=무엇을(플랜), Part 2=언제·어떻게(집행).
- (−) 플랜 스키마 진화 시 양측 동기화 필요 → `schemaVersion` 게이팅으로 안전.

---

## 5. 모듈 구조 (신규 `com.stockpulse.intraday`)

```
com.stockpulse
├── intraday
│   ├── IntradayRunner              # @Profile("intraday") 진입점 (부팅→리컨실→폴러 가동)
│   ├── MarketClock                 # 영업일·장시간(09:00~15:30, Asia/Seoul 고정) 판정, 종료 트리거
│   │                               #   휴장일 소스: KIS 휴장일 조회 API(우선) + 주말 필터.
│   │                               #   휴장일이면 부팅 직후 알림 없이 정상 종료(로그만).
│   ├── plan/PlanLoader             # 플랜 JSON 로드 + schemaVersion 게이팅
│   ├── poll/QuotePoller            # @Scheduled 폴링 루프(장중 게이트)
│   ├── signal/SignalEvaluator      # 후보 진입조건 평가 (순수)
│   ├── position/PositionManager    # 보유 포지션 목표/손절 모니터링
│   ├── order/
│   │   ├── OrderService            # 멱등 주문 실행(의도기록→전송→결과기록)
│   │   └── RiskGuard               # 주문 전 리스크 검사 게이트
│   ├── kill/KillSwitch             # 신규주문 중단 플래그(파일/설정/자동)
│   ├── recon/ReconciliationService # 부팅 시 broker+store 대조
│   └── domain                      # Order, Fill, Position, ExecutionState (VO 그룹화)
├── broker                          # ★ 신규 공유
│   ├── BrokerClient                # 인터페이스
│   ├── kis/KisBrokerClient         # 구현 (모의/실전 설정 전환)
│   ├── kis/KisTokenManager         # OAuth 토큰 발급·캐시
│   └── dto Quote/OrderRequest/OrderResult/Position/AccountBalance
└── (재사용) plan.*, notification.*, config.*
```

의존 규칙:
- `intraday` → `broker`, `plan`(읽기), `notification`(재사용) 허용. 역방향 금지.
- `broker`는 순수 외부 연동(도메인 무의존). `intraday.domain`은 인프라 무의존(의존성 역전).
- 알림은 Part 1 `Notifier` 재사용 — 체결·거부·가드·킬 이벤트 통지.

데이터 모델(신규 테이블):
- `order` (client_order_id UNIQUE, plan_date, symbol, side, qty, price, status, broker_order_id, ts)
- `fill` (order 참조, 체결가·수량·시각)
- `position` (symbol, qty, avg_price, opened_at, plan_date,
  **target_price, stop_loss_price** — 원본 플랜에서 복사·영속화) — 리컨실·이월 청산 기준.
  플랜은 하루살이지만 포지션은 며칠을 살 수 있으므로, 청산 기준은 포지션이 소유한다.
- `daily_pnl` (trade_date, realized, unrealized, updated_at) — 일 손실 가드 재료

---

## 6. 마일스톤 (첫 = 모의투자 자동주문)

### M1 — 모의투자 자동주문 MVP (첫 목표)
- `BrokerClient` + `KisBrokerClient`(모의) + `KisTokenManager`
- `PlanLoader`, `MarketClock`, `QuotePoller`, `SignalEvaluator`, `PositionManager`
- `OrderService`(멱등) + `RiskGuard`(종목당·총액·최대보유·일손실) — **핵심 가드 포함**
- 체결/거부/요약 알림. `order/fill/position` 저장.
- EOD 미체결 취소(15:25) + 이월 포지션 청산 기준 영속화 + 플랜 부재 시 청산 전용 모드 — M1 필수.
- 완료 기준: 모의계좌에서 플랜 후보 진입→목표/손절 청산이 자동 수행, 가드 동작, 재실행 안전,
  미체결 이월 0건.
- **진입 순서 고정**: `dry-run`(기본값)으로 최소 1거래일 shadow 검증 → 통과 후 `paper` 전환.

### M2 — 복원력 하드닝
- `ReconciliationService`(재시작 대조), `KillSwitch`(자동 발동 포함), 레이트리밋·타임아웃 가드 강화.

### M3 — 실전(소액)
- `mode=live` + `live-confirmed` 게이팅, 소액 한도 강제, 실전 tr_id 검증.

### M4 — 관측성·환류
- 당일 체결/손익 요약을 **다음날 아침 리포트(Part 1)** 에 환류, 간단 대시보드(선택).

---

## 7. 기술 부채 · 리스크 레지스터

| # | 항목 | 트리거 | 방향 |
|---|---|---|---|
| P2-1 | 단일 모듈에 batch+intraday 공존 | 코드 경계 흐려짐 | 멀티모듈 Gradle 분리 |
| P2-2 | REST 폴링의 지연 | 초단타/대량 종목 요구 | KIS 웹소켓 실시간 도입 |
| P2-3 | 킬스위치 자동청산 정책 미정 | 급락장 대응 필요 | 청산 정책 별도 ADR |
| P2-4 | KIS 레이트리밋 초과 | 종목 수 증가 | 호출 스케줄링/배치 조회 |

| 리스크 | 가능성 | 영향 | 완화 |
|---|---|---|---|
| 완전 자동 주문 폭주·중복 | 중 | 치명(금전) | 멱등키 + RiskGuard 게이트 + dry-run 리허설 + 킬스위치 |
| 엔진 장중 다운 | 중 | 포지션 방치 | 리컨실 + launchd 재기동 + 미체결 사후 조회 |
| KIS 토큰·API 오류 | 중 | 주문 실패 | 토큰 선갱신, 주문 무재시도+사후확인, 실패 알림 |
| 모의→실전 오전환 | 낮 | 치명 | live-confirmed 게이팅 + 소액 강제 |

## 8. 구현 가이드라인 (구현 Agent 준수)
1. **모든 주문은 `RiskGuard` + 멱등키 경유** — 우회 경로 금지. 멱등키는 결정적(재시도 seq 금지).
   주문은 자동 재시도 금지(사후 조회로 확인).
2. 모드 기본값은 항상 `dry-run`. `mode=live`는 게이팅(`live-confirmed`) 통과 없이는 부팅 거부.
3. 진입/청산 평가(`SignalEvaluator`)는 순수 함수 — 시세·플랜 입력, 외부호출 없음, 단위테스트 필수.
4. 조용한 실패 금지 — 주문/거부/가드/킬 전부 로깅 + 알림.
5. 시간·영업일은 `MarketClock`/주입 `Clock`으로만 — `now()` 직접 호출 금지(Part 1 승계).
6. 신규 라이브러리(KIS SDK 등) 도입 전 사용자 확인. KIS는 WebClient 직접 연동 우선 검토.
7. 엔티티 필드 15 초과 금지 — Order/Position은 VO 그룹화.

## 9. 핸드오프
- **HANDOFF → product-manager** (선행 권장): Part 2 PRD — 리스크 한도 **수치**(일 손실 한도, 실전 소액 기준),
  대상 종목 수, 알림 정책, 실전 전환 승인 기준. 설계는 구조를, PRD는 값·정책을 확정.
- **HANDOFF → security-auditor**: KIS 자격증명·토큰 보관, 킬스위치·게이팅 검토(실주문 전 필수).
- **HANDOFF → backend-expert** (M1): `broker` + `intraday` 구현. KIS 모의 연동부터.
- **선결 과제(사용자)**: KIS 계좌 개설 + API 신청(모의투자 포함) — 개발과 병행해 리드타임 확보.
