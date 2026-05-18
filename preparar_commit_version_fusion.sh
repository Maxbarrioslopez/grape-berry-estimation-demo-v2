#!/bin/bash
set -e

ROOT="/home/maxi/Escritorio/modelo_nuevo/optimizacion_uvas"
BRANCH_NAME="feature/fusion-frente-reverso-i18n-theme"
COMMIT_MSG="Add front-back grape fusion flow with bilingual UI and theme support"

cd "$ROOT"

echo "============================================================"
echo "==> Ruta actual"
echo "============================================================"
pwd

echo ""
echo "============================================================"
echo "==> Creando/actualizando .gitignore"
echo "============================================================"

cat > .gitignore <<'GITIGNORE_EOF'
# =========================================================
# Android / Gradle / Build outputs
# =========================================================
.gradle/
build/
*/build/
**/build/
captures/
.externalNativeBuild/
.cxx/
local.properties

# Android generated outputs
*.apk
*.aab
*.ap_
*.aar
**/outputs/
**/intermediates/
**/generated/
**/tmp/
**/kotlin/
**/incremental/

# Release folders / backups
app/release/
app_metrics_detection/app/release/
*.zip

# =========================================================
# Android Studio / IntelliJ
# =========================================================
.idea/
*.iml
.DS_Store

# =========================================================
# Logs / temporales
# =========================================================
*.log
*.tmp
*.temp
*.bak
*.swp
*.swo
Thumbs.db

# =========================================================
# Imágenes de prueba / datasets / resultados pesados
# =========================================================
images/
imagenesparatest/
debug/

app_metrics_detection/images/
app_metrics_detection/imagenesparatest/
app_metrics_detection/debug/

**/imagenesparatest/
**/test_images/
**/test-images/
**/datasets/
**/dataset/
**/results/
**/resultados/
**/runs/
**/outputs/
**/fused/
**/fusion/
**/reportes_test/
**/reports_test/

# Ignorar imágenes sueltas pesadas fuera de recursos Android/assets permitidos
*.jpg
*.jpeg
*.png
*.webp
*.heic
*.tif
*.tiff
*.bmp
*.gif

# Permitir imágenes necesarias de Android res
!app_metrics_detection/app/src/main/res/**/*.png
!app_metrics_detection/app/src/main/res/**/*.jpg
!app_metrics_detection/app/src/main/res/**/*.jpeg
!app_metrics_detection/app/src/main/res/**/*.webp
!app_metrics_detection/app/src/main/res/**/*.xml

# Permitir imágenes necesarias dentro de assets reales
!app_metrics_detection/app/src/main/assets/**/*.png
!app_metrics_detection/app/src/main/assets/**/*.jpg
!app_metrics_detection/app/src/main/assets/**/*.jpeg
!app_metrics_detection/app/src/main/assets/**/*.webp

# =========================================================
# Modelos ML reales necesarios para la app
# NO ignorar modelos dentro de src/main/assets
# =========================================================
!app_metrics_detection/app/src/main/assets/**/*.onnx
!app_metrics_detection/app/src/main/assets/**/*.onnx.data
!app_metrics_detection/app/src/main/assets/**/*.tflite
!app_metrics_detection/app/src/main/assets/**/*.bin
!app_metrics_detection/app/src/main/assets/**/*.json

# Evitar subir modelos copiados por build/intermediates
app_metrics_detection/app/build/
**/build/intermediates/
**/build/outputs/

# =========================================================
# Third party pesado / SDKs locales
# =========================================================
app_metrics_detection/third_party/opencv/OpenCV-android-sdk/samples/

# =========================================================
# Signing keys / release keys
# En este repo privado SÍ se versionarán las keys necesarias.
# =========================================================
!keystore.properties
!app_metrics_detection/keystore.properties
!keystores/*.jks
!*.jks
!*.keystore
!*.p12

# Mantener fuera llaves/certificados sueltos no necesarios
*.pem
*.key
*.crt
*.cer

# Google / Firebase secrets
google-services.json
service-account.json
firebase-adminsdk*.json

# =========================================================
# Node / frontend si existiera
# =========================================================
node_modules/
dist/
.next/
.vercel/

# =========================================================
# Python / entornos auxiliares
# =========================================================
__pycache__/
*.pyc
.venv/
venv/
.env
.env.*
GITIGNORE_EOF

echo ""
echo "============================================================"
echo "==> Inicializando Git si corresponde"
echo "============================================================"
git init

echo ""
echo "============================================================"
echo "==> Limpiando del índice archivos que NO deben versionarse"
echo "==> Esto NO borra tus archivos locales; solo los saca de Git"
echo "============================================================"

git rm -r --cached --ignore-unmatch \
  imagenesparatest \
  data \
  images \
  app_metrics_detection/imagenesparatest \
  app_metrics_detection/images \
  app_metrics_detection/app/build \
  app_metrics_detection/app/release \
  app_metrics_detection/local.properties \
  app_metrics_detection/app/src/main/cpp.zip

echo ""
echo "============================================================"
echo "==> Verificando modelos ML reales"
echo "============================================================"

REQUIRED_MODELS=(
  "app_metrics_detection/app/src/main/assets/weights/modelos/qty_model_rgbdt.onnx"
  "app_metrics_detection/app/src/main/assets/weights/modelos/qty_model_rgbdt.onnx.data"
  "app_metrics_detection/app/src/main/assets/weights/modelos/hist_rgbdt_bimodal.onnx"
  "app_metrics_detection/app/src/main/assets/weights/modelos/hist_rgbdt_bimodal.onnx.data"
  "app_metrics_detection/app/src/main/assets/weights/modelos/legacy/seg_best.onnx"
)

for file in "${REQUIRED_MODELS[@]}"; do
  if [ ! -f "$file" ]; then
    echo "ERROR: falta modelo requerido: $file"
    exit 1
  fi

  if git check-ignore -q "$file"; then
    echo "ERROR: modelo requerido está ignorado: $file"
    exit 1
  fi

  echo "OK: modelo no ignorado: $file"
done

echo ""
echo "============================================================"
echo "==> Verificando signing files / keys"
echo "============================================================"

REQUIRED_KEYS=(
  "app_metrics_detection/keystore.properties"
  "keystores/key1.jks"
)

for file in "${REQUIRED_KEYS[@]}"; do
  if [ ! -f "$file" ]; then
    echo "ERROR: falta key requerida: $file"
    exit 1
  fi

  if git check-ignore -q "$file"; then
    echo "ERROR: key requerida está ignorada: $file"
    exit 1
  fi

  echo "OK: key no ignorada: $file"
done

echo ""
echo "============================================================"
echo "==> Creando/cambiando a branch descriptivo"
echo "============================================================"

if git rev-parse --verify "$BRANCH_NAME" >/dev/null 2>&1; then
  git checkout "$BRANCH_NAME"
else
  git checkout -b "$BRANCH_NAME"
fi

echo ""
echo "============================================================"
echo "==> Agregando archivos permitidos"
echo "============================================================"
git add -A

echo ""
echo "============================================================"
echo "==> Validación de archivos excluidos críticos"
echo "============================================================"

echo ""
echo "==> Archivos de test/build que NO deberían aparecer como agregados nuevos:"
BAD_ADDED="$(git status --short | grep -E '^\?\?|^A ' | grep -E 'imagenesparatest|data/images|app/build|app/release|cpp\.zip' || true)"

if [ -n "$BAD_ADDED" ]; then
  echo "ERROR: hay archivos excluidos entrando como nuevos:"
  echo "$BAD_ADDED"
  echo ""
  echo "Revisa el .gitignore antes de commitear."
  exit 1
fi

echo "OK: no hay archivos excluidos entrando como nuevos."

echo ""
echo "==> Nota:"
echo "Si ves archivos imagenesparatest/data con estado D, está bien."
echo "Eso significa que Git dejará de versionarlos en este commit."

echo ""
echo "============================================================"
echo "==> Verificando que modelos y keys estén incluidos en el staging"
echo "============================================================"

git status --short | grep -E 'onnx|onnx.data|keystore.properties|key1.jks' || true

echo ""
echo "============================================================"
echo "==> Estado final antes del commit"
echo "============================================================"
git status --short

echo ""
echo "============================================================"
echo "==> Creando commit"
echo "============================================================"

if git diff --cached --quiet; then
  echo "No hay cambios en staging para commitear."
  exit 0
fi

git commit -m "$COMMIT_MSG"

echo ""
echo "============================================================"
echo "==> Commit creado correctamente"
echo "============================================================"
echo "Branch: $BRANCH_NAME"
echo "Commit: $COMMIT_MSG"
echo ""
echo "NO se hizo push."
