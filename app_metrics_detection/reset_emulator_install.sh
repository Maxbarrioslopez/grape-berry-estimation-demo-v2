#!/usr/bin/env bash
set -euo pipefail

APP_ID="com.gaiaspa.metrics_detection"
PROJECT_DIR="/home/maxi/Escritorio/modelo_nuevo/optimizacion_uvas/app_metrics_detection"
IMG_DIR="/home/maxi/Escritorio/images"
EMU_DIR="/sdcard/Pictures/MetricsTest"

echo "== Verificando ADB =="
adb devices

echo "== Borrando app anterior del emulador =="
adb uninstall "$APP_ID" || true

echo "== Limpiando carpetas seguras de imágenes en emulador =="
adb shell rm -rf "$EMU_DIR"
adb shell mkdir -p "$EMU_DIR"

echo "== Copiando imágenes nuevas al emulador =="
adb push "$IMG_DIR"/. "$EMU_DIR"/

echo "== Limpiando builds locales del proyecto =="
cd "$PROJECT_DIR"
rm -rf app/build app/.cxx .gradle
find . -name "*.apk" -path "*/build/*" -delete || true
find . -name "*.aab" -path "*/build/*" -delete || true

echo "== Compilando Debug nuevo =="
./gradlew :app:assembleDebug --rerun-tasks

echo "== Instalando APK nuevo =="
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo "== Abriendo app =="
adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1

echo "== Listo =="
echo "Imágenes copiadas en: $EMU_DIR"
