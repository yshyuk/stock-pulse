#!/bin/bash
#
# 2차 분석: 오늘 리포트를 Claude Code CLI 로 해석해 텔레그램·디스코드로 보낸다.
#
# 결과는 .md 파일로 보낸다(1차 리포트와 같은 방식). 본문으로 보내면 텔레그램 4096자 /
# 디스코드 2000자 제한에 걸려 한 편의 글이 토막나고, 조각낼 때 태그 경계까지 신경 써야 한다.
# 실패 알림만 본문으로 보낸다 — 짧고, 첨부를 열게 만들면 안 된다.
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
# 한 번의 시도에 이 이상 기다리지 않는다.
#
# 왜 600 이 아니라 120 인가: 성공한 실행 20여 건이 모두 20~40초에 끝났고, 멈춴 실행은
# 600초를 기다려도 **한 번도 회복되지 않았다.** 멈춤의 정체는 이미 연결된 TLS 소켓을 물고
# 응답을 기다리는 상태다(`sample` 로 확인: 메인 스레드 kevent64 + ESTABLISHED 소켓 3개).
# 상류에서 응답이 오지 않는 것이므로 더 기다려서 해결되지 않는다. 오래 기다리면 재시도만
# 늦어진다 — 실제로 600초 × 2회 = 20분을 쓰고 아무것도 얻지 못한 날이 이틀 있었다.
TIMEOUT_SEC="${ANALYSIS_TIMEOUT_SEC:-120}"
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
# 테스트에서 로컬 서버로 돌려받기 위한 것. 운영에서는 건드리지 않는다.
TELEGRAM_API_BASE="${TELEGRAM_API_BASE:-https://api.telegram.org}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROMPT_FILE="${ANALYSIS_PROMPT_FILE:-$SCRIPT_DIR/analysis-prompt.md}"

RUN_DATE="${1:-$(date +%F)}"
REPORT="$REPORT_DIR/$RUN_DATE.md"
ANALYSIS_FILE="$REPORT_DIR/$RUN_DATE.analysis.md"

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
        return 1
    fi
    local html chunk="" line failed=0
    html="$(to_telegram_html "$text")"

    _tg_post() {
        [ -z "$1" ] && return 0
        local resp
        resp="$(/usr/bin/curl -s --max-time 30 -X POST \
            "${TELEGRAM_API_BASE}/bot${BOT_TOKEN}/sendMessage" \
            -d "chat_id=${CHAT_ID}" -d "parse_mode=HTML" \
            --data-urlencode "text=$1")"
        case "$resp" in
            *'"ok":true'*) ;;
            *) log "[analyze] 텔레그램 전송 실패: $(printf '%.200s' "$resp")"; failed=1 ;;
        esac
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
    return $failed
}

# 디스코드는 마크다운을 그대로 받으므로 변환이 필요 없다. 제한은 2000자.
send_discord() {
    local text="$1"
    if [ -z "$DISCORD_WEBHOOK_URL" ]; then
        # 조용히 건너뛰지 않는다. 실제로 plist 에 DISCORD_WEBHOOK_URL 을 넣지 않은 채
        # "디스코드에 분석만 안 온다" 는 증상으로 한참을 헤맸다. 배치는 보내는데
        # 분석만 안 오면 코드를 의심하게 되는데, 원인은 환경변수 누락이었다.
        log "[analyze] 디스코드 미설정(DISCORD_WEBHOOK_URL) — 전송 생략"
        return 1
    fi
    local chunk="" line failed=0
    _dc_post() {
        [ -z "$1" ] && return 0
        local code
        code="$(/usr/bin/curl -s -o /dev/null -w '%{http_code}' --max-time 30 \
            -H "Content-Type: application/json" \
            -d "$(python3 -c 'import json,sys; print(json.dumps({"content": sys.argv[1]}))' "$1")" \
            "$DISCORD_WEBHOOK_URL")"
        case "$code" in
            2*) ;;
            *) log "[analyze] 디스코드 전송 실패 (HTTP $code)"; failed=1 ;;
        esac
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
    return $failed
}

# ── 파일 전송 ───────────────────────────────────────────────────────────────
#
# 분석 결과는 본문이 아니라 .md 파일로 보낸다. 배치의 1차 리포트와 같은 방식이다.
#
# 왜 본문이 아닌가
#   - 텔레그램 4096자 / 디스코드 2000자 제한 때문에 긴 분석이 여러 조각으로 쪼개진다.
#     읽는 쪽에서는 한 편의 글이 토막나 보인다.
#   - 조각내려면 HTML 태그 경계를 신경 써야 하고, 그 변환 자체가 깨질 여지를 만든다.
#   - 파일로 보내면 마크다운이 원문 그대로 남는다. 표도 살아 있고 나중에 다시 열어보기 쉽다.
#
# 실패 알림은 여전히 본문으로 보낸다 — 짧고, 즉시 보여야 하고, 첨부를 열게 만들면 안 된다.
send_telegram_file() {
    local path="$1" caption="$2" resp
    if [ -z "$BOT_TOKEN" ] || [ -z "$CHAT_ID" ]; then
        log "[analyze] 텔레그램 미설정 — 파일 전송 생략"
        return 1
    fi
    # 응답 본문을 본다. curl 은 HTTP 400 에도 exit 0 이라, 종료코드만 보면 텔레그램이
    # 거절한 것을 성공으로 기록하게 된다.
    resp="$(/usr/bin/curl -s --max-time 60 -X POST \
        "${TELEGRAM_API_BASE}/bot${BOT_TOKEN}/sendDocument" \
        -F "chat_id=${CHAT_ID}" \
        -F "caption=${caption}" \
        -F "document=@${path};type=text/markdown")"
    case "$resp" in
        *'"ok":true'*) log "[analyze] 텔레그램 전송: $(basename "$path")" ;;
        *) log "[analyze] 텔레그램 파일 전송 실패: $(printf '%.200s' "$resp")"; return 1 ;;
    esac
}

send_discord_file() {
    local path="$1" content="$2" code
    if [ -z "$DISCORD_WEBHOOK_URL" ]; then
        log "[analyze] 디스코드 미설정(DISCORD_WEBHOOK_URL) — 파일 전송 생략"
        return 1
    fi
    # 필드명은 배치의 DiscordNotifier 와 맞춘다(files[0]).
    code="$(/usr/bin/curl -s -o /dev/null -w '%{http_code}' --max-time 60 \
        -F "content=${content}" \
        -F "files[0]=@${path};type=text/markdown" \
        "$DISCORD_WEBHOOK_URL")"
    case "$code" in
        2*) log "[analyze] 디스코드 전송: $(basename "$path")" ;;
        *) log "[analyze] 디스코드 파일 전송 실패 (HTTP $code)"; return 1 ;;
    esac
}

# 설정된 모든 채널로 보낸다. 한쪽이 실패해도 다른 쪽은 시도한다.
#
# 어디로 갔는지 반드시 남긴다. 2026-09-25 분석이 타임아웃으로 실패했을 때 로그가
# "실패 (exit=143)" 에서 끝나 있어, 실패 알림이 실제로 전달됐는지 로그만으로는
# 알 수 없었다. 알림이 갔는지 모르는 상태는 알림이 없는 것과 다르지 않다.
notify() {
    local sent=""
    send_telegram "$1" && sent="${sent}텔레그램 "
    send_discord "$1" && sent="${sent}디스코드 "
    if [ -n "$sent" ]; then
        log "[analyze] 실패 알림 전송: ${sent% }"
    else
        log "[analyze] 실패 알림을 어느 채널로도 보내지 못했습니다"
    fi
}

# 어느 한 채널이라도 성공하면 0. 전부 실패하면 1 — 분석을 만들어놓고 아무에게도
# 전달하지 못한 것을 성공으로 기록하면, 그날 분석이 사라진 사실을 아무도 모른다.
notify_file() {
    local path="$1" caption="$2" ok=1
    send_telegram_file "$path" "$caption" && ok=0
    send_discord_file "$path" "$caption" && ok=0
    return $ok
}

# ── 전제 조건 ───────────────────────────────────────────────────────────────

# 이미 오늘 분석이 있으면 아무 일도 하지 않는다.
#
# 왜: 멈춤은 **구간 단위**다. 2026-10-05 에 120초 × 3회(8분 30초)를 sonnet·opus 양쪽으로
# 썼는데 세 번 다 멈췄다. 09-25·09-28 도 연속 실패였다. 반면 되는 날은 1차 시도가
# 18~40초에 끝난다. 즉 **몇 분 안에 다시 묻는 것은 거의 무의미하고, 시간을 벌려야 한다.**
#
# 그래서 launchd 에 여러 시각(06:10 / 07:10 / 09:10)을 등록하고, 이 가드로 중복을 막는다.
# 1차에 성공하면 나머지 호출은 즉시 끝나고 아무것도 보내지 않는다.
#
# ANALYSIS_FORCE=1 을 주면 이미 있어도 다시 만든다(수동 재생성용).
if [ -s "$ANALYSIS_FILE" ] && [ "${ANALYSIS_FORCE:-0}" != "1" ]; then
    log "[analyze] 이미 분석이 있습니다: $ANALYSIS_FILE — 종료 (다시 만들려면 ANALYSIS_FORCE=1)"
    exit 0
fi

# 주입된 경로 환경변수의 위생을 먼저 본다.
#
# 왜: analyze plist 의 HOME 이 "/Users/yshyuk " 였다 — 끝에 공백 한 칸. launchd 는 그 값을
# 그대로 넘기고, claude 는 $HOME/.claude 에 설정·인증 상태를 두므로 엉뚱한 디렉터리를 보게
# 된다. 이런 값은 "파일이 없다" 처럼 드러나지 않고 **출력 한 글자 없는 멈춤**으로 나타나서,
# 이틀치 분석을 잃고 나서야 cat 오류 메시지의 경로에 낀 공백을 보고 알아챘다.
#
# 공백을 조용히 잘라내지 않는다. 잘라내면 잘못된 plist 가 그대로 남아 다음 사람이 또 겪는다.
check_env_path() {
    local var="$1" val="${!1:-}" trimmed
    [ -z "$val" ] && return 0
    trimmed="${val#"${val%%[![:space:]]*}"}"     # 앞쪽 공백 제거
    trimmed="${trimmed%"${trimmed##*[![:space:]]}"}"  # 뒤쪽 공백 제거
    if [ "$val" != "$trimmed" ]; then
        log "[analyze] $var 앞뒤에 공백이 있습니다: '$val'"
        return 1
    fi
    return 0
}

env_problem=""
home_clean=true
for v in HOME CLAUDE_BIN STOCKPULSE_REPORT_DIR ANALYSIS_PROMPT_FILE; do
    if ! check_env_path "$v"; then
        env_problem="${env_problem}${v} "
        [ "$v" = HOME ] && home_clean=false
    fi
done
# 공백 문제로 이미 잡힌 HOME 을 두 번 세지 않는다.
if $home_clean && [ -n "$HOME" ] && [ ! -d "$HOME" ]; then
    log "[analyze] HOME 이 실재하는 디렉터리가 아닙니다: '$HOME'"
    env_problem="${env_problem}HOME "
fi
if [ -n "$env_problem" ]; then
    log "[analyze] 환경변수가 잘못되어 중단합니다 — plist 의 EnvironmentVariables 를 고치세요"
    notify "⚠️ StockPulse 2차 분석 중단 ($RUN_DATE)

환경변수 값이 잘못되었습니다: ${env_problem% }

plist 의 EnvironmentVariables 에 앞뒤 공백이 섞였거나 경로가 실재하지 않습니다.
이대로 두면 claude 가 출력 없이 멈춥니다(실제로 그렇게 이틀치 분석을 잃었습니다).
확인: plutil -p ~/Library/LaunchAgents/com.stockpulse.analyze.plist"
    exit 1
fi

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
CLAUDE_BIN 환경변수를 확인하세요.

버전을 고정해 둔 경우, 업데이터가 그 버전을 정리했을 수 있습니다.
설치된 버전 목록: ls -lt ~/.local/share/claude/versions/"
    exit 1
fi

# 어느 바이너리로 돌렸는지 매 실행 기록한다.
#
# 왜: claude 는 저절로 업데이트된다. 실제로 2026-09-15 → 09-24 → 09-27 → 10-02 네 번
# 바뀌었고(2.1.272 → 281 → 283 → 287), `~/.local/bin/claude` 는 심볼릭 링크라 대화형
# 세션이 업데이트하면 무인 배치가 쓰는 바이너리도 같이 바뀐다. 예고 없이 바뀌는 의존성을
# 디버깅하는 데 이틀을 썼다. 버전을 로그와 분석 파일에 남겨두면 "그날 무엇이 돌았는가" 를
# 나중에도 알 수 있다.
#
# --version 호출에 워치독을 두고 DISABLE_AUTOUPDATER 를 준다. 이 호출이 업데이트 확인을
# 트리거해 매달리면 본론 전에 죽는다 — 이 스크립트의 역사가 전부 그런 멈춤이었다.
CLAUDE_VERSION="(확인 실패)"
_vf="$(mktemp)"
( DISABLE_AUTOUPDATER=1 "$CLAUDE_BIN" --version >"$_vf" 2>&1 ) &
_vp=$!
( sleep 20; kill -0 "$_vp" 2>/dev/null && kill "$_vp" 2>/dev/null ) >/dev/null 2>&1 &
_vw=$!
disown "$_vw" 2>/dev/null || true
if wait "$_vp"; then
    CLAUDE_VERSION="$(head -1 "$_vf" | tr -d '\r')"
fi
kill "$_vw" 2>/dev/null
rm -f "$_vf"
log "[analyze] claude 버전: $CLAUDE_VERSION  ($CLAUDE_BIN)"

# 기대 버전을 지정해 두면 바뀌었을 때 로그로 알린다. 실패시키지는 않는다 —
# 버전이 올라간 것만으로 그날 분석을 버릴 이유는 없다.
if [ -n "${ANALYSIS_CLAUDE_VERSION:-}" ]; then
    case "$CLAUDE_VERSION" in
        *"$ANALYSIS_CLAUDE_VERSION"*) ;;
        *) log "[analyze] 주의: 기대 버전과 다릅니다 — 기대 '$ANALYSIS_CLAUDE_VERSION', 실제 '$CLAUDE_VERSION'" ;;
    esac
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
OUT_FILE="$(mktemp)"
ERR_FILE="$(mktemp)"
CLEAN_FILE="$(mktemp)"
DROPPED_FILE="$(mktemp)"
trap 'rm -f "$OUT_FILE" "$ERR_FILE" "$CLEAN_FILE" "$DROPPED_FILE"' EXIT

# claude 가 stdout 머리에 끼워 넣는 CLI 경고를 걷어낸다.
#
# 왜: 실제로 이 줄이 분석 본문 맨 앞에 들어갔다.
#   ⚠ claude.ai connectors are disabled because ANTHROPIC_API_KEY or another auth source is set ...
# 스크립트는 stdout 전체를 분석 결과로 보고 .md 로 저장해 텔레그램·디스코드로 보낸다.
# 리포트에 실행 환경 잡음이 섞이는 건 이 프로젝트 원칙에 어긋난다.
#
# **조용히 버리지 않는다.** 걷어낸 줄은 로그에 남긴다 — 경고 자체가 진단 단서인 경우가 있다.
# 실제로 이 줄이 "멈추기 전에 출력은 한다" 는 근거가 됐다.
#
# 머리 부분만 본다. 본문 중간의 ⚠ 는 분석이 쓴 것일 수 있으므로 건드리지 않는다.
strip_cli_warnings() {
    python3 - "$OUT_FILE" "$CLEAN_FILE" "$DROPPED_FILE" <<'PYEOF'
import pathlib, sys
src, clean, dropped = (pathlib.Path(a) for a in sys.argv[1:4])
lines = src.read_text(errors="replace").split("\n")
drop, i = [], 0
while i < len(lines):
    s = lines[i].strip()
    if s.startswith("⚠"):          # ⚠ 로 시작하는 경고 줄
        drop.append(s)
        i += 1
        continue
    if s == "" and drop:                # 경고 뒤의 빈 줄
        i += 1
        continue
    break
clean.write_text("\n".join(lines[i:]))
dropped.write_text("\n".join(drop))
PYEOF
}

# 한 번 호출한다. 종료코드를 돌려주고 출력은 OUT_FILE·ERR_FILE 에 남긴다.
#
# --allowed-tools "" : 도구 없이 순수 텍스트 분석만. 예측 가능하고 빠르다.
#   DB 이력까지 보게 하려면 여기에 Read·Bash 를 열면 된다(히스토리가 쌓인 뒤).
run_claude() {
    local model="$1"
    : > "$OUT_FILE"
    : > "$ERR_FILE"
    # DISABLE_AUTOUPDATER: 무인 배치가 공용 설치본을 갱신하지 않게 한다. 이게 없으면 새벽
    # 배치가 대화형 세션이 쓰는 바이너리를 바꿔놓는다(그 반대도 마찬가지다). 업데이트는
    # 사람이 의도해서 하는 게 맞다.
    DISABLE_AUTOUPDATER=1 \
    "$CLAUDE_BIN" -p "$(cat "$PROMPT_FILE")" --allowed-tools "" \
        --model "$model" --fallback-model "$FALLBACK_MODEL" \
        < "$REPORT" > "$OUT_FILE" 2> "$ERR_FILE" &
    local pid=$! wd st

    # macOS 기본 환경에는 timeout(1) 이 없어 워치독을 직접 둔다.
    # disown 은 노이즈 제거용이다 — 워치독을 kill 할 때 셸이 "Terminated: 15" 를 찍는데,
    # 정상 동작인데도 진짜 에러처럼 보여 로그 판독을 방해한다.
    # >/dev/null 2>&1 이 중요하다. 이게 없으면 워치독이 스크립트의 stdout 을 물고 있어
    # 본체가 끝나도 파이프가 닫히지 않는다 — 작업이 타임아웃 시간만큼 매달린 것처럼 보인다.
    ( sleep "$TIMEOUT_SEC"; kill -0 "$pid" 2>/dev/null && kill "$pid" 2>/dev/null ) \
        >/dev/null 2>&1 &
    wd=$!
    disown "$wd" 2>/dev/null || true

    wait "$pid"
    st=$?
    kill "$wd" 2>/dev/null
    return $st
}

# 한 번의 실행 안에서는 두 번만 시도한다.
#
# 2026-10-05 데이터가 방향을 바꿨다 — 120초 × 3회(8분 30초)를 sonnet·opus 양쪽으로 썼는데
# 세 번 다 멈췄다. 멈춤은 요청 단위 무작위가 아니라 **구간 단위**다. 한 실행 안에서 시도를
# 늘리는 것은 거의 쓸모가 없다.
#
# 그래서 2회만 한다(2차에서 모델이 바뀌므로 안전 분류기 거절에는 이것으로 충분하다).
# 구간을 벗어나는 일은 **launchd 가 여러 시각에 호출하는 것**으로 처리한다 —
# 06:10 / 07:10 / 09:10. 이미 분석이 있으면 위쪽 가드가 즉시 종료시킨다.
#
# 리포트는 이미 생성·발송된 뒤라 재시도 비용이 없다.
#
# 멈춤은 **간헐적이고 상류 쪽**이다. 로컬 변수 12개(CLI 버전, 입력 크기, 프롬프트, 인증,
# PATH·환경변수 전체, ProcessType, 사용자 설정, 훅·MCP, 작업 디렉터리, stdin, pty, launchd
# 여부)를 하나씩 배제했고 어느 것과도 상관관계가 없었다. 같은 명령이 어느 날은 30초에 끝나고
# 어느 날은 600초를 기다려도 안 온다.
#
# 그래서 대응은 "오래 기다리기" 가 아니라 "빨리 포기하고 다시 묻기" 다. 120초 × 3회 + 간격
# 60초면 최악 6분이고, 예전 600초 × 2회(20분)보다 짧으면서 시도는 더 많다. 안전 분류기
# 거절도 확률적이므로 같은 처방이 듣는다(2차 시도부터 모델도 바뀐다).
#
# 리포트는 이미 생성·발송된 뒤라 재시도 비용이 없다.
ATTEMPTS="${ANALYSIS_ATTEMPTS:-2}"
RETRY_WAIT_SEC="${ANALYSIS_RETRY_WAIT_SEC:-60}"
attempt=1
cur_model="$MODEL"
while : ; do
    log "[analyze] $REPORT 분석 시작 — 시도 ${attempt}/${ATTEMPTS} (model=${cur_model}, timeout ${TIMEOUT_SEC}s)"
    run_claude "$cur_model"
    STATUS=$?

    # 경고를 걷어낸 뒤의 본문으로 성공 여부를 판단한다. 경고만 돌아온 응답은 성공이 아니다.
    strip_cli_warnings
    ANALYSIS="$(cat "$CLEAN_FILE")"
    if [ -s "$DROPPED_FILE" ]; then
        # `|| [ -n "$_w" ]` 가 필요하다. 마지막 줄에 개행이 없으면 read 가 비정상 종료코드를
        # 돌려주면서 그 줄을 버린다 — 실제로 이 가드 없이 짰더니 제거는 됐는데 로그가 비었다.
        while IFS= read -r _w || [ -n "$_w" ]; do
            [ -n "$_w" ] && log "[analyze] CLI 경고 제거: $_w"
        done < "$DROPPED_FILE"
    fi

    if [ "$STATUS" -eq 0 ] && [ -n "$ANALYSIS" ]; then
        break
    fi

    # claude 는 인증 실패를 STDERR 가 아니라 STDOUT 으로 낸다. 예전에는 stderr 만 남겨서
    # "OAuth session expired" 라는 결정적 단서를 로그에도 알림에도 남기지 못했다.
    DETAIL="$(head -c 500 "$OUT_FILE")"
    [ -z "$DETAIL" ] && DETAIL="$(head -c 500 "$ERR_FILE")"
    [ -z "$DETAIL" ] && DETAIL="(출력 없음)"
    log "[analyze] 시도 ${attempt} 실패 (exit=$STATUS): $DETAIL"

    if [ "$attempt" -ge "$ATTEMPTS" ]; then
        break
    fi

    # 다음 시도는 다른 모델로 간다. 안전 분류기 거절은 **모델별**이므로 같은 모델에 같은 걸
    # 다시 물어도 같은 답이 온다. 실제로 2026-09-29 에 sonnet 이 두 번 연달아 거절해
    # 재시도가 아무 일도 하지 못했다.
    if [ "$cur_model" != "$FALLBACK_MODEL" ]; then
        log "[analyze] 다음 시도는 ${FALLBACK_MODEL} 로 바꿉니다 (거절·실패는 모델별로 갈립니다)"
        cur_model="$FALLBACK_MODEL"
    fi

    attempt=$((attempt + 1))
    sleep "$RETRY_WAIT_SEC"
done

if [ "$STATUS" -ne 0 ] || [ -z "$ANALYSIS" ]; then
    HINT="
${ATTEMPTS}회 모두 실패했습니다."
    case "$DETAIL" in
        *safeguards*|*reasoning_extraction*)
            HINT="$HINT
안전 분류기가 요청을 거절했습니다(확률적으로 발생합니다). ANALYSIS_MODEL 을 다른 모델로
바꾸거나, deploy/analysis-prompt.md 의 표현을 다듬어 보세요."
            ;;
        *authenticate*|*OAuth*|*Unauthorized*)
            HINT="$HINT
인증 문제로 보입니다. Mac Mini 에서 \`claude setup-token\` 으로 토큰을 재발급하고
plist 의 ANTHROPIC_AUTH_TOKEN 을 갱신하세요."
            ;;
    esac

    if [ "$STATUS" -eq 143 ]; then
        HINT="$HINT
${TIMEOUT_SEC}s 안에 끝나지 않아 중단했습니다. 정상은 20~40초입니다.
이 멈춤은 간헐적이며 상류 응답 대기로 확인됐습니다(로컬 변수 12개 배제).
**ANALYSIS_TIMEOUT_SEC 을 늘리지 마세요** — 600초를 기다려도 회복된 적이 없습니다.
셸에서 수동 실행하면 대개 통과합니다:
  bash ~/apps/stock-pulse/analyze-report.sh $RUN_DATE"
    fi

    log "[analyze] 실패 (exit=$STATUS, ${ATTEMPTS}회 시도): $DETAIL"
    notify "⚠️ StockPulse 2차 분석 실패 ($RUN_DATE)
exit=$STATUS
$DETAIL$HINT"
    exit 1
fi

[ "$attempt" -gt 1 ] && log "[analyze] ${attempt}번째 시도에서 성공"

log "[analyze] 완료 — ${#ANALYSIS}자"

# ── 저장 + 발송 ─────────────────────────────────────────────────────────────
{
    echo "# StockPulse 2차 분석 — $RUN_DATE"
    echo
    echo "> Claude Code CLI $CLAUDE_VERSION · 모델 ${cur_model} · 원본 리포트: $(basename "$REPORT")"
    echo
    echo "$ANALYSIS"
} > "$ANALYSIS_FILE"
log "[analyze] 저장: $ANALYSIS_FILE"

if ! notify_file "$ANALYSIS_FILE" "🔍 StockPulse 2차 분석 — $RUN_DATE"; then
    log "[analyze] 전 채널 전송 실패 — 분석은 $ANALYSIS_FILE 에 남아 있습니다"
    exit 1
fi

log "[analyze] 종료"
