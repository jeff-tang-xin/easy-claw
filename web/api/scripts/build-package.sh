#!/usr/bin/env bash
# Easy Claw - Linux / macOS build & package script
# Usage:
#   bash scripts/build-package.sh                    # app-image (bundled JRE) + tar.gz, no Java needed on target
#   bash scripts/build-package.sh --fat-jar-only     # fat jar only (target needs JDK 21+)
#   bash scripts/build-package.sh --version 1.2.0    # override version
#   bash scripts/build-package.sh --skip-frontend    # skip frontend build
#   bash scripts/build-package.sh --run-tests        # run tests (skipped by default: packaging also builds upstream modules)
#   bash scripts/build-package.sh --jdk-path /path/to/jdk   # use this JDK instead of auto-detection
#   bash scripts/build-package.sh --jdk-version 21          # required JDK major version (default 21)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"            # web/api
REPO_ROOT="$(dirname "$PROJECT_ROOT")"             # repo root (parent pom + sibling modules)
TARGET_DIR="$PROJECT_ROOT/target"
DIST_DIR="$TARGET_DIR/dist"
APP_NAME="Easy-Claw"

MODE="appimage"
VERSION=""
SKIP_FRONTEND=false
RUN_TESTS=false
JDK_PATH=""
JDK_VERSION=21
JAVA_VER_RE='"([0-9]+)\.'

show_help() {
    # print the usage header (comment block right after the shebang)
    awk 'NR==1 {next} /^#/ {sub(/^# ?/, ""); print; next} {exit}' "$0"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --fat-jar-only)  MODE="fatjar" ;;
        --version)
            if [[ $# -lt 2 ]]; then
                echo "ERROR: --version requires a value (e.g. --version 1.2.0)" >&2
                exit 1
            fi
            VERSION="$2"
            shift
            ;;
        --skip-frontend) SKIP_FRONTEND=true ;;
        --run-tests)     RUN_TESTS=true ;;
        --jdk-path)
            if [[ $# -lt 2 ]]; then
                echo "ERROR: --jdk-path requires a value (e.g. --jdk-path /usr/lib/jvm/jdk-21)" >&2
                exit 1
            fi
            JDK_PATH="$2"
            shift
            ;;
        --jdk-version)
            if [[ $# -lt 2 ]]; then
                echo "ERROR: --jdk-version requires a value (e.g. --jdk-version 21)" >&2
                exit 1
            fi
            if [[ ! "$2" =~ ^[0-9]+$ ]]; then
                echo "ERROR: --jdk-version must be an integer, got: $2" >&2
                exit 1
            fi
            JDK_VERSION="$2"
            shift
            ;;
        -h|--help)       show_help; exit 0 ;;
        *)
            echo "ERROR: unknown option: $1 (try --help)" >&2
            exit 1
            ;;
    esac
    shift
done

# ---------- detect OS & arch ----------
OS="$(uname -s)"
case "$OS" in
    Linux*)  PLATFORM="linux"  ;;
    Darwin*) PLATFORM="macos"  ;;
    *)       PLATFORM="unknown" ;;
esac

# jpackage produces images for the running architecture, name the archive after it
case "$(uname -m)" in
    x86_64)        ARCH="x64"   ;;
    aarch64|arm64) ARCH="arm64" ;;
    *)             ARCH="$(uname -m)" ;;
esac

echo "============================================="
echo "  $APP_NAME - Linux/macOS Build Script"
echo "============================================="
echo "  Platform: $PLATFORM ($ARCH)"
echo "  Mode:     $MODE"
echo ""

# ---------- find JDK 21+ ----------
find_jdk21() {
    local required=$JDK_VERSION
    local search_dirs=(
        "$HOME/.jdks"
        "$HOME/.sdkman/candidates/java"
        "/usr/lib/jvm"
        "/Library/Java/JavaVirtualMachines"
        "/opt/homebrew/opt"
    )
    local jdk="" ver line cand
    local re="$JAVA_VER_RE"

    # JAVA_HOME first, but only if it really is a JDK 21+
    if [[ -n "${JAVA_HOME:-}" ]] && [[ -x "$JAVA_HOME/bin/java" ]]; then
        line="$( "$JAVA_HOME/bin/java" -version 2>&1 | head -1 || true )"
        if [[ "$line" =~ $re ]] && (( BASH_REMATCH[1] >= required )); then
            echo "$JAVA_HOME"
            return 0
        fi
    fi

    for dir in "${search_dirs[@]}"; do
        [[ -d "$dir" ]] || continue
        # java lives at <root>/<jdk>/bin/java (depth 3); macOS bundles are deeper
        # (<root>/<jdk>/Contents/Home/bin/java), hence maxdepth 5
        while IFS= read -r cand; do
            line="$( "$cand" -version 2>&1 | head -1 || true )"
            if [[ "$line" =~ $re ]] && (( BASH_REMATCH[1] >= required )); then
                jdk="$cand"
                break 2
            fi
        done < <(find "$dir" -maxdepth 5 -name java -path "*/bin/java" -executable 2>/dev/null)
    done

    if [[ -z "$jdk" ]] && command -v java &>/dev/null; then
        ver="$(java -version 2>&1 | head -1 || true)"
        if [[ "$ver" =~ $re ]] && (( BASH_REMATCH[1] >= required )); then
            jdk="$(dirname "$(dirname "$(command -v java)")")"
        fi
    fi

    if [[ -n "$jdk" ]]; then
        echo "$jdk"
    fi
    # always return 0: the caller decides what an empty result means
    # (a non-zero here would kill the assignment under set -e)
    return 0
}

echo "[1/5] Find JDK ${JDK_VERSION}+"
JDK_HOME=""
if [[ -n "$JDK_PATH" ]]; then
    # explicit --jdk-path wins: validate it and never silently fall back
    if [[ ! -x "$JDK_PATH/bin/java" ]]; then
        echo "ERROR: --jdk-path '$JDK_PATH' does not contain an executable bin/java"
        exit 1
    fi
    line="$( "$JDK_PATH/bin/java" -version 2>&1 | head -1 || true )"
    if [[ "$line" =~ $JAVA_VER_RE ]] && (( BASH_REMATCH[1] >= JDK_VERSION )); then
        JDK_HOME="$JDK_PATH"
        echo "      Found JDK ${BASH_REMATCH[1]} at $JDK_HOME (--jdk-path)"
    else
        echo "ERROR: JDK at '$JDK_PATH' does not satisfy version ${JDK_VERSION}+: $line"
        exit 1
    fi
else
    JDK_HOME=$(find_jdk21)
fi
if [[ -z "$JDK_HOME" ]]; then
    echo "ERROR: JDK ${JDK_VERSION}+ not found. Set JAVA_HOME, pass --jdk-path or install from https://adoptium.net/"
    exit 1
fi
export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
echo "      JAVA_HOME: $JAVA_HOME"
echo "      java     : $(java -version 2>&1 | head -1)"

# ---------- read version ----------
# web/api/pom.xml declares no <version> of its own - it inherits from parent
# easy-claw-parent - so the first <version> in the file (inside <parent>) is it.
# (Deleting the <parent> block first would grab the first dependency's
#  "${project.version}" placeholder instead.)
if [[ -z "$VERSION" ]]; then
    VERSION=$(grep -m1 '<version>' "$PROJECT_ROOT/pom.xml" \
              | sed 's/.*<version>\([^<]*\)<\/version>.*/\1/' \
              | sed 's/-SNAPSHOT$//' || true)
    if [[ -z "$VERSION" ]]; then
        VERSION="1.0.0"
    fi
fi
echo "      Version: $VERSION"

# ---------- Maven build ----------
echo ""
echo "[2/5] Maven build"

if ! command -v mvn &>/dev/null; then
    echo "ERROR: mvn not found on PATH. Install Maven or add its bin directory to PATH."
    exit 1
fi

# Build from the repo root with -am so sibling SNAPSHOT modules (easy-claw-base,
# easy-claw-agent-core, the agent implementations, agentscope-core, ...) are
# compiled from current sources. Building inside web/api only would resolve them
# from the local Maven repository - stale or missing there on most machines.
# -pl uses the artifactId form: path form (web/api) fails for nested modules.
MVN_ARGS=("clean" "package" "-pl" ":easy-claw" "-am")
if ! $RUN_TESTS; then MVN_ARGS+=("-DskipTests"); fi
if $SKIP_FRONTEND; then MVN_ARGS+=("-Dskip.frontend=true"); fi

cd "$REPO_ROOT"
mvn "${MVN_ARGS[@]}"

JAR_FILE="$TARGET_DIR/easy-claw.jar"
if [[ ! -f "$JAR_FILE" ]]; then
    JAR_FILE=$(find "$TARGET_DIR" -maxdepth 1 -name "*.jar" ! -name "*-sources*" ! -name "*-javadoc*" | head -1 || true)
fi
if [[ -z "$JAR_FILE" ]] || [[ ! -f "$JAR_FILE" ]]; then
    echo "ERROR: built jar not found in $TARGET_DIR"
    exit 1
fi
echo "      JAR: $JAR_FILE"

# ---------- stage dist ----------
echo ""
echo "[3/5] Stage dist artifacts"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

cp "$JAR_FILE" "$DIST_DIR/easy-claw.jar"

cat > "$DIST_DIR/run.sh" << 'RUNEOF'
#!/usr/bin/env bash
cd "$(dirname "$0")"
java -Xmx2g -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -jar easy-claw.jar
RUNEOF
chmod +x "$DIST_DIR/run.sh"

cat > "$DIST_DIR/README.txt" << 'READEOF'
Easy Claw - AI Work Assistant
=============================

Quick Start (app-image, no Java install needed):
  1. Extract the archive
  2. Run Easy-Claw/bin/Easy-Claw (Linux) or Easy-Claw.app (macOS)
  3. Open http://localhost:18080

Quick Start (fat jar, requires JDK 21+):
  1. ./run.sh  (or: java -jar easy-claw.jar)
  2. Open http://localhost:18080

Data directory: ~/.easyClaw/
READEOF

echo "      dist dir: $DIST_DIR"

# ---------- jpackage ----------
PKG_INPUT="$TARGET_DIR/pkg-input"
PKG_READY=false

if [[ "$MODE" != "fatjar" ]]; then
    echo ""
    echo "[4/5] jpackage -> app-image"

    if ! command -v jpackage &>/dev/null; then
        echo "WARN: jpackage not found. Skipping native image, keeping fat jar."
        MODE="fatjar"
    fi
fi

if [[ "$MODE" != "fatjar" ]]; then
    rm -rf "$PKG_INPUT"
    mkdir -p "$PKG_INPUT"
    cp "$JAR_FILE" "$PKG_INPUT/easy-claw.jar"

    # Spring Boot 3.2+ fat jar entrypoint (reads MANIFEST's Start-Class)
    MAIN_CLASS="org.springframework.boot.loader.launch.JarLauncher"
    # No forced stdout/stderr encoding: the JVM follows the terminal (UTF-8 on Linux/macOS),
    # and logback console charset defaults to UTF-8 via application.yml. File logging stays UTF-8.
    JAVA_OPTS=(
        "-Xmx2g"
        "-Dfile.encoding=UTF-8"
        "-Dsun.jnu.encoding=UTF-8"
        "-Dspring.main.banner-mode=console"
    )

    jp_args=(
        --type app-image
        --name "$APP_NAME"
        --app-version "$VERSION"
        --input "$PKG_INPUT"
        --main-jar easy-claw.jar
        --main-class "$MAIN_CLASS"
        --dest "$DIST_DIR"
        --description "AgentScope 2.0 based AI work assistant"
        --vendor "Easy Claw"
        --icon "$SCRIPT_DIR/Easy-Claw.png"
    )
    for opt in "${JAVA_OPTS[@]}"; do
        jp_args+=(--java-options "$opt")
    done

    if jpackage "${jp_args[@]}"; then
        PKG_READY=true
        echo "      app-image: $DIST_DIR/$APP_NAME/"
    else
        echo "WARN: jpackage failed. fat jar kept."
        MODE="fatjar"
    fi
fi

# ---------- post-process: inject launcher script (Linux only; macOS uses the .app bundle) ----------
if $PKG_READY && [[ "$MODE" == "appimage" ]] && [[ "$PLATFORM" == "linux" ]]; then
    APP_DIR="$DIST_DIR/$APP_NAME"

    cat > "$APP_DIR/start.sh" << 'STARTEOF'
#!/usr/bin/env bash
# Easy Claw launcher (Linux)
# Browser auto-open is handled by JVM (BrowserLauncher.java), no hardcoded port.
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

mkdir -p "$HOME/.easyClaw/logs"

echo "Starting Easy-Claw..."
echo "  Log file : $HOME/.easyClaw/logs/app.log"
echo "  Data dir : $HOME/.easyClaw/"
echo "  Press Ctrl+C to stop."
echo

# jpackage app-image: the launcher binary sits next to this script, named after --name
BIN_NAME="Easy-Claw"
if [[ ! -x "$BIN_NAME" ]]; then
    echo "ERROR: $BIN_NAME not found or not executable in $SCRIPT_DIR"
    exit 1
fi

# set +e so a non-zero exit reaches the friendly message below instead of killing us
set +e
"./$BIN_NAME" >> "$HOME/.easyClaw/logs/app.log" 2>&1
RC=$?
set -e
if [[ $RC -ne 0 ]]; then
    echo
    echo "Easy-Claw exited with code $RC"
    echo "Check log: $HOME/.easyClaw/logs/app.log"
fi
STARTEOF
    chmod +x "$APP_DIR/start.sh"
    echo "      + start.sh (log + no-daemon + auto-browser)"
fi

# ---------- archive ----------
echo ""
echo "[5/5] Package for distribution"

ARCHIVE_EXT="tar.gz"

if $PKG_READY && [[ "$MODE" == "appimage" ]]; then
    # jpackage names the image "Easy-Claw" on Linux but "Easy-Claw.app" on macOS
    if [[ "$PLATFORM" == "macos" ]]; then
        BUNDLE_NAME="$APP_NAME.app"
    else
        BUNDLE_NAME="$APP_NAME"
    fi

    ARCHIVE="$DIST_DIR/${APP_NAME}-${VERSION}-${PLATFORM}-${ARCH}.${ARCHIVE_EXT}"
    rm -f "$ARCHIVE"
    tar -czf "$ARCHIVE" -C "$DIST_DIR" "$BUNDLE_NAME"
    echo "      archive: $ARCHIVE"
    SIZE=$(du -sh "$ARCHIVE" | cut -f1)
    echo "      size:    $SIZE"

    # macOS: also build a dmg (separate jpackage run)
    if [[ "$PLATFORM" == "macos" ]]; then
        DMG_JP_ARGS=(
            --type dmg
            --name "$APP_NAME"
            --app-version "$VERSION"
            --input "$PKG_INPUT"
            --main-jar easy-claw.jar
            --main-class "$MAIN_CLASS"
            --dest "$DIST_DIR"
            --description "AgentScope 2.0 based AI work assistant"
            --vendor "Easy Claw"
        )
        for opt in "${JAVA_OPTS[@]}"; do
            DMG_JP_ARGS+=(--java-options "$opt")
        done
        if jpackage "${DMG_JP_ARGS[@]}"; then
            echo "      dmg: $DIST_DIR/${APP_NAME}-${VERSION}.dmg"
        else
            echo "WARN: dmg packaging failed (tar.gz archive is still valid)."
        fi
    fi
else
    echo "      (skipped, no app-image to archive)"
fi

rm -rf "$PKG_INPUT" 2>/dev/null || true

# ---------- done ----------
echo ""
echo "============================================="
echo "  Build complete"
echo "  Output: $DIST_DIR"
echo "============================================="
