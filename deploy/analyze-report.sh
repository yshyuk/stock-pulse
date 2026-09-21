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
# 모델을 명시한다. 안전 분류기가 확률적으로 요청을 거절하는 경우가 있어
# (refusal, 예: reasoning_extraction) 폴백 모델을 함께 지정한다.
MODEL="${ANALYSIS_MODEL:-sonnet}"
FALLBACK_MODEL="${ANALYSIS_FALLBACK_MODEL:-opus}"
# 배치가 끝날 때까지 기다릴 시간. 맥이 예약 시각에 자고 있으면 배치가 늦게 끝나는데,
# 분석이 고정 시각에 한 번만 보고 포기하면 그날 분석이 통째로 빠진다(실제로 겪음).
REPORT_WAIT_SEC="${ANALYSIS_REPORT_WAIT_SEC:-1800}"
BOT_TOKEN="${TELEGRAM_BOT_TOKEN:-}"
CHAT_ID="${TELEGRAM_CHAT_ID:-}"
DISCORD_WEBHOOK_URL="${DISCORD_WEBHOOK_URL:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROMPT_FILE="${ANALYSIS_PROMPT_FILE:-$SCRIPT_DIR/analysis-prompt.md}"

RUN_DATE="${1:-$(date +%F)}"
REPORT="$REPORT_DIR/$RUN_DATE.md"

log() { echo "$(date '+%Y-%m-%dT%H:%M:%S%z') $*"; }

# 마크다운을 텔레그램 HTML 로 바꾼다.
#
# 왜 MarkdownV2 가 아니라 HTML 인가: MarkdownV2 는 _*[]()~`>#+-=|{}.! 를 전부 이스케이프해야
# 해서 한 글자만 놓쳐도 400 이 난다. HTML 은 & < > 셋만 막으면 되고, 텔레그램이 표나 ## 헤더를
# 지원하지 않으므로 어차피 변환이 필요하다.
to_telegram_html() {
    python3 - "$1" <<'PYEOF'
import html, re, sys

text = sys.argv[1]
out = []
for line in text.split("\n"):
    # 표 구분선(|---|---|)은 텔레그램에서 의미가 없다.
    if re.fullmatch(r"\s*\|[\s|:-]+\|\s*", line):
        continue
    line = html.escape(line, quote=False)
    line = re.sub(r"^\s*#{1,6}\s*(.+)$", r"<b>\1</b>", line)      # ## 헤더 → 굵게
    line = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", line)           # **굵게**
    line = re.sub(r"`([^`]+)`", r"<code>\1</code>", line)         # `코드`
    out.append(line)
print("\n".join(out))
PYEOF
}

# 텔레그램은 메시지당 4096자 제한이 있다. 줄 경계에서만 자른다 — 태그 한가운데를 자르면
# 나머지 조각이 깨진 HTML 이 되어 400 이 난다.
send_telegram() {
    local text="$1"
    if [ -z "$BOT_TOKEN" ] || [ -z "$CHAT_ID" ]; then
        log "[analyze] 텔레그램 미설정 — 전송 생략"
        return 0
    fi
    local html chunk="" line
    html="$(to_telegram_html "$text")"

    _tg_post() {
        [ -z "$1" ] && return 0
        /usr/bin/curl -s --max-time 30 -X POST \
            "https://api.telegram.org/bot${BOT_TOKEN}/sendMessage" \
            -d "chat_id=${CHAT_ID}" -d "parse_mode=HTML" \
            --data-urlencode "text=$1" >/dev/null \
            || log "[analyze] 텔레그램 전송 실패"
    }

    while IFS= read -r line; do
        if [ ${#chunk} -gt 0 ] && [ $(( ${#chunk} + ${#line} + 1 )) -gt 3800 ]; then
            _tg_post "$chunk"
            chunk="$line"
        else
            chunk="${chunk:+$chunk$'\n'}$line"
        fi
    done <<< "$html"
    _tg_post "$chunk"
}

# 디스코드는 마크다운을 그대로 받으므로 변환이 필요 없다. 제한은 2000자.
send_discord() {
    local text="$1"
    if [ -z "$DISCORD_WEBHOOK_URL" ]; then
        return 0
    fi
    local chunk="" line
    _dc_post() {
        [ -z "$1" ] && return 0
        /usr/bin/curl -s --max-time 30 -H "Content-Type: application/json" \
            -d "$(python3 -c 'import json,sys; print(json.dumps({"content": sys.argv[1]}))' "$1")" \
            "$DISCORD_WEBHOOK_URL" >/dev/null \
            || log "[analyze] 디스코드 전송 실패"
    }
    while IFS= read -r line; do
        if [ ${#chunk} -gt 0 ] && [ $(( ${#chunk} + ${#line} + 1 )) -gt 1900 ]; then
            _dc_post "$chunk"
            chunk="$line"
        else
            chunk="${chunk:+$chunk$'\n'}$line"
        fi
    done <<< "$text"
    _dc_post "$chunk"
}

# 설정된 모든 채널로 보낸다. 한쪽이 실패해도 다른 쪽은 시도한다.
notify() {
    send_telegram "$1"
    send_discord "$1"
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
    notify "⚠️ StockPulse 2차 분석 실패 ($RUN_DATE)
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
    notify "⚠️ StockPulse 2차 분석 실패 ($RUN_DATE)
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
    --model "$MODEL" --fallback-model "$FALLBACK_MODEL" \
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
        *safeguards*|*reasoning_extraction*)
            HINT="
안전 분류기가 요청을 거절했습니다(확률적으로 발생합니다). ANALYSIS_MODEL 을 다른 모델로
바꾸거나, deploy/analysis-prompt.md 의 표현을 다듬어 보세요."
            ;;
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
    notify "⚠️ StockPulse 2차 분석 실패 ($RUN_DATE)
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

notify "🔍 StockPulse 2차 분석 ($RUN_DATE)

$ANALYSIS"

log "[analyze] 종료"
