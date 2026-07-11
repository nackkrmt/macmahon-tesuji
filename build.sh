#!/usr/bin/env bash
## MacMahon Launcher - Build Script (macOS / Linux)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== MacMahon Launcher Build ==="

# Find a JDK providing javac + jar — checks JAVA_HOME first, then macOS's
# java_home registry (any vendor/install method), then a plain directory
# scan for Linux. Mirrors build.ps1's JDK auto-discovery.
find_jdk() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] && [ -x "$JAVA_HOME/bin/jar" ]; then
        echo "$JAVA_HOME"
        return 0
    fi

    if [ -x /usr/libexec/java_home ]; then
        local home
        home="$(/usr/libexec/java_home -v "25+" 2>/dev/null || true)"
        if [ -n "$home" ] && [ -x "$home/bin/javac" ] && [ -x "$home/bin/jar" ]; then
            echo "$home"
            return 0
        fi
    fi

    for d in /usr/lib/jvm/*; do
        if [ -x "$d/bin/javac" ] && [ -x "$d/bin/jar" ]; then
            echo "$d"
            return 0
        fi
    done

    return 1
}

JDK_HOME="$(find_jdk || true)"
if [ -z "$JDK_HOME" ]; then
    echo "ERROR: No JDK found (checked JAVA_HOME, java_home, /usr/lib/jvm)"
    echo "Install a JDK (Temurin recommended): brew install --cask temurin"
    exit 1
fi
JAVAC="$JDK_HOME/bin/javac"
JAR="$JDK_HOME/bin/jar"
echo "      Using JDK: $JDK_HOME"

# Clean
rm -rf "$ROOT/build"
mkdir -p "$ROOT/build"

# Step 1: Compile
echo "[1/3] Compiling..."
# Collect sources into an array without mapfile/readarray — macOS ships bash
# 3.2 by default (no bash 4+ builtins), so use a NUL-delimited read loop.
SOURCES=()
while IFS= read -r -d '' f; do
    SOURCES+=("$f")
done < <(find "$ROOT/src" -name "*.java" -print0)
"$JAVAC" -encoding UTF-8 --release 8 -d "$ROOT/build" "${SOURCES[@]}"
echo "      OK"

# Step 2: Embed MacMahon JAR
echo "[2/3] Embedding MacMahon JAR..."
MACMAHON_JAR="$(find "$ROOT/lib" -maxdepth 1 -name "macmahon-*.jar" | head -1)"
if [ -n "$MACMAHON_JAR" ]; then
    mkdir -p "$ROOT/build/embedded"
    cp "$MACMAHON_JAR" "$ROOT/build/embedded/macmahon.jar"
    echo "      Embedded: $(basename "$MACMAHON_JAR")"
else
    echo "      WARNING: No macmahon JAR found in lib/"
fi

# Step 3: Package JAR (generate manifest inline)
echo "[3/3] Packaging JAR..."
MANIFEST="$ROOT/build/MANIFEST.MF"
printf 'Manifest-Version: 1.0\nMain-Class: launcher.MacMahonLauncher\n' > "$MANIFEST"
(cd "$ROOT/build" && "$JAR" cfm "$ROOT/macmahon-tesuji.jar" "$MANIFEST" .)

# Clean up build dir
rm -rf "$ROOT/build"

FINAL_SIZE=$(du -m "$ROOT/macmahon-tesuji.jar" | cut -f1)
echo "      OK - macmahon-tesuji.jar (~${FINAL_SIZE} mb)"
echo ""
echo "=== Done! ==="
