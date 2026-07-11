#!/usr/bin/env bash
## MacMahon Launcher - macOS double-click launcher
cd "$(dirname "$0")"

find_java() {
    if [ -x /usr/libexec/java_home ]; then
        local home
        home="$(/usr/libexec/java_home -v "25+" 2>/dev/null || true)"
        if [ -n "$home" ] && [ -x "$home/bin/java" ]; then
            echo "$home/bin/java"
            return 0
        fi
    fi
    return 1
}

JAVA_BIN="$(find_java || true)"
if [ -z "$JAVA_BIN" ]; then
    echo "============================================"
    echo " MacMahon 3.10 requires Java 25"
    echo " Java 25 not found on this computer"
    echo "============================================"
    echo ""
    echo "Install via Homebrew:"
    echo "  brew install --cask temurin"
    echo ""
    echo "Or download the .pkg installer from:"
    echo "  https://adoptium.net/temurin/releases/?version=25"
    echo ""
    read -r -p "Press Enter to close..."
    exit 1
fi

"$JAVA_BIN" -Dfile.encoding=UTF-8 -Dapple.laf.useScreenMenuBar=true -jar "macmahon-tesuji.jar"
