#!/bin/bash
#
# 2차 분석: 오늘 리포트를 Claude Code CLI 로 해석해 텔레그램으로 보낸다.
#
# 왜 배치(jar)에 넣지 않고 분리했는가
#   1. 과금 경로가 다르다. jar 안의 AnthropicReportAnalyzer 는 api.anthropic.com 을
#      호출해 토큰당 과금되지만, claude CLI 는 Max 구독을 쓴다.
#   2. 프롬프트를 jar 재빌드 없이 고칠 수 있다. 2차 분석은 프롬프트를 계속 다듬게 된다.
#   3. 분석이 실패해도 리포트는 이미 생성·발송된 뒤다. 배치의 신뢰도를 깎지 않는다.
#
# 인증: claude 의 OAuth 세션은 만료되며, 만료되면 아무 설명 없이 exit 1 로 죽는다.
#       `claude setup-token` 으로 장수명 토큰을 발급해 ANTHROPIC_AUTH_TOKEN 으로 준다.
#       ANTHROPIC_API_KEY 를 쓰면 안 된다 — 그쪽은 API 종량제라 구독을 쓰지 않는다.
#
# 설치: README "2차 분석" 절 참조. launchd 로 배치보다 뒤에 실행한다.

set -uo pipefail

# ── 설정 (launchd EnvironmentVariables 로 주입) ──────────────────────────────
REPORT_DIR="${STOCKPULSE_REPORT_DIR:-/Users/Shared/stock-pulse/reports}"
CLAUDE_BIN="${CLAUDE_BIN:-$HOME/.local/bin/claude}"
TIMEOUT_SEC="${ANALYSIS_TIMEOUT_SEC:-600}"
# 배치가 끝날 때까지 기다릴 시간. 맥이 예약 시각에 자고 있으면 배치가 늦게 끝나는데,
# 분석이 고정 시각에 한 번만 보고 포기하면 그날 분석이 통째로 빠진다(실제로 겪음).
REPORT_WAIT_SEC="${ANALYSIS_REPORT_WAIT_SEC:-1800}"
BOT_TOKEN="${TELEGRAM_BOT_TOKEN:-}"
CHAT_ID="${TELEGRAM_CHAT_ID:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROMPT_FILE="${ANALYSIS_PROMPT_FILE:-$SCRIPT_DIR/analysis-prompt.md}"

RUN_DATE="${1:-$(date +%F)}"
REPORT="$REPORT_DIR/$RUN_DATE.md"

log() { echo "$(date '+%Y-%m-%dT%H:%M:%S%z') $*"; }

# 텔레그램은 메시지당 4096자 제한이 있다. parse_mode 없이 평문으로 잘라 보낸다 —
# 마크다운을 중간에서 자르면 태그가 깨져 API 가 400 을 돌려주기 때문이다.
send_telegram() {
    local text="$1"
    if [ -z "$BOT_TOKEN" ] || [ -z "$CHAT_ID" ]; then
        log "[analyze] 텔레그램 미설정 — 전송 생략"
        return 0
    fi
    local chunk
    while [ -n "$text" ]; do
        chunk="${text:0:3800}"
        text="${text:3800}"
        /usr/bin/curl -s --max-time 30 -X POST \
            "https://api.telegram.org/bot${BOT_TOKEN}/sendMessage" \
            -d "chat_id=${CHAT_ID}" \
            --data-urlencode "text=${chunk}" >/dev/null \
            || log "[analyze] 텔레그램 전송 실패"
    done
}

# ── 전제 조건 ───────────────────────────────────────────────────────────────
is_weekend() {
    local dow
    dow="$(date -j -f "%Y-%m-%d" "$1" "+%u" 2>/dev/null || echo 0)"
    [ "$dow" = "6" ] || [ "$dow" = "7" ]
}

if [ ! -f "$REPORT" ]; then
    if is_weekend "$RUN_DATE"; then
        # 주말에는 배치도 리포트를 만들지 않는다. 기다릴 이유가 없다.
        log "[analyze] 리포트 없음: $REPORT — 주말이므로 종료"
        exit 0
    fi
    # 평일인데 없다면 배치가 아직 안 끝났을 수 있다. 고정 시각에 한 번 보고 포기하면
    # 맥이 늦게 깨어난 날의 분석이 통째로 빠진다.
    log "[analyze] 리포트 대기 중 (최대 ${REPORT_WAIT_SEC}s): $REPORT"
    waited=0
    while [ ! -f "$REPORT" ] && [ "$waited" -lt "$REPORT_WAIT_SEC" ]; do
        sleep 30
        waited=$((waited + 30))
    done
    if [ ! -f "$REPORT" ]; then
        # 공휴일이거나 배치가 실패한 것. 배치 실패는 배치 쪽이 이미 알린다.
        log "[analyze] ${REPORT_WAIT_SEC}s 대기 후에도 리포트 없음 — 공휴일이거나 배치 실패로 보고 종료"
        exit 0
    fi
    log "[analyze] 리포트 확인됨 (${waited}s 대기)"
fi

if [ ! -x "$CLAUDE_BIN" ]; then
    log "[analyze] claude CLI 를 찾을 수 없음: $CLAUDE_BIN"
    send_telegram "⚠️ StockPulse 2차 분석 실패 ($RUN_DATE)
claude CLI 를 찾을 수 없습니다: $CLAUDE_BIN
CLAUDE_BIN 환경변수를 확인하세요."
    exit 1
fi

if [ ! -f "$PROMPT_FILE" ]; then
    log "[analyze] 프롬프트 파일 없음: $PROMPT_FILE"
    exit 1
fi

# claude 는 현재 작업 디렉터리를 읽을 수 있어야 시작한다. 읽지 못하면
# "An unknown error occurred" 만 남기고 죽어 원인 파악이 어렵다 — 먼저 확인한다.
if ! ls . >/dev/null 2>&1; then
    log "[analyze] 현재 디렉터리를 읽을 수 없음: $(pwd) — plist 의 WorkingDirectory 를 확인하세요"
    send_telegram "⚠️ StockPulse 2차 분석 실패 ($RUN_DATE)
작업 디렉터리를 읽을 수 없습니다: $(pwd)"
    exit 1
fi

# ── 분석 ────────────────────────────────────────────────────────────────────
log "[analyze] $REPORT 분석 시작 (timeout ${TIMEOUT_SEC}s)"

OUT_FILE="$(mktemp)"
ERR_FILE="$(mktemp)"
trap 'rm -f "$OUT_FILE" "$ERR_FILE"' EXIT

# --allowed-tools "" : 도구 없이 순수 텍스트 분석만. 예측 가능하고 빠르다.
#   DB 이력까지 보게 하려면 여기에 Read·Bash 를 열면 된다(히스토리가 쌓인 뒤).
"$CLAUDE_BIN" -p "$(cat "$PROMPT_FILE")" --allowed-tools "" \
    < "$REPORT" > "$OUT_FILE" 2> "$ERR_FILE" &
CLAUDE_PID=$!

# macOS 기본 환경에는 timeout(1) 이 없어 워치독을 직접 둔다.
# disown 은 노이즈 제거용이다 — 워치독을 kill 할 때 셸이 "Terminated: 15" 를 찍는데,
# 정상 동작인데도 진짜 에러처럼 보여 로그 판독을 방해한다.
# >/dev/null 2>&1 이 중요하다. 이게 없으면 워치독이 스크립트의 stdout 을 물고 있어
# 본체가 끝나도 파이프가 닫히지 않는다 — 작업이 타임아웃 시간만큼(기본 10분) 매달린 것처럼 보인다.
( sleep "$TIMEOUT_SEC"; kill -0 "$CLAUDE_PID" 2>/dev/null && kill "$CLAUDE_PID" 2>/dev/null ) \
    >/dev/null 2>&1 &
WATCHDOG_PID=$!
disown "$WATCHDOG_PID" 2>/dev/null || true

wait "$CLAUDE_PID"
STATUS=$?
kill "$WATCHDOG_PID" 2>/dev/null

ANALYSIS="$(cat "$OUT_FILE")"

if [ "$STATUS" -ne 0 ] || [ -z "$ANALYSIS" ]; then
    # claude 는 인증 실패를 STDERR 가 아니라 STDOUT 으로 낸다. 예전에는 stderr 만 남겨서
    # "OAuth session expired" 라는 결정적 단서를 로그에도 알림에도 남기지 못했다.
    DETAIL="$(head -c 500 "$OUT_FILE")"
    [ -z "$DETAIL" ] && DETAIL="$(head -c 500 "$ERR_FILE")"
    [ -z "$DETAIL" ] && DETAIL="(출력 없음)"

    HINT=""
    case "$DETAIL" in
        *authenticate*|*OAuth*|*Unauthorized*)
            HINT="
인증 문제로 보입니다. Mac Mini 에서 \`claude setup-token\` 으로 토큰을 재발급하고
plist 의 ANTHROPIC_AUTH_TOKEN 을 갱신하세요."
            ;;
    esac

    if [ "$STATUS" -eq 143 ]; then
        HINT="
${TIMEOUT_SEC}s 안에 끝나지 않아 중단했습니다. ANALYSIS_TIMEOUT_SEC 을 늘리거나
claude 가 응답하고 있는지 확인하세요."
    fi

    log "[analyze] 실패 (exit=$STATUS): $DETAIL"
    send_telegram "⚠️ StockPulse 2차 분석 실패 ($RUN_DATE)
exit=$STATUS
$DETAIL$HINT"
    exit 1
fi

log "[analyze] 완료 — ${#ANALYSIS}자"

# ── 저장 + 발송 ─────────────────────────────────────────────────────────────
ANALYSIS_FILE="$REPORT_DIR/$RUN_DATE.analysis.md"
{
    echo "# StockPulse 2차 분석 — $RUN_DATE"
    echo
    echo "> Claude Code CLI 로 생성. 원본 리포트: $(basename "$REPORT")"
    echo
    echo "$ANALYSIS"
} > "$ANALYSIS_FILE"
log "[analyze] 저장: $ANALYSIS_FILE"

send_telegram "🔍 StockPulse 2차 분석 ($RUN_DATE)

$ANALYSIS"

log "[analyze] 종료"
