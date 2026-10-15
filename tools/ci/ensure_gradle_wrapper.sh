#!/usr/bin/env bash
#
# Makes `./gradlew` available without committing the wrapper jar.
#
# This repository does not commit `gradle/wrapper/gradle-wrapper.jar` (a binary), so
# the wrapper is generated on the build machine with whatever Gradle is installed.
# The generation runs in an EMPTY directory on purpose: the project's own settings
# apply AGP 9.4, which refuses to configure on the Gradle that CI images ship
# preinstalled (8.14.1 on Codemagic's macOS instances), so `gradle wrapper` cannot be
# run inside the checkout. The generated wrapper then downloads the pinned
# distribution (9.6.0, the minimum for AGP 9.4 — see docs/VERSIONS.md) and every later
# command goes through it.
#
# Idempotent: does nothing when `./gradlew` already exists, so committing the wrapper
# later simply retires this script.
#
# Usage:  bash tools/ci/ensure_gradle_wrapper.sh [gradle-version]

set -euo pipefail

version="${1:-9.6.0}"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

if [ -x "$root/gradlew" ]; then
    echo "Gradle wrapper already present; nothing to do."
    exit 0
fi

gradle_bin="$(command -v gradle || true)"
if [ -z "$gradle_bin" ] && [ -x /Users/builder/programs/gradle-8.14.1/bin/gradle ]; then
    # The Codemagic macOS image's documented location for its preinstalled Gradle.
    gradle_bin=/Users/builder/programs/gradle-8.14.1/bin/gradle
fi
if [ -z "$gradle_bin" ]; then
    echo "No Gradle found on PATH (and no known fallback), so the wrapper cannot be generated." >&2
    exit 1
fi

echo "Generating the Gradle wrapper for $version with $gradle_bin"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

# Gradle 8.x refuses to run any task — including `wrapper` — in a directory that is not
# a Gradle build ("Directory ... does not contain a Gradle build"), so the scratch
# directory gets the smallest possible build first. It is not this project's build: the
# settings here are deliberately empty, because applying the project's real settings in
# this directory would drag in AGP and fail on Gradle 8.x, which is the whole reason the
# generation happens outside the checkout.
cat > "$scratch/settings.gradle.kts" <<'SETTINGS'
rootProject.name = "wrapper-bootstrap"
SETTINGS

(
    cd "$scratch"
    "$gradle_bin" --no-daemon --console=plain \
        wrapper --gradle-version "$version" --distribution-type bin
)

if [ ! -f "$scratch/gradlew" ] || [ ! -f "$scratch/gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "Gradle finished but produced no wrapper in $scratch; nothing was copied." >&2
    exit 1
fi

mkdir -p "$root/gradle/wrapper"
cp "$scratch/gradle/wrapper/gradle-wrapper.jar" "$root/gradle/wrapper/gradle-wrapper.jar"
cp "$scratch/gradle/wrapper/gradle-wrapper.properties" "$root/gradle/wrapper/gradle-wrapper.properties"
cp "$scratch/gradlew" "$root/gradlew"
if [ -f "$scratch/gradlew.bat" ]; then
    cp "$scratch/gradlew.bat" "$root/gradlew.bat"
fi
chmod +x "$root/gradlew"

if ! grep -q "gradle-$version-" "$root/gradle/wrapper/gradle-wrapper.properties"; then
    echo "The generated wrapper does not point at $version:" >&2
    grep distributionUrl "$root/gradle/wrapper/gradle-wrapper.properties" >&2
    exit 1
fi

echo "Wrapper ready: $root/gradlew"
grep distributionUrl "$root/gradle/wrapper/gradle-wrapper.properties" | sed 's/^/  /'
