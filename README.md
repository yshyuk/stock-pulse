# StockPulse

새벽에 주식 데이터를 **수집 → 1차 가공 → 리포트 생성 → 전달(알림·파일·DB)** 후 종료하는
**배치형 서비스**입니다. Mac Mini(M4, macOS)에서 셀프 호스팅하며, 매일 새벽 한 번 실행되고
끝납니다. 상시 떠 있는 서버가 아닙니다.

리포트는 **객관적 지표만** 담습니다("이 종목이 좋다" 같은 판단은 하지 않음). 종목 해석/추천 등
2차 분석은 사람이 리포트를 받아 **Claude에 붙여넣어** 수행하는 반자동 워크플로입니다.
(미래에 배치가 직접 Claude API를 호출해 자동화할 수 있도록 확장 포인트만 비워 두었습니다.)

---

## 1. 실행 모델 (중요)

```
launchd (매일 06:00, OS가 스케줄링 담당)
   └─ java -jar stock-pulse.jar --spring.profiles.active=prod
        └─ Spring 부팅
             └─ BatchRunner (ApplicationRunner) 즉시 1회 실행
                  └─ BatchPipeline.run()
                       └─ 성공/실패 알림 → System.exit(0 또는 1)  → JVM 종료
```

- 앱 내부에 `@Scheduled` / `@EnableScheduling` **없음** — 스케줄은 전적으로 **launchd**가 담당.
- 앱은 기동되면 파이프라인을 **딱 한 번** 돌리고 종료(`System.exit`).
- 성공이든 실패든 **반드시 알림** → 조용한 실패 방지. 실패 시 종료코드 `1`.
- 웹 서버는 미래 조회 API를 위해 최소한으로만 살려둠(지금은 `/api/health`만).

## 2. 파이프라인 흐름

```
 collector            processor             report              analysis            storage              notification
┌──────────┐        ┌───────────┐        ┌───────────┐       ┌────────────┐       ┌───────────┐        ┌──────────────┐
│DataSource│  raw   │MetricProc.│ metric │ReportSvc  │ Report│ReportAnalyzer│Report│FileStore   │        │TelegramNotif.│
│ (dummy)  │ ─────▶ │(객관지표) │ ─────▶ │+Markdown  │ ────▶ │ (NoOp/TODO) │ ───▶ │DbStore     │ ─────▶ │DiscordNotif. │
└──────────┘        └───────────┘        └───────────┘       └────────────┘       └───────────┘        └──────────────┘
   수집                1차 가공             리포트 생성          2차 분석 확장점       파일+DB 저장            푸시 알림
```

오케스트레이션은 `batch/BatchPipeline`이 담당(실행 순서·예외 처리·성공/실패 통지),
1회 실행 후 종료는 `batch/BatchRunner`가 담당합니다.

## 3. 패키지 구조

```
com.stockpulse
├── StockPulseApplication        # 진입점 (배치형, @Scheduled 없음)
├── batch
│   ├── BatchRunner              # ApplicationRunner: 1회 실행 → System.exit
│   └── BatchPipeline            # 오케스트레이션 (collect→process→report→analyze→store→notify)
├── collector
│   ├── DataSource               # ★ 멀티소스 추상화 인터페이스
│   ├── CollectorService         # 등록된 DataSource 전부 취합
│   └── source/DummyDataSource   # 더미 구현 1개 (실제 DART/네이버/뉴스는 TODO)
├── processor
│   └── MetricProcessor          # 등락률·거래량 변화율 등 "객관 지표"만 계산 (판단 X)
├── report
│   ├── ReportRenderer           # ★ 출력 포맷 추상화 인터페이스
│   ├── ReportService            # 포맷별 렌더러 선택 + Report 생성
│   └── render/Markdown·Json     # MarkdownRenderer(기본) + JsonRenderer(ReportFormat.JSON)
├── analysis
│   ├── ReportAnalyzer           # ★ 2차 분석 확장 포인트 인터페이스
│   └── NoOpReportAnalyzer       # 기본 구현(아무것도 안 함). Claude 호출은 TODO
├── notification
│   ├── Notifier                 # ★ 채널 추상화 인터페이스 (둘 다 outbound webhook)
│   ├── NotificationService      # enabled 채널로 팬아웃
│   └── channel/Telegram·Discord # WebClient outbound 전송 (긴 리포트 분할/파일 첨부)
├── storage
│   ├── ReportStore              # ★ 저장소 추상화 인터페이스
│   ├── FileReportStore          # reports/YYYY-MM-DD.md
│   └── DbReportStore            # JPA 저장
├── domain                       # RawData, StockMetric, ReportModel, Report(@Entity), ReportRepository, ReportFormat
├── api                          # HealthController(/api/health), ReportController(리포트 조회)
└── config                       # WebClientConfig(WebClient·Clock), StockPulseProperties(yml 바인딩)
```

### 핵심 인터페이스 한눈에

| 인터페이스 | 위치 | 역할 | 현재 구현 | 확장 예정 |
|---|---|---|---|---|
| `DataSource` | collector | 데이터 소스 추상화 | `DummyDataSource` | DART·네이버·뉴스 |
| `ReportRenderer` | report | 출력 포맷 추상화 | `Markdown`,`Json` | 기타 포맷 |
| `Notifier` | notification | 알림 채널 추상화 | `Telegram`,`Discord` | Slack 등 |
| `ReportStore` | storage | 저장소 추상화 | `File`,`Db` | S3 등 |
| `ReportAnalyzer` | analysis | 2차 분석 확장점 | `NoOpReportAnalyzer` | Claude API 호출 |

## 4. 로컬 실행

기본 프로파일은 `local`이며, **인메모리 H2**를 사용하므로 MySQL 없이도 파이프라인이 끝까지 돕니다.

```bash
# 1) 빌드 + 테스트
./gradlew clean build

# 2) 실행 (1회 돌고 종료됨)
./gradlew bootRun
#   또는
java -jar build/libs/stock-pulse.jar
```

실행하면 `./reports/YYYY-MM-DD.md`에 리포트가 생성되고, 알림 채널이 설정돼 있지 않으면
"skipping" 로그만 남기고 정상 종료합니다(자격증명 없이도 동작).

> 참고: `bootRun`/`java -jar`는 배치 특성상 실행 직후 `System.exit`으로 종료됩니다(정상).
> 종료를 막고 컨텍스트만 띄우려면 테스트처럼 `--stockpulse.batch.auto-run=false`를 주세요.

## 5. 필요한 환경변수

`.env.example`를 복사해 `.env`로 채우거나, prod에서는 launchd plist의 `EnvironmentVariables`로 주입합니다.

| 변수 | 용도 | 비고 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | 프로파일 | prod (Mac Mini) / local |
| `DB_URL` | MySQL 접속 URL | prod |
| `DB_USERNAME` / `DB_PASSWORD` | DB 자격증명 | prod, **민감** |
| `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` | 텔레그램 알림 | **민감**, 없으면 채널 비활성 |
| `DISCORD_WEBHOOK_URL` | 디스코드 알림 | **민감**, 없으면 채널 비활성 |
| `STOCKPULSE_REPORT_DIR` | 리포트 저장 경로 | 기본 `./reports` |
| `STOCKPULSE_RUN_HOUR` | 실행 시각 참조값 | 실제 스케줄은 launchd가 소유 |
| `ANTHROPIC_API_KEY` | 미래 2차 분석용 | 지금은 미사용 |

모든 민감정보는 코드/yml에 하드코딩하지 않고 환경변수로만 주입합니다.

## 6. 리포트 저장 위치

- **파일**: `${STOCKPULSE_REPORT_DIR}/YYYY-MM-DD.md` (기본 `./reports/`, gitignore됨)
- **DB**: `report` 테이블 (`report_date`, `format`, `content`, `generated_at`)
- **알림**: 텔레그램/디스코드로 본문 전송(긴 리포트는 분할 또는 파일 첨부)

## 7. 조회 API (선택)

배치형이라 평소엔 실행 후 종료되지만, 서버를 띄워두면(`--stockpulse.batch.auto-run=false`)
DB에 저장된 리포트를 조회할 수 있습니다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/health` | 헬스 체크 |
| GET | `/api/reports/{date}` | 해당 날짜(YYYY-MM-DD) 리포트 |
| GET | `/api/reports/latest` | 가장 최근 리포트 |

```bash
java -jar build/libs/stock-pulse.jar --stockpulse.batch.auto-run=false &
curl localhost:8080/api/reports/latest
```

## 8. Mac Mini 배포 절차

### 8.1 self-hosted runner 등록
GitHub 저장소 → **Settings → Actions → Runners → New self-hosted runner** → macOS 안내대로 설치/실행.
러너는 Mac Mini에서 상시 동작하며, `main` 푸시 시 `.github/workflows/deploy.yml`을 실행합니다.

```bash
# (예) 러너를 로그인 시 자동 시작되는 서비스로 설치
cd ~/actions-runner
./svc.sh install
./svc.sh start
```

### 8.2 디렉토리 준비
```bash
mkdir -p ~/apps/stock-pulse
mkdir -p /Users/Shared/stock-pulse/{reports,logs}
```

### 8.3 launchd 설정
```bash
# 템플릿 복사
cp deploy/com.stockpulse.batch.plist ~/Library/LaunchAgents/

# 편집: REPLACE_ME_USERNAME(=$(whoami)) 경로, DB/텔레그램/디스코드 시크릿 채우기
nano ~/Library/LaunchAgents/com.stockpulse.batch.plist

# 로드 (재설정 시 unload 후 load)
launchctl unload ~/Library/LaunchAgents/com.stockpulse.batch.plist 2>/dev/null
launchctl load   ~/Library/LaunchAgents/com.stockpulse.batch.plist

# 즉시 한번 테스트 실행
launchctl start com.stockpulse.batch
tail -f /Users/Shared/stock-pulse/logs/stdout.log
```

- `StartCalendarInterval`로 매일 06:00 실행, `KeepAlive` 미사용(1회 실행 후 종료가 정상).
- 표준출력/에러는 `/Users/Shared/stock-pulse/logs/`에 기록.

### 8.4 절전(슬립) 대응 — 실행 누락 방지
Mac이 자고 있으면 launchd 스케줄이 **건너뛸 수 있습니다**. 두 가지 방법:

**(권장) pmset로 예약 기상** — 실행 2분 전에 깨우기:
```bash
sudo pmset repeat wakeorpoweron MTWRFSU 05:58:00
pmset -g sched            # 예약 확인
```

**또는 caffeinate로 실행 구간 동안 잠들지 않게:**
```bash
# plist의 java 호출을 caffeinate로 감싸면 실행 중 슬립 방지
# 예: /usr/bin/caffeinate -i /usr/bin/java -jar .../stock-pulse.jar --spring.profiles.active=prod
```
> 권장 조합: `pmset repeat`로 정시 전에 **깨우고**, 실행은 짧으니 그대로 끝나면 다시 잠듭니다.

### 8.5 배포 (자동)
`main`에 푸시하면 self-hosted runner에서:
1. checkout
2. `./gradlew clean build` (테스트 포함)
3. `build/libs/stock-pulse.jar` → `~/apps/stock-pulse/stock-pulse.jar` 교체
4. plist 템플릿 변경 여부 점검(시크릿이 든 설치본은 자동 덮어쓰지 않고 경고만)

배치형이라 **데몬 재시작이 필요 없습니다** — 다음 새벽 실행 때 새 jar가 자동으로 쓰입니다.

## 9. 2차 Claude 분석 — 권장 수동 워크플로

1. 새벽 배치가 끝나면 텔레그램/디스코드로 리포트가 도착합니다(또는 `reports/YYYY-MM-DD.md` 확인).
2. 리포트의 **종목 요약 표 + 종목별 상세**를 그대로 복사합니다.
3. Claude에 붙여넣고, 관심 종목/전략 관점에서 해석·비교를 요청합니다.
   - 리포트 자체에는 어떤 매수/매도 신호도 없으므로, 판단은 이 단계에서 이뤄집니다.
4. (미래 자동화) `ReportAnalyzer`에 `AnthropicReportAnalyzer`를 구현하고 `@Primary`로 등록하면
   배치가 리포트 생성 직후 `api.anthropic.com`을 직접 호출해 2차 분석까지 자동화할 수 있습니다.
   → `analysis/NoOpReportAnalyzer`의 TODO 참고. (Claude 모델 ID/엔드포인트는 연동 시점 최신 문서 확인.)

## 10. 기술 스택

Java 21 · Spring Boot 3.3.x · Gradle(Kotlin DSL) · Spring Web(최소) · Spring Data JPA ·
MySQL(prod) / H2(local) · Lombok · WebClient(외부 API).
