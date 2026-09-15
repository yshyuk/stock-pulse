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
# 설치: README "2차 분석" 절 참조. launchd 로 배치보다 10분 뒤에 실행한다.

set -uo pipefail

# ── 설정 (launchd EnvironmentVariables 로 주입) ──────────────────────────────
REPORT_DIR="${STOCKPULSE_REPORT_DIR:-/Users/Shared/stock-pulse/reports}"
CLAUDE_BIN="${CLAUDE_BIN:-$HOME/.local/bin/claude}"
TIMEOUT_SEC="${ANALYSIS_TIMEOUT_SEC:-600}"
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
if [ ! -f "$REPORT" ]; then
    # 주말·공휴일에는 배치가 리포트를 만들지 않는다. 정상이므로 조용히 끝낸다.
    log "[analyze] 리포트 없음: $REPORT — 비거래일로 보고 종료"
    exit 0
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
( sleep "$TIMEOUT_SEC"; kill -0 "$CLAUDE_PID" 2>/dev/null && kill "$CLAUDE_PID" 2>/dev/null ) &
WATCHDOG_PID=$!

wait "$CLAUDE_PID"
STATUS=$?
kill "$WATCHDOG_PID" 2>/dev/null

ANALYSIS="$(cat "$OUT_FILE")"

if [ "$STATUS" -ne 0 ] || [ -z "$ANALYSIS" ]; then
    log "[analyze] 실패 (exit=$STATUS)"
    log "$(head -c 500 "$ERR_FILE")"
    send_telegram "⚠️ StockPulse 2차 분석 실패 ($RUN_DATE)
exit=$STATUS
$(head -c 500 "$ERR_FILE")"
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
