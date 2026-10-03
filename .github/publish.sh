#!/usr/bin/env bash
# Publish <file> as release "<platform>-<version>" (notes from CHANGELOG.md, falling back to
# generated notes) and roll "<platform>-latest" forward. Needs GH_TOKEN; run from repo root.
# Usage: .github/publish.sh <windows|macos> <vX.Y.Z> <file>
set -euo pipefail
platform="$1"; version="$2"; file="$3"
tag="$platform-$version"

# Body of the "## X.Y.Z" section (heading excluded) up to the next "## ".
awk -v v="${version#v}" '/^## /{f = ($2 == v); next} f' CHANGELOG.md > notes.md
if [ -s notes.md ]; then notes=(--notes-file notes.md); else notes=(--generate-notes); fi

if gh release view "$tag" >/dev/null 2>&1; then
  gh release upload "$tag" "$file" --clobber
else
  gh release create "$tag" "$file" --title "$platform $version" "${notes[@]}"
fi
if gh release view "$platform-latest" >/dev/null 2>&1; then
  gh release delete "$platform-latest" --yes --cleanup-tag
fi
gh release create "$platform-latest" "$file" --title "$platform (latest)" \
  --notes "Always points to the newest $platform build - currently $version. See the versioned $tag release for history."
