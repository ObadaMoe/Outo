#!/usr/bin/env bash
# release.sh: publish the current version of Outo on GitHub, where the car's
# Settings › About › Updates finds it.
#
# What it does:
#   - checks the working tree is committed and pushed, and that this version
#     (versionName in cockpit/app/build.gradle.kts) has no release yet;
#   - builds the release APK and runs the unit tests;
#   - creates the GitHub release v<version> on that commit, with the APK attached
#     as Outo-<version>.apk and your notes.
#
# Build it on this Mac: the car only installs an update signed with the same key
# as the Outo it has (this Mac's Android debug key, see build.gradle.kts).
# Raise versionName and versionCode before each release; the car takes a release
# only if its version is higher than the one it runs.
#
# Usage:
#   tools/release.sh "What changed, a line or two"
#   tools/release.sh -F notes.md

set -euo pipefail

REPO="ObadaMoe/Outo"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_FILE="$ROOT/cockpit/app/build.gradle.kts"

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 \"release notes\"  |  $0 -F notes.md" >&2
  exit 2
fi
if [[ "$1" == "-F" ]]; then
  [[ -f "${2:-}" ]] || { echo "No notes file: ${2:-}" >&2; exit 2; }
  NOTES_ARGS=(--notes-file "$2")
else
  NOTES_ARGS=(--notes "$*")
fi

# GitHub as ObadaMoe, without changing which account gh uses by default.
if [[ -z "${GH_TOKEN:-}" ]]; then
  GH_TOKEN="$(gh auth token --user ObadaMoe 2>/dev/null || true)"
  [[ -n "$GH_TOKEN" ]] || { echo "No GitHub sign-in for ObadaMoe (gh auth login)." >&2; exit 1; }
  export GH_TOKEN
fi

VERSION="$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' "$GRADLE_FILE" | head -1)"
[[ -n "$VERSION" ]] || { echo "No versionName in $GRADLE_FILE" >&2; exit 1; }
TAG="v$VERSION"

cd "$ROOT"
if [[ -n "$(git status --porcelain)" ]]; then
  echo "Commit your changes first: the release is built from, and tagged on, the pushed commit." >&2
  exit 1
fi
git fetch -q origin
HEAD_SHA="$(git rev-parse HEAD)"
if ! git branch -r --contains "$HEAD_SHA" | grep -q "origin/"; then
  echo "Push first: $HEAD_SHA isn't on GitHub yet." >&2
  exit 1
fi
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  echo "$TAG is already released. Raise versionName (and versionCode) for a new one." >&2
  exit 1
fi

echo "Building Outo $VERSION…"
(cd cockpit && ./gradlew -q :app:assembleRelease :app:testReleaseUnitTest)
OUT="$ROOT/cockpit/app/build/outputs/apk/release"
APK="$OUT/Outo-$VERSION.apk"
cp "$OUT/app-release.apk" "$APK"

echo "Publishing $TAG on $REPO…"
gh release create "$TAG" "$APK" --repo "$REPO" --target "$HEAD_SHA" --title "Outo $VERSION" "${NOTES_ARGS[@]}"
echo "Done. The car finds it the next time it starts (or Settings › About › Check Now)."
