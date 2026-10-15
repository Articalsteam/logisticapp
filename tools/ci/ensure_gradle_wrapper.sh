#!/usr/bin/env bash
#
# Restores the Gradle wrapper if it is missing.
#
# The wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` and
# `gradle-wrapper.properties`) is committed, so in a normal checkout this script does
# nothing. It exists because the files were absent until recently and something that
# deletes or ignores them should recover without a developer guessing.
#
# It does NOT use any installed Gradle, on purpose. The whole reason this is awkward is
# that CI images ship Gradle 8.14.1, which refuses to run *any* task — `wrapper`
# included — in a directory that is not a Gradle build, and cannot configure this
# project anyway (AGP 9.4 needs Gradle 9.6.0). So instead of generating a wrapper with
# the wrong Gradle, this downloads the wrapper files that Gradle itself publishes in its
# own repository at the release tag, which needs neither Gradle nor even a JDK.
#
# Usage:  bash tools/ci/ensure_gradle_wrapper.sh [gradle-version] [tag]
#   Defaults: version 9.6.0, tag v9.6.0 (the tag carries a leading "v").
#
# Source of the files: github.com/gradle/gradle, path `gradle/wrapper/` and the two
# scripts at the repository root, fetched through the GitHub contents API, which returns
# the raw bytes.

set -euo pipefail

version="${1:-9.6.0}"
tag="${2:-v$version}"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

properties="$root/gradle/wrapper/gradle-wrapper.properties"
jar="$root/gradle/wrapper/gradle-wrapper.jar"

if [ -x "$root/gradlew" ] && [ -f "$jar" ] && [ -f "$properties" ]; then
    echo "Gradle wrapper already present; nothing to do."
    exit 0
fi

if ! command -v curl >/dev/null 2>&1; then
    echo "curl is required to restore the wrapper and was not found on PATH." >&2
    exit 1
fi

api="https://api.github.com/repos/gradle/gradle/contents"
fetch() { # fetch <repo-path> <destination>
    curl -fsSL -H "Accept: application/vnd.github.raw" \
        "$api/$1?ref=$tag" -o "$2"
}

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

echo "Fetching the Gradle $version wrapper from gradle/gradle@$tag"
fetch "gradle/wrapper/gradle-wrapper.jar" "$scratch/gradle-wrapper.jar"
fetch "gradlew" "$scratch/gradlew"
fetch "gradlew.bat" "$scratch/gradlew.bat"

# The download is only accepted if it is really the wrapper: a ZIP archive whose manifest
# names Gradle's wrapper main class. An HTML error page saved as a .jar would otherwise
# look like success until the first build.
if ! python3 - "$scratch/gradle-wrapper.jar" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    manifest = archive.read("META-INF/MANIFEST.MF").decode()
if "org.gradle.wrapper.GradleWrapperMain" not in manifest:
    sys.exit(f"Main-Class is not GradleWrapperMain:\n{manifest}")
PY
then
    echo "The downloaded gradle-wrapper.jar is not a valid wrapper; nothing was copied." >&2
    exit 1
fi

mkdir -p "$root/gradle/wrapper"
cp "$scratch/gradle-wrapper.jar" "$jar"
cp "$scratch/gradlew" "$root/gradlew"
cp "$scratch/gradlew.bat" "$root/gradlew.bat"
chmod +x "$root/gradlew"

if [ ! -f "$properties" ]; then
    # Written line by line rather than with a heredoc: the `\:` in a properties file
    # escapes the colon, and getting that through shell quoting by eye is exactly the
    # kind of thing that silently produces a wrapper which cannot resolve its
    # distribution URL.
    {
        printf '%s\n' 'distributionBase=GRADLE_USER_HOME'
        printf '%s\n' 'distributionPath=wrapper/dists'
        printf '%s\n' "# Gradle $version — the minimum AGP 9.4 accepts (docs/VERSIONS.md)."
        printf '%s\n' "distributionUrl=https\\://services.gradle.org/distributions/gradle-$version-bin.zip"
        printf '%s\n' 'networkTimeout=10000'
        printf '%s\n' 'retries=3'
        printf '%s\n' 'retryBackOffMs=500'
        printf '%s\n' 'validateDistributionUrl=true'
        printf '%s\n' 'zipStoreBase=GRADLE_USER_HOME'
        printf '%s\n' 'zipStorePath=wrapper/dists'
    } > "$properties"
fi

if ! grep -q "gradle-$version-bin.zip" "$properties"; then
    echo "The wrapper properties do not point at $version:" >&2
    grep distributionUrl "$properties" >&2
    exit 1
fi

echo "Wrapper restored: $root/gradlew"
grep -E 'distributionUrl|distributionSha256Sum' "$properties" | sed 's/^/  /'
