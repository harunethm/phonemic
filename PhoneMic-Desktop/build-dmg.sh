#!/usr/bin/env bash
# Rebuilds PhoneMic-Desktop and packages it as a standalone macOS .app (with its own
# bundled Java runtime) into a .dmg, then drops it in ../../phonemic/macos-release/ for
# publishing via release.sh.

# jpackage on macOS rejects a 0.x major, so release 0.X.Y ships as app-version 1.X.Y.
APP_VERSION="${APP_VERSION:-1.3.0}"
set -euo pipefail
cd "$(dirname "$0")"

: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17+ install, e.g. /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"

./gradlew installDist
rm -rf dist

# Camera: the helper that feeds OBS's virtual camera. Built next to the jars, so jpackage
# ships it inside the app where ObsMacCamera.kt looks for it. Needs Xcode command line tools.
# Non-fatal on purpose: without the helper the app still works (camera falls back to
# preview only), so a helper build problem must not block the whole macOS release.
if ! clang++ -std=c++17 -O2 -fobjc-arc -arch arm64 -arch x86_64 \
  native/macos/phonemic-vcam.mm \
  -framework Foundation -framework CoreMedia -framework CoreMediaIO -framework CoreVideo \
  -o build/install/PhoneMic-Desktop/lib/phonemic-vcam; then
  echo "::warning::phonemic-vcam helper failed to build - macOS camera will be preview only"
fi

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
