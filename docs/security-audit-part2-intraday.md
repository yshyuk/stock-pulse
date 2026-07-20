# 보안 감사 보고서: Part 2 장중 실행 엔진 (자동매매)
**작성일**: 2026-07-17 · **감사**: security-auditor agent · **대상**: `broker`, `intraday`, 자격증명 처리, 노출 표면
**상태**: 조치 완료 (High 2 / Medium 3 수정, Low 2 문서화)
**판정**: 실주문(`mode=live`) 전 **KIS 연동 후 재감사 필요** — 현 코드베이스 자체는 조치 완료

---

## 1. 위협 모델 (Threat Model)

| 자산 | 위협 | 영향 |
|---|---|---|
| KIS 자격증명(app key/secret, 계좌번호), 액세스 토큰 | 유출 → 제3자가 계좌에서 주문 | **치명적**(금전) |
| 주문 실행 능력 | 무단·폭주·중복 주문 | **치명적**(금전) |
| 킬스위치 | 무력화/소실 → 손실 한도 후에도 거래 지속 | **높음**(금전) |
| 실전 모드 게이트 | 오전환 → 의도치 않은 실거래 | **치명적**(금전) |
| 트레이딩 플랜·포지션 데이터 | 노출 → 전략 유출, 선행매매 표적 | 중간 |

신뢰 경계: Mac Mini 로컬(단일 사용자) ↔ LAN ↔ 외부 API(KIS/DART/ECOS). 웹 서버는 장중 상시 기동.

---

## 2. 발견 및 조치

### [High-1] 킬스위치 상태가 프로세스 재시작으로 소실 — **수정됨**
- **문제**: `KillSwitch.tripped`가 in-memory `AtomicBoolean`이고 `engage()`가 영속화하지 않음. 일 손실 한도 초과로 스위치가 걸린 뒤 크래시/launchd 재시작 시 **스위치가 풀려 거래 재개**.
- **악화 요인**: RiskGuard가 DB에서 일손익을 재계산해 재트립하지만, 부채 **P2-5**(리컨실 phantom 청산이 `realizedPnl=null`)와 결합되면 다운타임 중 발생한 손실을 놓쳐 **재트립되지 않음**. 수동 `engage()`는 무조건 소실.
- **조치**: `engage()`가 kill 파일을 기록해 트립을 영속화. 파일 미설정 시 "재시작 시 소실됨"을 ERROR로 경고.
- **검증**: `KillSwitchTest` — 새 인스턴스(=재시작)가 트립을 계속 인식.

### [High-2] 실전 모드에 out-of-band 정지 수단 없음 — **수정됨**
- **문제**: `kill-switch-file` 기본값이 `""`(비활성). 운영자가 돌아가는 엔진을 즉시 멈출 수단이 프로세스 kill 외 없음.
- **조치**: `LiveModeGuard`가 `mode=live`일 때 `kill-switch-file` 필수 검증 → 없으면 **부팅 거부**.
- **검증**: `LiveModeGuardTest` — 미설정 시 `IllegalStateException`.

### [Medium-3] 인증 없는 API가 트레이딩 플랜 노출 (OWASP A01) — **수정됨**
- **문제**: `/api/reports/{date}`, `/api/reports/latest`가 인증 없이 공개. `server.address` 미설정 → **0.0.0.0(모든 인터페이스)** 바인딩. intraday 프로파일에선 장중 내내 기동 → LAN(포트포워딩 시 인터넷)의 누구나 당일 플랜·포지션 조회 가능. Spring Security 미도입.
- **조치**: `server.address` 기본값을 `127.0.0.1`(loopback)로 제한. 원격 접근이 필요하면 **인증 추가 후** 의도적으로 override.

### [Medium-4] API 키가 로그로 유출 가능 (OWASP A09/A02) — **수정됨**
- **문제**: ECOS는 키를 **URL 경로**에, DART는 **쿼리 파라미터(`crtfc_key`)** 에 배치. 호출 실패 시 `CollectorService`가 `log.error(..., e.getMessage(), e)`로 예외 메시지+스택트레이스를 기록 → WebClient 예외는 요청 URI를 포함하므로 **키가 평문으로 로그 파일**(`/Users/Shared/stock-pulse/logs/app.log`)에 기록됨.
- **조치**: `SecretMasker` 도입. ECOS/DART가 예외를 자체 처리해 마스킹 로그만 남기고 빈 결과로 degrade(둘 다 optional 소스라 `DataSource` 계약에 부합) → 원시 예외가 CollectorService의 스택트레이스 로깅에 도달하지 않음.

### [Medium-5] SELL 경로에 가드 없음 — **수정됨**
- **문제**: `submitSell`은 `RiskGuard`를 거치지 않음(청산은 킬 상태에서도 허용해야 하므로 **의도된 설계**). 다만 가드가 전무해 손상된 포지션 레코드가 있으면 비정상 수량 매도 시도.
- **조치**: 수량 sanity check(`qty <= 0` 거부) 추가. 의도적 우회임을 코드 주석으로 명시.

### [Low-6] KIS 토큰 영속화 시 파일 권한 — **미구현(향후 필수)**
- 설계상 토큰을 파일/DB에 영속화 예정(발급 레이트리밋 때문). 평문 + 기본 umask면 로컬 타 사용자가 열람 가능.
- **요구사항**: 토큰 파일 `0600`, 디렉토리 `0700`. DB 저장 시 접근 계정 분리. **KIS 연동 PR에서 반드시 검증**.

### [Low-7] 실전 게이트가 동일 신뢰 경계 내 — **수용(문서화)**
- `mode=live`와 `live-confirmed=true`가 같은 env에서 오므로 실수 방지(speed bump)일 뿐, 침해된 환경에선 보호 안 됨. 단일 사용자 셀프호스팅 규모에서 수용 가능.

---

## 3. 양호 항목 (확인됨)
- 시크릿 하드코딩 **없음** — 전부 환경변수 주입 (`application*.yml`에 평문 없음)
- `.gitignore`가 `.env`/`*.env` 차단, `.env.example`만 허용
- `deploy/com.stockpulse.batch.plist`는 `REPLACE_ME` 플레이스홀더 — 실 시크릿 미커밋
- BUY 주문은 **RiskGuard 단일 게이트**를 반드시 통과 (우회 경로 없음)
- 결정적 멱등키(`{planDate}-{symbol}-BUY`)로 중복 주문 차단
- 기본 모드 `dry-run` — 설정 실수로 실주문이 나갈 수 없음
- 주문 자동 재시도 없음(중복 체결 방지)
- 로그에 토큰/키 직접 출력 없음 (`LiveModeGuard`는 mode/impl만 기록)

## 4. 실주문 전 필수 체크리스트
- [ ] KIS 자격증명은 launchd plist `EnvironmentVariables`로만 주입 (파일/코드 금지)
- [ ] 토큰 영속화 파일 권한 `0600` / 디렉토리 `0700` 검증 (Low-6)
- [ ] `kill-switch-file` 설정 + **실제로 파일 생성해 정지되는지 리허설**
- [ ] 부채 **P2-5 해소** — KIS 체결내역으로 다운타임 실현손익 소급 (미해소 시 일손실 가드가 손실 과소집계)
- [ ] `mode=live` 전환 시 소액 한도 강제 + `live-confirmed=true`
- [ ] KIS 연동 코드 **재감사** (URL/헤더의 키 노출, 응답 로깅)
- [ ] 로그 파일 권한 및 보관 위치 점검 (`/Users/Shared/`는 전체 사용자 읽기 가능 — 이전 검토 필요)

> **주의**: `/Users/Shared/stock-pulse/logs/`는 macOS에서 **모든 로컬 사용자가 읽을 수 있는 경로**입니다. 단일 사용자 머신이면 수용 가능하나, 계정이 여럿이면 로그 경로를 사용자 홈 하위로 옮길 것.
