#!/usr/bin/env bash
# Easy Claw - Linux / macOS build & package script
# Usage:
#   bash scripts/build-package.sh                  # app-image (bundled JRE) + tar.gz, no Java needed on target
#   bash scripts/build-package.sh --fat-jar-only   # fat jar only (target needs JDK 21+)
#   bash scripts/build-package.sh --version 1.2.0  # override version
#   bash scripts/build-package.sh --skip-frontend   # skip frontend build

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
TARGET_DIR="$PROJECT_ROOT/target"
DIST_DIR="$TARGET_DIR/dist"
APP_NAME="Easy-Claw"

MODE="appimage"
VERSION=""
SKIP_FRONTEND=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --fat-jar-only)  MODE="fatjar" ;;
        --version)       VERSION="${2:-}"; shift ;;
        --skip-frontend) SKIP_FRONTEND=true ;;
    esac
    shift
done

# ---------- detect OS ----------
OS="$(uname -s)"
case "$OS" in
    Linux*)  PLATFORM="linux"  ;;
    Darwin*) PLATFORM="macos"  ;;
    *)       PLATFORM="unknown" ;;
esac

echo "============================================="
echo "  $APP_NAME - Linux/macOS Build Script"
echo "============================================="
echo "  Platform: $PLATFORM"
echo "  Mode:     $MODE"
echo ""

# ---------- find JDK 21+ ----------
find_jdk21() {
    local required=21
    local search_dirs=(
        "$HOME/.jdks"
        "$HOME/.sdkman/candidates/java"
        "/usr/lib/jvm"
        "/Library/Java/JavaVirtualMachines"
        "/opt/homebrew/opt"
    )
    local jdk ver

    if [[ -n "${JAVA_HOME:-}" ]] && [[ -x "$JAVA_HOME/bin/java" ]]; then
        jdk="$JAVA_HOME"
    else
        for dir in "${search_dirs[@]}"; do
            [[ -d "$dir" ]] || continue
            jdk=$(find "$dir" -maxdepth 2 -name "java" -path "*/bin/java" -executable 2>/dev/null \
                  | while read -r j; do
                      $j -version 2>&1 | head -1 | grep -qE '"(2[1-9]|[3-9][0-9])\.' && echo "$j"
                    done | head -1)
            [[ -n "$jdk" ]] && break
        done
        if [[ -z "$jdk" ]] && command -v java &>/dev/null; then
            ver=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\)\..*/\1/')
            if [[ "$ver" -ge "$required" ]]; then
                jdk="$(dirname "$(dirname "$(which java)")")"
            fi
        fi
    fi
    [[ -n "$jdk" ]] && echo "$jdk"
}

echo "[1/5] Find JDK 21+"
JDK_HOME=$(find_jdk21)
if [[ -z "$JDK_HOME" ]]; then
    echo "ERROR: JDK 21+ not found. Set JAVA_HOME or install from https://adoptium.net/"
    exit 1
fi
export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
echo "      JAVA_HOME: $JAVA_HOME"
echo "      java     : $(java -version 2>&1 | head -1)"

# ---------- read version ----------
if [[ -z "$VERSION" ]]; then
    # strip parent block first
    VERSION=$(sed '/<parent>/,/<\/parent>/d' "$PROJECT_ROOT/pom.xml" \
              | grep -m1 '<version>' \
              | sed 's/.*<version>\([^<]*\)<\/version>.*/\1/' \
              | sed 's/-SNAPSHOT//')
    [[ -z "$VERSION" ]] && VERSION="1.0.0"
fi
echo "      Version: $VERSION"

# ---------- Maven build ----------
echo ""
echo "[2/5] Maven build"

MVN_ARGS=("clean" "package")
$SKIP_FRONTEND && MVN_ARGS+=("-Pskip-frontend")

cd "$PROJECT_ROOT"
mvn "${MVN_ARGS[@]}"

JAR_FILE="$TARGET_DIR/easy-claw.jar"
[[ -f "$JAR_FILE" ]] || JAR_FILE=$(find "$TARGET_DIR" -maxdepth 1 -name "*.jar" ! -name "*-sources*" ! -name "*-javadoc*" | head -1)
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
java -Xmx2g -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -jar easy-claw.jar
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

    # Spring Boot fat jar entrypoint is JarLauncher (reads MANIFEST's Start-Class)
    MAIN_CLASS="org.springframework.boot.loader.launch.JarLauncher"
    JAVA_OPTS=(
        "-Xmx2g"
        "-Dfile.encoding=UTF-8"
        "-Dstdout.encoding=UTF-8"
        "-Dstderr.encoding=UTF-8"
        "-Dsun.stdout.encoding=UTF-8"
        "-Dsun.stderr.encoding=UTF-8"
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

# ---------- post-process: inject launcher script ----------
if $PKG_READY && [[ "$MODE" == "app-image" ]]; then
    APP_DIR="$DIST_DIR/$APP_NAME"

    cat > "$APP_DIR/start.sh" << 'STARTEOF'
#!/usr/bin/env bash
# Easy Claw launcher (Linux / macOS)
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

# jpackage app-image: binary name matches --name
BIN_NAME="$(ls | grep -v '\.' | head -1)"
if [[ -z "$BIN_NAME" ]] || [[ ! -x "$BIN_NAME" ]]; then
    BIN_NAME="Easy-Claw"
fi

"./$BIN_NAME" >> "$HOME/.easyClaw/logs/app.log" 2>&1
RC=$?
if [[ $RC -ne 0 ]]; then
    echo
    echo "Easy-Claw exited with code $RC"
    echo "Check log: $HOME/.easyClaw/logs/app.log"
fi
STARTEOF
    chmod +x "$APP_DIR/start.sh"
    echo "      + start.sh (UTF-8 + log + no-daemon + auto-browser)"
fi

# ---------- archive ----------
echo ""
echo "[5/5] Package for distribution"

ARCHIVE_EXT="tar.gz"

if $PKG_READY && [[ "$MODE" == "app-image" ]]; then
    ARCHIVE="$DIST_DIR/${APP_NAME}-${VERSION}-${PLATFORM}-x64.${ARCHIVE_EXT}"
    rm -f "$ARCHIVE"
    tar -czf "$ARCHIVE" -C "$DIST_DIR" "$APP_NAME"
    echo "      archive: $ARCHIVE"
    SIZE=$(du -sh "$ARCHIVE" | cut -f1)
    echo "      size:    $SIZE"

    # macOS: also try --type dmg if hdiutil available (separate jpackage run)
    if [[ "$PLATFORM" == "macos" ]] && command -v hdiutil &>/dev/null; then
        rm -rf "$DIST_DIR/$APP_NAME.app" 2>/dev/null || true
        DMG_JP_ARGS=(
            --type app
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
        if jpackage "${DMG_JP_ARGS[@]}" 2>/dev/null; then
            DMG="$DIST_DIR/${APP_NAME}-${VERSION}.dmg"
            rm -f "$DMG"
            if hdiutil create -volname "$APP_NAME" -srcfolder "$DIST_DIR/$APP_NAME.app" -ov -format UDZO "$DMG" 2>/dev/null; then
                echo "      dmg: $DMG"
            fi
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
