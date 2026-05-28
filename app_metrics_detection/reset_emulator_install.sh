#!/usr/bin/env bash
set -euo pipefail

APP_ID="com.gaiaspa.metrics_detection"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${PROJECT_DIR:-$SCRIPT_DIR}"
IMG_DIR="${IMG_DIR:-$PROJECT_DIR/images}"
EMU_DIR="/sdcard/Pictures/MetricsTest"

echo "== Checking ADB =="
adb devices

echo "== Removing previous app from emulator =="
adb uninstall "$APP_ID" || true

echo "== Cleaning secure image folders on emulator =="
adb shell rm -rf "$EMU_DIR"
adb shell mkdir -p "$EMU_DIR"

echo "== Copying new images to emulator =="
adb push "$IMG_DIR"/. "$EMU_DIR"/

echo "== Cleaning local project builds =="
cd "$PROJECT_DIR"
rm -rf app/build app/.cxx .gradle
find . -name "*.apk" -path "*/build/*" -delete || true
find . -name "*.aab" -path "*/build/*" -delete || true

echo "== Building new Debug =="
./gradlew :app:assembleDebug --rerun-tasks

echo "== Installing new APK =="
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo "== Opening app =="
adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1

echo "== Done =="
echo "Images copied to: $EMU_DIR"
