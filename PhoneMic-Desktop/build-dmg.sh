#!/usr/bin/env bash
# Rebuilds PhoneMic-Desktop and packages it as a standalone macOS .app (with its own
# bundled Java runtime) into a .dmg, then drops it in ../../phonemic/macos-release/ for
# publishing via release.sh.

# jpackage on macOS rejects a 0.x major, so release 0.X.Y ships as app-version 1.X.Y.
APP_VERSION="${APP_VERSION:-1.2.0}"
set -euo pipefail
cd "$(dirname "$0")"

: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17+ install, e.g. /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"

./gradlew installDist
rm -rf dist

"$JAVA_HOME/bin/jpackage" \
  --type dmg \
  --input build/install/PhoneMic-Desktop/lib \
  --dest dist \
  --name "PhoneMic-Desktop" \
  --main-jar "PhoneMic-Desktop.jar" \
  --main-class "com.scylla.tool.phonemic.pc.MainKt" \
  --icon "src/main/resources/icons/app-icon.icns" \
  --app-version "$APP_VERSION" \
  --vendor "Scylla"

release_dir="../../phonemic/macos-release"
mkdir -p "$release_dir"
cp dist/PhoneMic-Desktop-$APP_VERSION.dmg "$release_dir/PhoneMic-Desktop-macos.dmg"

echo "Done. Published to $release_dir/PhoneMic-Desktop-macos.dmg"
