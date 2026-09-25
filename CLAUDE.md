# StockPulse — 프로젝트 컨텍스트

한국 주식시장 일일 리포트 배치. Mac Mini에서 launchd로 매일 새벽 실행된다.

```
06:00  배치(jar)          KRX 전종목 수집 → 스냅샷 → 스크리닝 → 리포트 → 텔레그램·디스코드
06:10  analyze-report.sh  리포트를 claude -p 로 해석 → 텔레그램·디스코드
```

## 스택

Java 21 / Spring Boot 3.3.5 / Gradle(Kotlin DSL) / MySQL(prod)·H2(local) / JUnit5 + AssertJ + Mockito

---

## 이 프로젝트의 핵심 원칙

### 1. 조용한 실패를 만들지 않는다

**이 프로젝트에서 가장 중요한 규칙이다.** 실제로 하드코딩된 샘플 데이터(삼성전자 78,900원,
실제 270,000원)가 몇 달간 프로덕션 리포트로 나갔는데 아무도 몰랐다. 배치는 exit 0이었고
알림도 정상이었다. 이후 발견된 결함 11개가 **전부 같은 형태**였다.

따라서:
- 데이터를 못 받으면 **실패**한다 (`EmptyRunPolicy` — 평일 0건이면 exit 1 + FAILURE 알림)
- 리포트에 **데이터 출처를 표기**한다. 샘플이 섞이면 숫자보다 앞에 ⚠️ 배너
- 에러는 **stdout·stderr 양쪽을 다 남긴다** (claude는 인증 실패를 stdout으로 낸다)
- 로그 문구가 사실과 다르면 **버그로 취급**한다. 부정확한 로그는 다음 사람의 진단을 막는다
- 값을 조용히 버리지 않는다 (중복 제거 시 어느 쪽을 버렸는지 WARN)

### 2. 스크리닝은 비용 통제이지 투자 판단이 아니다

전종목 2,765개를 그대로 리포트·2차분석에 넘기면 토큰이 폭증하고 출력도 잘린다.
`ScreeningStage`가 유동성 게이트 → 룰 매칭 → 랭킹 → 상한으로 좁힌다.

- **이상징후 탐지**이며 매수/매도 후보가 아니다
- 정렬은 "얼마나 특이한가"(매칭 룰 수 → 거래량비 → 등락률 절대값). **상승/하락 편향 없음**
- 판단은 2차 분석(Claude)에서 한다 — 배치는 객관적 지표만 담는다
- **플랜 스테이지는 전체 스냅샷을 계속 받는다.** 상한 때문에 플랜 후보가 조용히 누락되면
  추적이 매우 어렵다

### 3. 검증은 실행으로 한다

단위 테스트 통과와 "동작한다"는 다르다. 이 프로젝트에서 실제로:
- 테스트는 초록불인데 파서가 아무것도 파싱 못 하고 있었다(픽스처가 구 스키마)
- 2종목 픽스처로 통과한 코드가 2,765종목에서 버퍼 한도로 죽었다
- 13개 테스트 전부 통과인데 리포트 상위 30개가 "가장 특이한 30"이 아니었다

**픽스처는 실응답에서 캡처한다.** 파서를 보고 픽스처를 쓰면 파서가 자기 자신을 검증한다.

---

## 배포

### 구조
```
~/Documents/Repository/stock-pulse   레포 (TCC 보호 폴더 — launchd가 읽지 못함)
~/apps/stock-pulse/                  실행 산출물 (jar, analyze-report.sh, analysis-prompt.md)
~/Library/LaunchAgents/              com.stockpulse.batch.plist, com.stockpulse.analyze.plist
/Users/Shared/stock-pulse/           reports/, plan/, logs/
```

**`~/Documents`에서 직접 실행할 수 없다.** macOS가 보호하는 폴더(TCC)라 launchd 에이전트가
읽지 못하고 `exit 126` + `Operation not permitted`로 죽는다. 반드시 `~/apps`로 내보낸다.

### 배포 명령
```bash
git checkout release && git pull origin release
./deploy/install.sh              # 빌드 + 배포 + 검증
./deploy/install.sh --no-build   # Java 변경 없을 때
```

배포 대상이 **jar 하나가 아니라 셋**이다. 수동 복사 시절 스크립트만 구버전으로 남아
하루치 분석이 빠진 적이 있다. **빠뜨려도 조용히 구버전이 돈다.**

### self-hosted 러너

`deploy.yml`(`push: release`, `workflow_dispatch`)이 맥미니의 self-hosted 러너에서
`./deploy/install.sh` 를 돌린다. 러너가 없으면 job이 queue에 머물다 24시간 후 취소되고,
그동안 배포는 수동이다.

**`deploy.yml`은 `install.sh`를 호출해야 한다.** 예전에는 `cp build/libs/stock-pulse.jar` 로
jar만 복사했다. 그러면 `analyze-report.sh`·`analysis-prompt.md`가 영구히 구버전으로 남는다 —
자동화가 수동 배포보다 나빠지는 셈이다. 배포 대상은 셋이고, 빠뜨려도 조용히 구버전이 돈다.

#### 이 레포는 PUBLIC 이다 — 지켜야 할 규칙

GitHub은 self-hosted 러너를 **private 레포에만** 쓰라고 권고한다. 포크한 누구든 PR을 열 수
있고, 그 PR이 self-hosted 러너에서 돌면 맥미니에서 임의 코드가 실행된다. 이 기계에는 KRX
인증키·텔레그램 토큰·`ANTHROPIC_AUTH_TOKEN`이 다 있다.

**`pull_request`로 트리거되는 job을 `self-hosted`에 올리지 않는다.** 예외 없다.

```
ci.yml      pull_request        → ubuntu-latest   ← 포크 PR 은 여기서만 돈다
deploy.yml  push:release, 수동  → self-hosted     ← 포크로 트리거할 수 없다
```

`pull_request` 이벤트가 쓰는 워크플로 정의는 **base 브랜치 것**이므로 포크가 바꿔 넣을 수는
없다. 위험은 우리가 스스로 `self-hosted`를 `pull_request` job에 붙이는 경우에만 생긴다.
`pull_request_target`·`issue_comment` 같은 트리거를 새로 도입할 때도 같은 규칙을 적용한다.

#### 등록 절차 (맥미니에서)

등록 토큰은 1시간 만료다. **채팅·문서에 붙여넣지 않는다** — 그 자리에서 생성해 바로 쓴다.

```bash
# 아키텍처를 직접 고르지 말고 판별한다. 자산 URL 도 API 에서 받아 쓴다(구성하지 않는다).
case "$(uname -m)" in arm64) A=osx-arm64 ;; x86_64) A=osx-x64 ;; *) echo "지원 안 함"; exit 1 ;; esac
mkdir -p ~/actions-runner && cd ~/actions-runner
curl -fsSL -o runner.tar.gz "$(gh api repos/actions/runner/releases/latest \
  --jq ".assets[] | select(.name | test(\"$A\")) | .browser_download_url")"
tar xzf runner.tar.gz

TOKEN="$(gh api -X POST repos/yshyuk/stock-pulse/actions/runners/registration-token --jq .token)"
./config.sh --url https://github.com/yshyuk/stock-pulse --token "$TOKEN" \
  --name macmini --labels self-hosted,macos --work _work --unattended

./svc.sh install && ./svc.sh start && ./svc.sh status
```

`gh` 가 없거나 로그인되어 있지 않으면 토큰은 웹에서 받는다 —
Settings → Actions → Runners → New self-hosted runner 화면에 표시된다.

`~/actions-runner/_work` 는 `~/Documents` 밖이라 TCC 문제가 없다. 서비스는 사용자
LaunchAgent(`actions.runner.*.plist`)로 등록되므로 `~/apps` 쓰기도 된다.

### plist는 코드가 건드리지 않는다
시크릿이 들어 있어 `install.sh`도 배포 워크플로도 덮어쓰지 않는다. 변경은 수동.
**토큰 재발급 시 batch·analyze 양쪽 plist를 모두 고쳐야 한다** — 한쪽만 고치면
그쪽 알림만 조용히 끊긴다.

---

## 외부 의존성 — 확인된 사실

| 대상 | 상태 |
|---|---|
| **KRX Open API** | `https://data-dbg.krx.co.kr/svc/apis/sto/{stk,ksq}_bydd_trd?basDd=YYYYMMDD`, 헤더 `AUTH_KEY`. 인증키 발급 + **API별 이용신청**이 둘 다 필요(키만으론 401 `Unauthorized API Call`). 1년 만료. 코스피 943 + 코스닥 1,822 |
| **KRX 포털** (`data.krx.co.kr`) | **차단됨** — 400 `LOGOUT`. 프로그램 접근 불가 |
| **네이버 실시세** | 동작하나 비공식. 2026-09 스키마 변경 이력 있음(`cd/nm/nv/pcv/aq/cr` → `itemCode/stockName/closePriceRaw/...`) |
| **claude CLI** | OAuth 세션은 만료된다. `claude setup-token` 장수명 토큰을 `ANTHROPIC_AUTH_TOKEN`으로. `ANTHROPIC_API_KEY`는 **API 종량제**라 구독을 쓰지 않는다 |

### KRX 날짜 규칙
장 마감 후 게시되므로 **당일 조회는 0건**이다. 06시 배치는 직전 거래일부터 데이터가
나올 때까지 거슬러 올라간다(공휴일을 시장 캘린더 없이 처리).

### 주말 처리
`EmptyRunPolicy`가 **주말이면 수집 결과와 무관하게 SKIP**한다. KRX 거슬러올라가기 때문에
토요일에도 금요일 데이터가 잡히는데, 그대로 저장하면 같은 세션이 토·일·월 세 번 쌓인다.
파생지표는 날짜가 아니라 **행 순서**로 N일 전을 세므로 20일 지표가 계속 밀린다.

공휴일은 여전히 중복이 생긴다(연 11회). 시장 캘린더 의존성을 피하려고 수용한 트레이드오프이며
`MarketClock`도 같은 판단이다.

---

## 2차 분석 (Claude Code CLI)

배치와 **분리된 launchd 작업**이다. 이유:
1. **과금 경로** — `claude` CLI는 Max 구독, jar 안의 `AnthropicReportAnalyzer`는 API 종량제
2. 프롬프트를 jar 재빌드 없이 고칠 수 있다
3. 분석이 실패해도 리포트는 이미 생성·발송된 뒤다

`STOCKPULSE_ANALYSIS_ENABLED`는 `false`로 둔다(API 경로 미사용).

### 프롬프트 작성 주의
자동화용 프롬프트에서 **금지문을 늘어놓으면 안전 분류기에 걸린다.** 실제로
`safeguards flagged this message` / `[reasoning_extraction]`로 거절당했다.

```
❌ 되묻지 마세요 / 본문만 출력하세요 / 근거를 밝히세요
   → "평소 행동 하지 마라 + 곧바로 결과만 내라" 조합이 탈옥 템플릿과 형태가 비슷하다

✅ "자동 배치가 텔레그램으로 보내는 글이고, 한 번에 완결되는 글이므로..."
   → 맥락을 설명하고 그에 맞는 형식을 요청한다. 요구 동작은 같다
```

`--model` / `--fallback-model`을 명시한다(거절은 모델별 분류기).

### 전송 방식 — 분석은 `.md` 파일로 보낸다

1차 리포트와 같다. 본문으로 보내면 텔레그램 4096자 / 디스코드 2000자에 걸려 한 편의 글이
토막나고, 조각낼 때 HTML 태그 경계까지 맞춰야 한다. 파일로 보내면 마크다운이 원문 그대로
남고 표도 살아 있다.

**실패 알림만 본문으로 보낸다.** 짧고, 즉시 보여야 하고, 첨부를 열게 만들면 안 된다.
그쪽은 `parse_mode=HTML`을 쓴다 — MarkdownV2는 이스케이프할 문자가 많아 한 글자만 놓쳐도
400이 나고, HTML은 `& < >` 셋만 막으면 된다. **분할은 줄 경계에서만** 한다.

### 전송 성공을 종료코드로 판단하지 않는다

`curl -s`는 **HTTP 400에도 exit 0**이다. 종료코드만 보면 텔레그램이 거절한 것을 성공으로
기록한다. 응답을 본다 — 텔레그램은 `"ok":true`, 디스코드는 HTTP 2xx.

분석을 만들어놓고 **아무 채널에도 못 보냈으면 exit 1**이다. 한쪽만 성공하면 0.
전달되지 않은 분석을 성공으로 기록하면 그날 분석이 사라진 사실을 아무도 모른다.

### 실패하면 한 번 더 시도한다

`ANALYSIS_ATTEMPTS`(기본 2). 2026-09-25 에 `exit=143`(타임아웃) + **출력 전무**로 그날
분석이 통째로 빠졌다. 성공한 날들은 38~67초에 끝났으니 느린 게 아니라 **멈춘 것**이고,
타임아웃을 늘려도 해결되지 않는다. 안전 분류기 거절도 확률적으로 발생한다.
두 경우 모두 **두 번째 시도가 정확히 듣는** 형태다. 리포트는 이미 발송된 뒤라 비용도 없다.

**출력이 전혀 없는 타임아웃은 타임아웃을 늘려서 고치지 않는다.** 멈춤을 의심한다.

### 알림이 어디로 갔는지 남긴다

09-25 로그는 `실패 (exit=143)` 에서 끝나 있어, **실패 알림이 전달됐는지 로그만으로 알 수
없었다.** 알림이 갔는지 모르는 상태는 알림이 없는 것과 다르지 않다. 이제 `notify()` 가
`실패 알림 전송: 텔레그램 디스코드` 또는 `어느 채널로도 보내지 못했습니다`를 남긴다.

### 채널 누락은 조용히 넘어가지 않는다

`DISCORD_WEBHOOK_URL`이 비어 있으면 로그를 남기고 건너뛴다. 실제로 "배치 리포트는 오는데
분석만 디스코드에 안 온다"는 증상으로 헤맸는데, 원인은 **analyze plist에 키를 안 넣은 것**
이었다. `install.sh`는 plist를 덮어쓰지 않으므로 레포 템플릿에 키를 추가해도 **운영 plist에는
자동으로 반영되지 않는다.**

---

## 형상관리

전역 `~/.claude/CLAUDE.md`의 `git-workflow` 규칙을 따른다.

```
feat|fix|chore/* → (dev PR) → develop → (release PR) → release + v{x.y.z} 태그
```

- `release`가 프로덕션 트렁크이자 GitHub 기본 브랜치. **직접 push 금지**
- `main`은 폐지됐다. 워크플로가 `main`을 참조하던 탓에 배포가 영영 트리거되지 않은 적이 있다
- CI(`ci.yml`)는 `release`·`develop` 대상 PR에서 돈다
- **릴리즈 PR(develop→release)은 merge commit으로 머지한다. squash 금지.**
  squash 하면 release가 develop의 커밋들을 커밋 하나로 받아 공통 조상이 어긋나고,
  **다음 릴리즈 PR이 통째로 충돌**한다. v0.4.0을 squash로 머지한 탓에 v0.5.0 PR에서
  4개 파일이 전부 충돌했다. (작업 브랜치→develop PR은 squash가 맞다)

  이미 갈라졌다면 release를 develop으로 되머지해 조상 관계만 잇는다. develop이
  내용상 상위 집합인지 **먼저 확인**하고, 트리가 바뀌지 않는지 검증한다:
  ```bash
  git diff develop origin/release        # release 에만 있는 내용이 없어야 한다
  git merge -s ours origin/release       # develop 트리는 그대로, 조상 관계만 연결
  ```
- `pull_request` 이벤트의 트리거 판정은 **base 브랜치**의 워크플로 정의를 쓴다.
  `ci.yml` 수정이 아직 base에 없으면 그 PR에는 CI가 돌지 않는다

### 커밋하지 않는 것
- `docs/superpowers/` (설계 문서) — 사용자 요청
- `docs/2026-09-09.md` (사건 당시 더미 리포트)

---

## 테스트

```bash
./gradlew clean test
```

**`clean`을 붙인다.** `UP-TO-DATE`로 건너뛰고 통과했다고 착각한 적이 있다.

**실행된 테스트 수를 확인한다.** `@SpringBootTest`에서 `stockpulse.batch.auto-run=false`를
빠뜨리면 `BatchRunner`가 `System.exit()`를 호출해 테스트 JVM이 죽는다. 그러면 나머지 테스트가
조용히 건너뛰어지고 **`BUILD SUCCESSFUL`이 난다.** 실제로 33개 중 5개만 돌았는데 성공으로
보고된 적이 있다.

```bash
# 클래스 수와 tests/failures/errors 합계
grep -ho 'tests="[0-9]*"\|failures="[0-9]*"\|errors="[0-9]*"' \
  build/test-results/test/TEST-*.xml \
  | awk -F'"' '{a[$1]+=$2} END {for (k in a) print k a[k]}'
ls build/test-results/test/TEST-*.xml | wc -l
```

---

## 현재 상태 (2026-09-22)

- **v0.5.0** 릴리즈·배포 완료. 맥미니에서 실행 확인:
  `2762 evaluated -> 546 matched -> 50 kept (cap 50)` + 텔레그램·디스코드 발송
- 2차 분석의 **`.md` 파일 전송 · 디스코드 전송** 확인 완료
- 테스트 163개 통과 (클래스 40개)
- DB에 09-14 ~ 09-18 스냅샷 (주말 유령 행 삭제 완료)

배포 전 맥미니는 **v0.3.3** 이었다. v0.3.4 가 릴리즈됐는데도 반영되지 않고 있었다.
러너가 0대라 배포가 수동이고, **배포를 빠뜨려도 조용히 구버전이 돈다.**

### 배포 호스트는 맥미니 하나다

삼성전자·SK하이닉스 두 종목짜리 샘플 리포트가 계속 온다는 제보를 쫓아간 결과,
**맥북 프로에 9월 7일자 배포(v0.2.0)가 남아 매일 아침 돌고 있었다.** 맥미니의 진짜
배치와 **같은 텔레그램 챗·같은 디스코드 웹훅**으로 보내고 있었다.

```
~/apps/stock-pulse/stock-pulse.jar   9월 7일, v0.2.0
plist 에 KRX_API_KEY 없음            → 더미 소스로 폴백
reports/ 15일치가 전부 1529 바이트   → 매일 같은 내용
```

처음 제보였던 `docs/2026-09-09.md`(삼성전자 78,900)도 여기서 나온 것이다.
맥미니만 고치는 동안 맥북이 계속 보내고 있었고, 양쪽 다 exit 0 이라 알아챌 수 없었다.
**조용한 실패의 전형이며, 이번엔 호스트 단위로 일어났다.**

- 맥북의 `com.stockpulse.batch`는 언로드하고 plist 는
  `~/Library/LaunchAgents.disabled/` 로 옮겼다 (시크릿이 있어 삭제하지 않음)
- **진단할 때 "어느 호스트인가"를 먼저 확정한다.** Claude Code 는 맥북에서 돌고
  프로덕션은 맥미니다. `scutil --get ComputerName` 으로 확인할 것
- 알림이 이상하면 **발신처가 하나라고 가정하지 않는다.** 같은 봇 토큰을 쓰는
  다른 호스트·다른 버전이 있을 수 있다

### 다음에 볼 것 (우선순위)
1. **스크리닝 상한 30 → 50** — 상한 30은 API 종량제 비용 때문이었는데 구독 전환으로
   제약이 사라졌다. 분석이 "474종목 매칭 중 30개만 표시, ±5~15% 구간은 안 보인다"고 지적.
   env 한 줄(`STOCKPULSE_SCREENING_MAX`)
2. self-hosted 러너 등록 (현재 0대, jar 수동 배포 중)
3. 임계값 조정 — 실데이터 2~4주 누적 후
4. `SnapshotService` 히스토리 조회 — 현재 종목별. 누적 후 재측정 필요
   (2,765 × 260 ≈ 72만 행/실행)
5. `BatchPipeline` 협력자 16개 — 분리 검토

---

## 자주 쓰는 명령

```bash
# 로컬 전종목 실행 (실 API)
KRX_ALL_ENABLED=true KRX_API_KEY=... STOCKPULSE_SCREENING_ENABLED=true \
  ./gradlew bootRun --args='--spring.profiles.active=local'

# 과거 날짜로
  ... --args='--spring.profiles.active=local --stockpulse.run-date=2026-09-18'

# Mac Mini 로그
tail -40 /Users/Shared/stock-pulse/logs/stdout.log
tail -40 /Users/Shared/stock-pulse/logs/analyze-stdout.log

# 수동 실행 — 반드시 launchctl 로. 셸에서 직접 돌리면 안 된다(아래 참조)
launchctl start com.stockpulse.batch
launchctl start com.stockpulse.analyze
```

### 수동 실행은 `launchctl start` 로 한다

시크릿은 plist 의 `EnvironmentVariables` 에만 있다. 그래서 스크립트를 셸에서 직접 부르면
**인증도 알림도 통째로 빠진다.**

```bash
bash ~/apps/stock-pulse/analyze-report.sh 2026-09-21
#  → Failed to authenticate: OAuth session expired    (ANTHROPIC_AUTH_TOKEN 없음)
#  → [analyze] 텔레그램 미설정 — 전송 생략              (TELEGRAM_BOT_TOKEN 없음)
```

둘 다 **환경 문제이지 배포 문제가 아니다.** 실제로 이 출력을 보고 토큰이 또 만료됐다고
오진할 뻔했다. 날짜를 지정해 다시 돌려야 한다면 plist 의 값을 그 셸에 먼저 넣어야 한다.

시크릿(KRX 인증키, 텔레그램 토큰, `ANTHROPIC_AUTH_TOKEN`)은 plist에만 있다.
코드·설정·문서 어디에도 넣지 않는다.
