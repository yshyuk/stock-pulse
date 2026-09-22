#!/bin/bash
#
# Mac Mini 배포: jar 를 빌드하고 실행 산출물을 ~/apps/stock-pulse 로 내보낸다.
#
# 왜 필요한가
#   배포 대상이 jar 하나가 아니라 jar + 분석 스크립트 + 프롬프트 세 개인데, 셋 다 수동
#   복사였다. 실제로 스크립트만 구버전으로 남아 하루치 분석이 통째로 빠진 적이 있다.
#   빠뜨려도 조용히 구버전이 돌기 때문에 알아채기 어렵다.
#
#   또한 ~/Documents 는 macOS 가 보호하는 폴더(TCC)라 launchd 가 읽지 못한다. 레포에서
#   직접 실행할 수 없고 반드시 ~/apps 로 내보내야 한다.
#
# 사용:
#   ./deploy/install.sh            # 빌드 + 배포
#   ./deploy/install.sh --no-build # 이미 빌드된 jar 로 배포만
#
# plist 는 시크릿이 들어 있어 건드리지 않는다. 최초 설치는 README 를 따른다.

set -uo pipefail

APPS_DIR="${STOCKPULSE_APPS_DIR:-$HOME/apps/stock-pulse}"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD=true
[ "${1:-}" = "--no-build" ] && BUILD=false

say()  { echo "[install] $*"; }
fail() { echo "[install] 실패: $*" >&2; exit 1; }

cd "$REPO_DIR" || fail "레포 디렉터리로 이동할 수 없습니다: $REPO_DIR"

# 배포하려는 것이 어느 커밋인지 먼저 밝힌다. 브랜치를 착각한 채 배포하는 사고를 막는다.
say "레포: $REPO_DIR"
say "브랜치: $(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"
say "커밋: $(git log --oneline -1 2>/dev/null || echo '?')"
REPO_VERSION="$(sed -n 's/^version = "\(.*\)"$/\1/p' build.gradle.kts 2>/dev/null | head -1)"
say "레포 버전: ${REPO_VERSION:-?}"

if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    say "주의: 커밋되지 않은 변경이 있습니다. 배포되는 내용과 레포 상태가 다를 수 있습니다."
fi

if $BUILD; then
    say "빌드 중 (테스트 포함)..."
    chmod +x ./gradlew
    ./gradlew clean build || fail "빌드 실패 — 테스트 결과를 확인하세요"
fi

JAR="build/libs/stock-pulse.jar"
[ -f "$JAR" ] || fail "jar 이 없습니다: $JAR (--no-build 를 썼다면 먼저 빌드하세요)"

# --no-build 는 마지막에 빌드된 jar 을 그대로 내보낸다. 그 jar 이 지금 레포 버전으로
# 빌드된 것이라는 보장이 없다. 실제로 v0.5.0 을 --no-build 로 배포했더니 v0.4.0 때
# 빌드한 jar 이 나갔고, "버전: 0.5.0" 이라고 출력한 뒤 실행 로그에는 v0.4.0 이 찍혔다.
# 셸 스크립트만 바뀐 릴리즈여서 동작은 같았지만, 다음 사람이 배포 실패로 오해한다.
JAR_VERSION="$(unzip -p "$JAR" META-INF/MANIFEST.MF 2>/dev/null | tr -d '\r' \
    | sed -n 's/^Implementation-Version: *//p' | head -1)"
say "jar 버전: ${JAR_VERSION:-?}"
if [ -n "$REPO_VERSION" ] && [ -n "$JAR_VERSION" ] && [ "$REPO_VERSION" != "$JAR_VERSION" ]; then
    say "주의: 내보낼 jar 은 v${JAR_VERSION} 으로 빌드된 것입니다 (레포는 v${REPO_VERSION})."
    say "      실행 로그에 v${JAR_VERSION} 이 찍힙니다 — 배포가 안 된 것으로 오해하기 쉽습니다."
    say "      Java 코드가 바뀐 릴리즈라면 --no-build 없이 다시 실행하세요."
fi

mkdir -p "$APPS_DIR" || fail "$APPS_DIR 을 만들 수 없습니다"

say "배포 → $APPS_DIR"
cp "$JAR" "$APPS_DIR/stock-pulse.jar"           || fail "jar 복사 실패"
cp deploy/analyze-report.sh "$APPS_DIR/"        || fail "분석 스크립트 복사 실패"
cp deploy/analysis-prompt.md "$APPS_DIR/"       || fail "프롬프트 복사 실패"
chmod +x "$APPS_DIR/analyze-report.sh"

# 복사가 실제로 반영됐는지 확인한다. cp 성공과 "최신 내용이 거기 있다" 는 다르다.
for f in stock-pulse.jar analyze-report.sh analysis-prompt.md; do
    [ -s "$APPS_DIR/$f" ] || fail "$f 이 비어 있거나 없습니다"
done
if ! cmp -s deploy/analyze-report.sh "$APPS_DIR/analyze-report.sh"; then
    fail "analyze-report.sh 내용이 레포와 다릅니다"
fi
if ! cmp -s deploy/analysis-prompt.md "$APPS_DIR/analysis-prompt.md"; then
    fail "analysis-prompt.md 내용이 레포와 다릅니다"
fi

say "완료:"
ls -la "$APPS_DIR"/stock-pulse.jar "$APPS_DIR"/analyze-report.sh "$APPS_DIR"/analysis-prompt.md

cat <<'NEXT'

[install] 다음 실행부터 새 버전이 쓰입니다. 데몬 재시작은 필요 없습니다.
[install] 바로 확인하려면:
             launchctl start com.stockpulse.batch
             launchctl start com.stockpulse.analyze
NEXT
