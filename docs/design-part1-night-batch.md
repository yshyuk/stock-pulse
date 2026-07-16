# 설계: 야간 배치 고도화 (Part 1)
**작성일**: 2026-07-15 · **작성**: architect agent · **입력**: `docs/PRD-part1-night-batch.md`
**상태**: Proposed

이 문서는 PRD F-01~F-13 중 Sprint 1·2 범위(시계열 축적, 플랜 산출)의 구조적 결정을 담는다.
구현 상세(코드)는 data-engineer / backend-expert 영역이다.

---

## 0. 설계 요약 (TL;DR)

1. **시계열은 리포트와 별개의 1급 데이터** — `Report`(텍스트 스냅샷)와 분리된
   `daily_stock_snapshot` / `daily_market_snapshot` 테이블에 적재. 자연키 `(symbol, trade_date)` upsert로 멱등.
2. **파생 지표는 적재 시 계산해 같은 행에 저장** (읽기 시 계산 아님) — ADR-002.
3. **플랜은 신규 `plan` 모듈** — 규칙 평가(결정적) → `TradingPlan` 생성 → JSON 직렬화 →
   파일+DB 저장. AI advisory는 별도 필드로만 부가.
4. **파이프라인 통합**: `collect → process → [A] timeseries 적재 → [B] plan 생성 →
   report 생성(plan 요약 포함) → analyze → store → notify`. 기존 단계는 건드리지 않고 두 단계 삽입.
5. **날짜 파라미터화**: 파이프라인 전 단계가 "오늘"을 직접 조회하지 않고 `runDate`를 주입받는다
   (F-03 재실행의 전제).

---

## 1. ADR-001: 시계열 저장 구조 — 별도 스냅샷 테이블 + 자연키 upsert

### 상태
Proposed

### 컨텍스트
- 현재 유일한 영속 데이터는 `report` 테이블(날짜별 Markdown 전문). 텍스트라 지표 질의 불가.
- PRD F-01: 종목·날짜 단위 구조화 적재. F-03: 재실행 멱등(upsert). 워크로드는
  **일 1회 쓰기(종목 수십 건) + 기간 조회(최근 N일)** 로 극히 가볍다.
- Part 2 실행 엔진과 미래 백테스트가 이 테이블을 읽는다.

### 결정
`daily_stock_snapshot` 테이블 신설. 원시값과 파생 지표를 한 행에 담는다.

```
daily_stock_snapshot
  id                BIGINT PK (auto)
  symbol            VARCHAR(16)   NOT NULL   -- 예: 005930
  trade_date        DATE          NOT NULL
  -- 원시 (수집값)
  price             DECIMAL(18,2) NOT NULL
  previous_price    DECIMAL(18,2)
  volume            BIGINT
  -- 파생 (적재 시 계산, 데이터 부족 시 NULL)
  change_rate_1d    DECIMAL(9,4)
  change_rate_5d    DECIMAL(9,4)
  change_rate_20d   DECIMAL(9,4)
  volume_ma20_ratio DECIMAL(9,4)             -- 당일 거래량 / 20일 평균
  streak_days       INT                       -- 연속 상승(+)/하락(-) 일수
  volatility_20d    DECIMAL(9,4)             -- 20일 일간수익률 표준편차
  range_position    DECIMAL(9,4)             -- 축적분 고저 대비 위치 0~1
  source            VARCHAR(32)   NOT NULL   -- naver | dummy | (미래) kis
  collected_at      TIMESTAMP     NOT NULL
  UNIQUE KEY uq_symbol_date (symbol, trade_date)
```

`daily_market_snapshot` (Sprint 3, F-05/06)도 동일 원칙:
`(indicator_code, trade_date)` UNIQUE — indicator_code 예: `KOSPI`, `KOSDAQ`, `USDKRW`, `SPX`, `IXIC`.

- 쓰기는 **upsert** (`(symbol, trade_date)` 기준). 재실행 시 덮어쓰기 → 멱등.
- 조회 패턴은 "symbol별 최근 N일" — UNIQUE 인덱스가 그대로 커버.
- 파생 지표 컬럼이 13개 내외로 15 미만이나, 확장 시 그룹화(별도 테이블 분리)를 검토한다.

### 대안
1. **리포트 JSON(내용)에 지표 포함, 테이블 신설 안 함** — 장점: 스키마 변경 없음.
   단점: 기간 질의 불가, Part 2가 텍스트 파싱해야 함. 기각.
2. **원시값만 저장, 파생 지표는 조회 시 계산** — ADR-002에서 별도 판단.
3. **EAV(지표명-값 세로 테이블)** — 장점: 지표 추가 시 스키마 무변경. 단점: 조회·정합성 복잡,
   지표 5~10종 규모에서 오버엔지니어링. 기각. 단, 지표가 계속 늘면 재검토(부채 레지스터 #1).

### 결과
- (+) Part 2·백테스트의 조회 기반 확보, 멱등 재실행 단순화.
- (−) 파생 지표 정의 변경 시 과거 행 재계산 필요 → F-03 재실행으로 해소 가능(기간 재실행은 백로그).
- 후속: data-engineer가 DDL/JPA 매핑·H2/MySQL upsert 전략(`ON DUPLICATE KEY` vs select-then-save) 확정.

---

## 2. ADR-002: 파생 지표는 적재 시 계산·저장 (read-time 계산 아님)

### 상태
Proposed

### 컨텍스트
파생 지표(F-02)를 어디서 계산하나 — 적재 시 vs 조회 시.

### 결정
**적재 시 계산해 스냅샷 행에 저장한다.** 계산 주체는 신규 `timeseries` 모듈의
`DerivedMetricCalculator`(직전 N일 스냅샷을 읽어 당일 파생값 산출).

### 대안
- **조회 시 계산**: 장점 — 지표 정의 변경이 즉시 반영. 단점 — Part 2 실행 엔진(장중 1~2분 주기)이
  매번 계산 비용·로직 중복을 짊어짐. 소비자가 여럿(플랜 생성기, 리포트, Part 2)이라 계산을
  한 곳(야간 배치)에 고정하는 것이 결정성·재현성(PRD KPI) 요구에 부합. 기각.

### 결과
- (+) 소비자는 SELECT만으로 지표 사용. 플랜 재현성 보장.
- (−) 지표 버그 수정 시 과거 재계산 필요(F-03로 복구).
- 규칙: **파생 지표 계산은 DB에 있는 데이터만 사용** (당일 수집분 + 과거 스냅샷). 외부 재호출 금지.
- 데이터 부족(예: 5일치뿐인데 20일 지표) 시 **NULL 저장** — 0이나 근사값 금지 (플랜 오염 방지).

---

## 3. ADR-003: 트레이딩 플랜 스키마 — 결정적 규칙 산출물 + advisory 격리

### 상태
Proposed

### 컨텍스트
- F-08: 플랜은 Part 2 실행 엔진과의 **계약**. 기계 파싱 가능해야 하고, 버전 변경을 견뎌야 한다.
- 무판단 원칙: 플랜의 집행 필드는 규칙의 기계적 산출이어야 하며 AI가 수정 불가(F-11).

### 결정
플랜 JSON v1 스키마 (필드는 Value Object 그룹화 원칙 적용):

```jsonc
{
  "schemaVersion": 1,                  // 필수. Part 2는 미지원 버전이면 거부
  "planDate": "2026-07-15",            // 집행 대상 거래일
  "generatedAt": "2026-07-15T06:03:12+09:00",
  "mode": "plan-only",                 // Part 1은 항상 plan-only (비기능 요구)
  "constraints": {                     // 플랜 전역 리스크 한도
    "maxBudgetPerSymbolKrw": 500000,
    "maxTotalBudgetKrw": 2000000,
    "validUntil": "2026-07-15T15:30:00+09:00"
  },
  "marketContext": {                   // Sprint 3 전까지는 null 허용
    "kospiChangeRate": null,
    "usdkrw": null
  },
  "candidates": [                      // 규칙 매칭된 시그널 후보 (0건이면 빈 배열)
    {
      "symbol": "005930",
      "name": "삼성전자",
      "matchedRules": ["volume-surge-3x", "up-5pct"],   // 규칙 id 목록 (근거 추적)
      "entry":  { "type": "limit", "priceKrw": 61000 }, // 규칙이 산출한 진입 기준
      "exit":   { "targetPriceKrw": 64000, "stopLossPriceKrw": 59000 },
      "sizing": { "maxBudgetKrw": 500000 },
      "evidence": {                    // 판단 근거 스냅샷 (재현성)
        "changeRate1d": 5.2, "volumeMa20Ratio": 3.4, "streakDays": 2
      }
    }
  ],
  "advisory": {                        // AI 참고 의견 — 집행 필드와 격리, null 허용
    "source": "claude",
    "text": "...",
    "generatedAt": "..."
  },
  "warnings": ["missing-data:000660:2026-07-14"]  // F-04 누락 감지 등
}
```

원칙:
- **`candidates[*]`의 모든 값은 규칙 엔진의 결정적 산출** — 같은 입력이면 같은 출력.
- **`advisory`는 append-only 참고 필드** — Part 2는 advisory를 집행에 사용하지 않는다(계약 명문화).
- 규칙 정의는 yml 외부화(F-09). v1 표현력은 "지표 임계값의 AND 결합 + 진입/청산 가격 산식(기준가 ± %)"로 한정.
- 저장: 파일 `plan/YYYY-MM-DD.json` + DB `trading_plan` 테이블(`plan_date` UNIQUE, `content` JSON, `schema_version`).
  ReportStore와 동일한 이중화 패턴.

### 대안
1. **리포트에 플랜 섹션 포함(별도 산출물 없음)** — Part 2가 Markdown 파싱해야 함. 기각.
2. **DB 정규화 테이블로만 저장(JSON 없음)** — Part 2가 DB 스키마에 강결합. 파일 기반 JSON은
   실행 엔진을 DB 없이도 기동 가능하게 함. JSON을 원본, DB는 이력/조회용으로. 기각(단독으로는).
3. **candidates에 근거 없이 심볼·가격만** — 재현성 KPI 위배, 사후 검증 불가. 기각.

### 결과
- (+) Part 2 설계가 이 스키마 하나에서 시작 가능. AI 개입 경계가 구조로 강제됨.
- (−) 스키마 진화 관리 부담 → `schemaVersion` + "필드 추가는 minor, 의미 변경은 version bump" 규칙.
- 후속: backend-expert 구현 시 JSON 스키마 검증(저장 전) 포함 — 검증 실패 시 플랜 미저장 + 경고 알림.

---

## 4. ADR-004: 모듈 배치와 파이프라인 통합 지점

### 상태
Proposed

### 컨텍스트
기존 구조는 단계별 패키지(collector/processor/report/analysis/storage/notification)의
모듈러 모놀리스. 신규 책임 둘: 시계열 적재·파생지표, 플랜 생성.

### 결정
기존 컨벤션을 따라 **패키지 2개 신설**, BatchPipeline에 **단계 2개 삽입**.

```
com.stockpulse
├── timeseries                       # 신규 — Epic A
│   ├── SnapshotService              # ★ 적재 진입점: metrics → 파생계산 → upsert
│   ├── DerivedMetricCalculator      # 과거 N일 조회 → 파생 지표 산출 (순수 계산)
│   ├── DailyStockSnapshot           # @Entity
│   └── DailyStockSnapshotRepository # 최근 N일 조회, upsert
├── plan                             # 신규 — Epic C
│   ├── PlanService                  # ★ 진입점: snapshots → 규칙 평가 → TradingPlan
│   ├── rule/
│   │   ├── PlanRule                 # 규칙 1건 (yml 바인딩: id, 조건, 진입/청산 산식)
│   │   └── RuleEvaluator            # 지표 vs 규칙 매칭 (결정적, 순수)
│   ├── TradingPlan / PlanCandidate  # 도메인 (VO 그룹: Constraints, Entry, Exit, Sizing, Evidence)
│   ├── PlanStore                    # ★ 저장 추상화 (File + Db 구현, ReportStore 패턴 답습)
│   └── PlanJsonSerializer           # 스키마 검증 포함 직렬화
└── batch/BatchPipeline              # 수정 — 아래 삽입
```

파이프라인 순서 (기존 번호 유지, A·B 삽입):

```
1 collect → 2 process → [A] snapshotService.record(runDate, metrics)
                      → [B] planService.generate(runDate)      // 스냅샷 DB를 읽음
→ 3 report 생성(+ 플랜 요약 섹션) → 4 analyze(advisory를 플랜에도 부가) → 5 store(report + plan) → 6 notify
```

의존 규칙:
- `plan` → `timeseries`(Repository 조회)는 허용. 역방향 금지.
- `report`는 `TradingPlan`을 입력으로 받아 요약 섹션 렌더링(모델 전달, plan 내부 접근 금지).
- `analysis`는 기존대로 Report를 받고, 결과를 plan의 advisory에도 반영 — 오케스트레이션은
  BatchPipeline이 담당(모듈 간 직접 호출 금지 유지).
- **가격 소스 실패 시(F-13)**: [A] 이전에 중단 → 플랜 미산출. 부분 실패 허용은 비-P0 소스만.

runDate 주입 (F-03):
- `BatchPipeline.run()` → `run(LocalDate runDate)`로 변경.
- `BatchRunner`가 `--stockpulse.run-date`(기본: `Clock` 기준 오늘)를 해석해 전달.
- 기존 `WebClientConfig`의 `Clock` 빈을 그대로 활용 — 어떤 단계도 `LocalDate.now()` 직접 호출 금지.
- 주의: 과거 날짜 재실행 시 **수집 소스가 과거 시세를 못 주는 한계**(네이버는 현재가만) →
  v1에서는 "DB에 이미 있는 원시값 기반 파생 재계산 + 플랜 재생성"으로 범위 한정. 원시값
  소급 수집은 Part 2의 KIS API(일봉 조회) 도입 시 해소(부채 레지스터 #2).

### 대안
1. **processor 패키지에 파생 지표 흡수** — MetricProcessor는 "당일 원시→당일 지표"의 순수 변환.
   시계열 조회(DB 의존)가 섞이면 책임 혼합. 기각.
2. **plan을 analysis 하위에 배치** — analysis는 "AI 보강" 확장점, plan은 결정적 산출물.
   성격이 달라 분리. 기각.
3. **이벤트 기반(스프링 이벤트로 단계 분리)** — 단일 프로세스 1회 실행 배치에서 순차
   오케스트레이션이 더 단순·추적 용이. 오버엔지니어링. 기각.

### 결과
- (+) 기존 5개 확장 인터페이스 컨벤션(★ 패턴)과 일관. 각 단계 독립 테스트 가능.
- (−) BatchPipeline 생성자 의존성 증가(8→10) → 지금은 허용, 12 초과 시 단계(Stage) 추상화 검토(부채 #3).

---

## 5. 기술 부채 레지스터

| # | 항목 | 트리거 | 방향 |
|---|---|---|---|
| 1 | 스냅샷 테이블 가로 확장 | 파생 지표 15종 초과 | 지표 그룹별 테이블 분리 또는 EAV 재검토 |
| 2 | 과거 원시값 소급 수집 불가 | Part 2 KIS API 도입 | 일봉 조회로 backfill 커맨드 추가 |
| 3 | BatchPipeline 의존성 비대 | 생성자 파라미터 12 초과 | PipelineStage 인터페이스 도입 |
| 4 | 규칙 yml 표현력 (AND만) | OR/기간 조건 요구 발생 | 규칙 DSL 확장은 별도 ADR로 |

## 6. 구현 가이드라인 (구현 Agent 준수 사항)

1. 파생 지표 계산은 **순수 함수**로 (`DerivedMetricCalculator`) — 입력: 당일 값 + 과거 스냅샷 리스트, DB 접근 금지. 단위 테스트 필수.
2. 데이터 부족 지표는 **NULL**, 절대 0 대체 금지.
3. upsert는 `(symbol, trade_date)` 자연키 기준. H2(local)와 MySQL(prod) 양쪽 동작 확인.
4. 플랜 JSON은 저장 전 스키마 검증. 실패 시 플랜 미저장 + WARNING 알림 (배치 전체는 성공 처리).
5. `LocalDate.now()` / `Instant.now()` 직접 호출 금지 — 주입된 `Clock`/`runDate` 사용.
6. 새 설정은 `StockPulseProperties`에 바인딩 (`stockpulse.plan.*`, `stockpulse.timeseries.*`), 시크릿은 환경변수.
7. 신규 라이브러리(JSON 스키마 검증기 등) 도입 전 사용자 확인 (전역 규칙).

## 7. 핸드오프

- **HANDOFF → data-engineer** (P0): ADR-001 기반 DDL·JPA 매핑·upsert 전략 확정
  (H2/MySQL 호환, 인덱스). 완료 기준: 마이그레이션 스크립트 + Repository 조회 메서드 시그니처.
- **HANDOFF → backend-expert** (P0, data-engineer 후): F-01~03 구현
  (SnapshotService, DerivedMetricCalculator, runDate 파라미터화). 이후 Sprint 2에서 plan 모듈.
- **HANDOFF → qa-engineer** (Sprint 2): 파생 지표 경계값·플랜 스키마 검증 테스트 전략.
