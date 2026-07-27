# Changelog

모든 주요 변경 사항은 이 파일에 기록됩니다.
형식: [Semantic Versioning](https://semver.org/lang/ko/)

---

## [0.2.0] - 2026-07-27

### 저장소 무결성 수정 (중요)
- **`plan/` 패키지 누락 복구**: `.gitignore`의 앵커 없는 `plan/` 규칙이 런타임 산출물뿐 아니라
  `src/main/java/com/stockpulse/plan` 소스 패키지까지 무시해, 플랜 패키지 전체(main+test)가
  v0.1.0까지 한 번도 커밋되지 않았음(클린 clone 시 컴파일 불가). 규칙을 `/plan/`·`/reports/`로
  루트 앵커해 소스 패키지를 보호하고 패키지 전체를 git에 편입. (#7)

### 새 기능
- **트레이딩 플랜 schema v2 (stock-api signal 연동)** (#7): `mode = plan-only | signal`,
  `PlanCandidate.priority`(결정적 랭킹), v1·v2 동시 지원 직렬화(구 플랜 재읽기 호환).
- **PlanDispatcher** (#7): `signal` 모드 플랜을 외부 stock-api SignalIngest로 전송(기본 off,
  전송 실패는 fail-safe로 배치 비중단). `plan-only` 플랜은 전송 거부.
- **M4 매매 결과 환류** (#7): 전일 실현손익 + 보유 포지션을 다음날 아침 리포트의
  "전일 매매 요약" 섹션으로 표기 — 플랜 → 실행 → 리포트 폐루프 완성.

---

## [0.1.0] - 2026-07-23

StockPulse 첫 릴리즈 — **야간 배치(Part 1)** 와 **장중 실행 엔진(Part 2 M1+M2, dry-run/paper)**.

> ⚠️ 이 릴리즈는 `plan/` 소스 패키지가 gitignore로 누락되어 클린 clone 시 컴파일되지 않습니다.
> v0.2.0에서 복구되었습니다.

### 새 기능
- **시계열 축적 · 파생 지표** (#2): 일일 스냅샷 + 파생 지표 7종, 데이터 부족 시 NULL.
- **트레이딩 플랜 산출** (#2): 규칙 엔진 → `plan/YYYY-MM-DD.json`(+ DB), 리포트 요약 병기.
- **시장 지표 수집** (#2): 네이버 지수 · ECOS 환율 · KRX 수급, 플랜 `marketContext` 연동.
- **날짜 지정 재실행** (#2): `--stockpulse.run-date`로 과거 날짜 재생성(멱등).
- **장중 실행 엔진 M1** (#4): BrokerClient 추상화 + FakeBrokerClient, 멱등 주문 + 5겹 리스크 가드.
- **재시작 리컨실리에이션 M2** (#4): 브로커 보유와 로컬 포지션 대조(브로커가 진실의 원천).

### 버그 수정 · 보안
- **보안 하드닝** (#5): 킬스위치 파일 영속화, 실전 게이팅, API loopback 제한,
  자격증명 로그 유출 차단, SELL 경로 검증.
