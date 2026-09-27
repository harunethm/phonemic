#!/usr/bin/env bash
# Builds the release APK and drops it in ../../phonemic/android-release/ for publishing
# via release.sh.
#
# Signed if ~/.gradle/gradle.properties has PHONEMIC_RELEASE_STORE_FILE/STORE_PASSWORD/
# KEY_ALIAS/KEY_PASSWORD set (see app/build.gradle.kts); otherwise falls back to an
# UNSIGNED apk - installable via `adb install -r` but not via a plain tap-install.

set -euo pipefail
cd "$(dirname "$0")"

./gradlew assembleRelease

release_dir="../../phonemic/android-release"
mkdir -p "$release_dir"

signed_apk="app/build/outputs/apk/release/app-release.apk"
unsigned_apk="app/build/outputs/apk/release/app-release-unsigned.apk"

if [ -f "$signed_apk" ]; then
  cp "$signed_apk" "$release_dir/PhoneMic-Android.apk"
  echo "Done. Published SIGNED apk to $release_dir/PhoneMic-Android.apk"
else
  cp "$unsigned_apk" "$release_dir/PhoneMic-Android-unsigned.apk"
  echo "Done. Published UNSIGNED apk to $release_dir/PhoneMic-Android-unsigned.apk"
fi
