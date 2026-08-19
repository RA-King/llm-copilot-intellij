#!/bin/sh
##############################################################################
# LLM Copilot IntelliJ Plugin — Gradle Wrapper
#
# Target: IntelliJ IDEA 2026.1.4 (build 261)
#
# WHY THIS SCRIPT FORCES JDK 21:
#   The IntelliJ Platform Gradle Plugin performs a JDK version check at
#   SETTINGS EVALUATION TIME (before any task or toolchain configuration
#   runs). It throws a bare RuntimeException with the JDK version string
#   when the process JVM is > 21. This cannot be bypassed with task
#   configuration or system properties because it happens too early.
#
#   Solution: this script always uses IntelliJ IDEA's own bundled JDK 21
#   to run Gradle. Your system JDK (17, 21, 22, 23, 24, 25) is irrelevant —
#   IntelliJ IDEA ships with JDK 21 and that is what we use here.
#
# USAGE:
#   ./gradlew buildPlugin          # auto-detects bundled JDK
#   JAVA_HOME=/path/jdk21 ./gradlew buildPlugin   # explicit override
##############################################################################
set -e

APP_HOME="$(cd "$(dirname "$0")" && pwd -P)"
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# ── Step 1: Find a JDK ≤ 21 for running Gradle ───────────────────────────────
# We prefer IntelliJ's bundled JDK — it is guaranteed to be present when the
# user has IntelliJ IDEA 2026.1 installed.

find_jbr() {
    # $1 = base app path (without /Contents/...)
    local jbr="$1/Contents/jbr/Contents/Home"
    if [ -d "$jbr" ] && [ -x "$jbr/bin/java" ]; then
        local ver
        ver=$("$jbr/bin/java" -version 2>&1 | grep -o '"[0-9]*' | head -1 | tr -d '"')
        if [ -n "$ver" ] && [ "$ver" -le 21 ] 2>/dev/null; then
            echo "$jbr"
        fi
    fi
}

IDEA_JDK=""

# Only search if current JAVA_HOME is too new (or unset)
need_search() {
    [ -z "$JAVA_HOME" ] && return 0
    [ ! -x "$JAVA_HOME/bin/java" ] && return 0
    local ver
    ver=$("$JAVA_HOME/bin/java" -version 2>&1 | grep -o '"[0-9]*' | head -1 | tr -d '"')
    [ -z "$ver" ] && return 0
    [ "$ver" -gt 21 ] 2>/dev/null && return 0 || return 1
}

if need_search; then
    echo "[gradlew] Searching for JDK ≤ 21 (required by IntelliJ Platform build plugin)…"

    for app in \
        "/Applications/IntelliJ IDEA 2026.1.app" \
        "/Applications/IntelliJ IDEA 2026.2.app" \
        "/Applications/IntelliJ IDEA.app" \
        "/Applications/IntelliJ IDEA CE.app" \
        "/Applications/IntelliJ IDEA Ultimate.app" \
        "$HOME/Applications/IntelliJ IDEA 2026.1.app" \
        "$HOME/Applications/IntelliJ IDEA.app" \
        "$HOME/Applications/IntelliJ IDEA CE.app"; do
        found="$(find_jbr "$app")"
        if [ -n "$found" ]; then
            IDEA_JDK="$found"
            break
        fi
    done

    # Fallback: macOS java_home
    if [ -z "$IDEA_JDK" ] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
        for v in 21 20 19 18 17; do
            c=$(/usr/libexec/java_home -v "$v" 2>/dev/null || true)
            if [ -n "$c" ] && [ -d "$c" ]; then
                IDEA_JDK="$c"; break
            fi
        done
    fi

    # Fallback: SDKMAN
    if [ -z "$IDEA_JDK" ]; then
        for sdkman_dir in "$HOME/.sdkman/candidates/java" "${SDKMAN_DIR:-}/candidates/java"; do
            if [ -d "$sdkman_dir" ]; then
                for v in 21.0 20.0 19.0 18.0 17.0; do
                    found=$(find "$sdkman_dir" -maxdepth 1 -name "${v}*" -type d 2>/dev/null | head -1)
                    if [ -n "$found" ] && [ -x "$found/bin/java" ]; then
                        IDEA_JDK="$found"; break 2
                    fi
                done
            fi
        done
    fi

    if [ -n "$IDEA_JDK" ]; then
        export JAVA_HOME="$IDEA_JDK"
        echo "[gradlew] Using JDK at: $JAVA_HOME"
    else
        cat << 'ERR'

  ERROR: Could not find a JDK 17-21 for building.

  The IntelliJ Platform Gradle Plugin requires the Gradle process itself
  to run on JDK ≤ 21.  Your compiled plugin will still support Java 25.

  Fix (choose one):

  1) Install Temurin 21:
       brew install --cask temurin@21
       export JAVA_HOME=$(/usr/libexec/java_home -v 21)
       ./gradlew buildPlugin

  2) Point to IntelliJ's bundled JDK manually:
       export JAVA_HOME="/Applications/IntelliJ IDEA 2026.1.app/Contents/jbr/Contents/Home"
       ./gradlew buildPlugin

  3) Set org.gradle.java.home in gradle.properties (uncomment the line).

ERR
        exit 1
    fi
fi

# ── Step 2: Run Gradle ────────────────────────────────────────────────────────

exec "$JAVA_HOME/bin/java" \
    --enable-native-access=ALL-UNNAMED \
    -Dorg.gradle.appname="Gradle" \
    -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
