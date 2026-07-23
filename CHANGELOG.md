# Changelog

모든 주요 변경 사항은 이 파일에 기록됩니다.
형식: [Semantic Versioning](https://semver.org/lang/ko/)

---

## [0.1.0] - 2026-07-23

StockPulse 첫 릴리즈 — **야간 배치(Part 1)** 와 **장중 실행 엔진(Part 2 M1+M2, dry-run/paper)**.

### 새 기능
- **시계열 축적 · 파생 지표** (#2): 종목별 일일 스냅샷(`daily_stock_snapshot`)과 파생 지표 7종
  (5/20일 등락률, 거래량 MA20 배율, 연속 상승/하락 일수, 변동성, 52주 고저 위치). 데이터 부족 시 NULL.
- **트레이딩 플랜 산출** (#2): yml 외부화 규칙 엔진으로 시그널 후보를 산출해
  `plan/YYYY-MM-DD.json`(+ DB)에 저장. `schemaVersion`/`mode: plan-only` 계약. 리포트에 요약 병기.
- **시장 지표 수집** (#2): 네이버 지수(KOSPI/KOSDAQ) · 한국은행 ECOS 환율 · KRX 수급.
  플랜의 `marketContext`로 연동. 세 소스 모두 기본 비활성.
- **날짜 지정 재실행** (#2): `--stockpulse.run-date=YYYY-MM-DD`로 과거 날짜 리포트·지표 재생성(멱등).
- **장중 실행 엔진 M1** (#4): `BrokerClient` 추상화 + `FakeBrokerClient`(인메모리 시뮬레이터),
  멱등 주문 + 5겹 리스크 가드, 진입/청산 평가, dry-run/paper/live 모드. `intraday` 프로파일로 구동.
- **재시작 리컨실리에이션 M2** (#4): 부팅 시 브로커 보유와 로컬 포지션 대조
  (수량 보정 / phantom 청산 / broker-only 보고). 브로커가 진실의 원천.

### 버그 수정 · 보안
- **보안 하드닝** (#5): 킬스위치 파일 영속화(재시작에도 트립 유지), 실전 모드 게이팅 강화
  (`mode=live`는 kill 파일 필수), 미인증 API를 loopback(127.0.0.1)으로 제한,
  ECOS/DART API 키의 로그 유출 차단(`SecretMasker`), SELL 경로 수량 검증.

### 내부 개선 · 문서
- Claude 2차 분석을 플랜의 `advisory` 필드로 격리 — 집행 규칙은 결정적으로 유지 (#2).
- 운영 신뢰성: 외부 heartbeat, 필수 소스 실패 시 degrade(플랜 미산출) (#2).
- 배치/장중 엔진 생명주기 분리(`@Profile`) (#4).
- 설계 문서: Part 1(ADR-001~004), Part 2(ADR-005~008), Part 2 보안 감사 보고서.

### 알려진 제약 (실주문 전 필수)
- KIS 실연동(`KisBrokerClient`)은 계좌·API 발급 후 예정 — 이번 릴리즈는 **dry-run/paper 검증까지**가 안전 범위.
- 부채 P2-5: 리컨실 phantom 청산의 실현손익 미상(다운타임 손실이 일손실 가드에 미반영) — `mode=live` 전 해소 필요.
- 부채 P2-6: broker-only 미관리 포지션은 청산되지 않음.
