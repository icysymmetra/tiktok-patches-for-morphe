#!/usr/bin/env bash
set -euo pipefail

if ./gradlew --version; then
  echo 'GRADLE_BIN=./gradlew' >> "$GITHUB_ENV"
  exit 0
fi

echo '::warning::Wrapper download failed; trying the official Gradle GitHub asset API.'
properties=gradle/wrapper/gradle-wrapper.properties
distribution=$(sed -n 's/^distributionUrl=//p' "$properties" | tr -d '\r')
filename=${distribution##*/}
version=${filename#gradle-}
version=${version%-bin.zip}
expected=$(sed -n 's/^distributionSha256Sum=//p' "$properties" | tr -d '\r')
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || [[ ! "$expected" =~ ^[0-9a-f]{64}$ ]]; then
  echo '::error::Unsupported Gradle distribution or missing pinned SHA-256.'
  exit 1
fi

asset_url=$(gh api "repos/gradle/gradle-distributions/releases/tags/v$version" \
  --jq ".assets[] | select(.name == \"$filename\") | .url")
if [[ ! "$asset_url" =~ ^https://api\.github\.com/repos/gradle/gradle-distributions/releases/assets/[0-9]+$ ]]; then
  echo '::error::Expected one official Gradle release asset.'
  exit 1
fi

directory=$(mktemp -d "$RUNNER_TEMP/metra-gradle.XXXXXX")
archive="$directory/$filename"
gh api -H 'Accept: application/octet-stream' "$asset_url" > "$archive"
if ! printf '%s  %s\n' "$expected" "$archive" | sha256sum --check --status; then
  echo '::error::Gradle API download does not match the wrapper SHA-256.'
  exit 1
fi
unzip -q "$archive" -d "$directory"
launcher="$directory/gradle-$version/bin/gradle"
"$launcher" --version
printf 'GRADLE_BIN=%s\n' "$launcher" >> "$GITHUB_ENV"
