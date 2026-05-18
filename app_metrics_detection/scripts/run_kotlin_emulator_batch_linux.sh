#!/usr/bin/env bash
# =========================================================================
# run_kotlin_emulator_batch_linux.sh v4.0 — Pipeline robusto con resume
# =========================================================================

# ---- defaults ----
PROJECT_ROOT="/home/maxi/Escritorio/modelo_nuevo/optimizacion_uvas/app_metrics_detection"
DATA_ROOT="/home/maxi/Descargas/data-20260517T050615Z-3-001/data"
CSV="$DATA_ROOT/csv/val_sample_pairs.csv"
IMAGES_ROOT="$DATA_ROOT/images"
OUT_ROOT="$DATA_ROOT/resultados_test_uvas"
PACKAGE="com.gaiaspa.metrics_detection"
ACTIVITY=".DebugBatchActivity"
LIMIT=""
BUILD="false"
INSTALL="false"
EVAL="true"
PAIR_POLICY="auto"
PROVIDER="auto"
FUSED="true"
SKIP_UNINSTALL="false"
DEVICE_INPUT="/sdcard/Download/opt_uvas_input"
DEVICE_OUTPUT="/sdcard/Download/opt_uvas_output"
WAIT_TIMEOUT_MIN="60"

# ---- helpers ----
RED='\e[31m'; GREEN='\e[32m'; YELLOW='\e[33m'; CYAN='\e[36m'; MAG='\e[35m'; BOLD='\e[1m'; RESET='\e[0m'; GRAY='\e[90m'
info()  { echo -e "${CYAN}[INFO]${RESET}    $*"; }
ok()    { echo -e "${GREEN}[OK]${RESET}      $*"; }
warn()  { echo -e "${YELLOW}[WARN]${RESET}    $*"; }
err()   { echo -e "${RED}[ERROR]${RESET}   $*"; }
die()   { err "$*"; exit 1; }
step()  { echo -e "\n${MAG}═════════════════════════════════════════════════${RESET}"; echo -e "  ${MAG}$*${RESET}"; echo -e "${MAG}═════════════════════════════════════════════════${RESET}\n"; }

show_help() {
    cat <<EOF
Uso: $0 [OPCIONES]

Pipeline Android batch: push, run, pull, eval.
Sube todas las imágenes de una vez, DebugBatchActivity las procesa secuencialmente.

OPCIONES:
  --project-root DIR  Proyecto Android  (default: $PROJECT_ROOT)
  --data-root DIR     Raíz del dataset  (default: $DATA_ROOT)
  --csv FILE          CSV ground truth   (default: $CSV)
  --images-root DIR   Imágenes           (default: $IMAGES_ROOT)
  --out-root DIR      Resultados         (default: $OUT_ROOT)
  --limit N           Limitar imágenes
  --build true|false  Compilar APK       (default: false)
  --install true|false Instalar APK      (default: false)
  --eval true|false   Evaluación forense (default: true)
  --provider PROVIDER auto|cpu|nnapi     (default: auto)
  --fused true|false  Fused A/B          (default: true)
  --pair-policy P     auto|strict|single-view
  --skip-uninstall    No desinstalar al empezar
  --help              Esta ayuda
EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --help)        show_help ;;
        --project-root)   PROJECT_ROOT="$2";  shift 2 ;;
        --data-root)      DATA_ROOT="$2";     shift 2 ;;
        --csv)            CSV="$2";           shift 2 ;;
        --images-root)    IMAGES_ROOT="$2";   shift 2 ;;
        --out-root)       OUT_ROOT="$2";      shift 2 ;;
        --limit)          LIMIT="$2";         shift 2 ;;
        --build)          BUILD="$2";         shift 2 ;;
        --install)        INSTALL="$2";       shift 2 ;;
        --eval)           EVAL="$2";          shift 2 ;;
        --provider)       PROVIDER="$2";      shift 2 ;;
        --fused)          FUSED="$2";         shift 2 ;;
        --pair-policy)    PAIR_POLICY="$2";   shift 2 ;;
        --skip-uninstall) SKIP_UNINSTALL="true"; shift ;;
        *) die "Argumento desconocido: $1" ;;
    esac
done

# =================================================================
# 1. ADB + DISPOSITIVO
# =================================================================
step "[1/8] ADB y dispositivo"

ADB=$(command -v adb || true)
[[ -z "$ADB" ]] && ADB="$HOME/Android/Sdk/platform-tools/adb"
[[ -x "$ADB" ]] || die "adb no encontrado. Instala: sudo apt install adb"
ok "adb: $ADB"

# Esperar dispositivo (hasta 30s)
$ADB wait-for-device 2>/dev/null || true
sleep 2

DEVICES=$($ADB devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ -z "$DEVICES" ]]; then
    die "Ningún dispositivo conectado. Conectá tu teléfono con depuración USB."
fi

# Preferir real device sobre emulador
DEVICE_SERIAL=""
IS_EMU=0
for SERIAL in $DEVICES; do
    HW=$($ADB -s "$SERIAL" shell getprop ro.boot.hardware 2>/dev/null | tr -d '\r')
    if echo "$HW" | grep -qiE "emu|qemu|vsoc"; then
        [[ -z "$DEVICE_SERIAL" ]] && DEVICE_SERIAL="$SERIAL" && IS_EMU=1
    else
        DEVICE_SERIAL="$SERIAL"
        IS_EMU=0
        break
    fi
done

MODEL=$($ADB -s "$DEVICE_SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "unknown")
ARCH=$($ADB -s "$DEVICE_SERIAL" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r' || echo "unknown")
ok "Dispositivo: $DEVICE_SERIAL  ($MODEL, $ARCH)"

if [[ "$IS_EMU" == "1" ]]; then
    warn "Usando EMULADOR — los modelos ONNX pueden no funcionar (no hay GPU/NNAPI)."
    warn "Si falla, conectá el dispositivo real."
    PROVIDER="cpu"
fi

# =================================================================
# 2. VALIDAR DIRECTORIOS
# =================================================================
step "[2/8] Directorios locales"
for d in "$PROJECT_ROOT" "$DATA_ROOT" "$IMAGES_ROOT"; do
    [[ -d "$d" ]] || die "Directorio no existe: $d"
done
[[ -f "$CSV" ]] || die "CSV no encontrado: $CSV"
ok "Todo OK"

# =================================================================
# 3. CREAR ESTRUCTURA DE SALIDA
# =================================================================
step "[3/8] Creando run"
mkdir -p "$OUT_ROOT"/{runs,por_variedad,fused,informes,logs,unmatched,failed}
RUN_ID="run_$(date +%Y%m%d_%H%M%S)"
RUN_DIR="$OUT_ROOT/runs/$RUN_ID"
mkdir -p "$RUN_DIR"
ok "Run: $RUN_DIR"

# =================================================================
# 4. LEER CSV Y RESOLVER IMÁGENES
# =================================================================
step "[4/8] Leyendo CSV ($CSV)"

declare -a IMAGE_FILES=()
while IFS= read -r line; do
    r="${line#\"}"; r="${r%\"}"
    c1="$IMAGES_ROOT/$(basename "$r")"
    if [[ -f "$c1" ]]; then IMAGE_FILES+=("$c1"); continue; fi
    c2="$IMAGES_ROOT/$r"
    if [[ -f "$c2" ]]; then IMAGE_FILES+=("$c2"); continue; fi
    warn "No encontrada: $r"
done < <(python3 -c "
import csv,sys
with open('$CSV') as f:
    for row in csv.DictReader(f): print(row['image_path'])
" 2>/dev/null) || die "Error leyendo CSV."

TOTAL=${#IMAGE_FILES[@]}
[[ $TOTAL -eq 0 ]] && die "Cero imágenes encontradas."
info "Total: $TOTAL imágenes"

if [[ -n "$LIMIT" && $LIMIT -gt 0 ]]; then
    IMAGE_FILES=("${IMAGE_FILES[@]:0:$LIMIT}")
    TOTAL=$LIMIT
    info "Limitado a $TOTAL"
fi

echo "$CSV" > "$RUN_DIR/manifest_input.csv"

# =================================================================
# 5. PREPARAR DISPOSITIVO
# =================================================================
step "[5/8] Preparando dispositivo"

if [[ "$SKIP_UNINSTALL" != "true" ]]; then
    info "Desinstalando versión anterior..."
    $ADB -s "$DEVICE_SERIAL" uninstall "$PACKAGE" > /dev/null 2>&1 || true
    $ADB -s "$DEVICE_SERIAL" uninstall --user 0 "$PACKAGE" > /dev/null 2>&1 || true
    ok "App desinstalada (si existía)"
fi

info "Limpiando directorios en dispositivo..."
$ADB -s "$DEVICE_SERIAL" shell "rm -rf '$DEVICE_INPUT' '$DEVICE_OUTPUT'; mkdir -p '$DEVICE_INPUT/images' '$DEVICE_OUTPUT'" 2>/dev/null || true
ok "Directorios listos"

# =================================================================
# 6. BUILD + INSTALL
# =================================================================
if [[ "$BUILD" == "true" ]]; then
    step "[6a/8] Compilando APK debug"
    ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
    export ANDROID_HOME
    pushd "$PROJECT_ROOT" > /dev/null
    ./gradlew assembleDebug 2>&1 | tee "$RUN_DIR/build_log.txt"
    popd > /dev/null
    ok "Build OK"
fi

if [[ "$INSTALL" == "true" ]]; then
    step "[6b/8] Instalando APK"
    APK=$(find "$PROJECT_ROOT/app/build/outputs/apk/debug" -name '*.apk' 2>/dev/null | head -1)
    [[ -z "$APK" ]] && APK=$(find "$PROJECT_ROOT" -path '*/outputs/apk/debug/*.apk' 2>/dev/null | head -1)
    [[ -n "$APK" ]] || die "APK no encontrado. Compilá con --build true."

    INSTALL_OK=0
    if $ADB -s "$DEVICE_SERIAL" install -r -d "$APK" >> "$RUN_DIR/build_log.txt" 2>&1; then
        INSTALL_OK=1
    elif $ADB -s "$DEVICE_SERIAL" install -r -d --user 0 "$APK" >> "$RUN_DIR/build_log.txt" 2>&1; then
        INSTALL_OK=1
    else
        warn "No se pudo instalar. Activá 'Instalar vía USB' en Opciones de desarrollador."
    fi
    [[ $INSTALL_OK -eq 1 ]] && ok "APK instalado"

    info "Limpiando caché de $PACKAGE ..."
    $ADB -s "$DEVICE_SERIAL" shell "pm clear $PACKAGE" > /dev/null 2>&1 || true
    $ADB -s "$DEVICE_SERIAL" shell "cmd package compile -f $PACKAGE" > /dev/null 2>&1 || true
fi

$ADB -s "$DEVICE_SERIAL" shell "appops set $PACKAGE MANAGE_EXTERNAL_STORAGE allow" 2>/dev/null || true

# =================================================================
# 7. PUSH → RUN → WAIT → PULL
# =================================================================
step "[7/8] Procesando en dispositivo ($TOTAL imágenes)"

# 7a. Push imágenes en lote (un solo adb push del directorio completo)
info "Subiendo $TOTAL imágenes al dispositivo..."
TMPDIR=$(mktemp -d)
python3 -c "
import csv, shutil, os
with open('$CSV') as f:
    for row in csv.DictReader(f):
        src = '$IMAGES_ROOT/' + os.path.basename(row['image_path'])
        if os.path.exists(src):
            shutil.copy2(src, '$TMPDIR/')
        else:
            src2 = '$IMAGES_ROOT/' + row['image_path']
            if os.path.exists(src2):
                shutil.copy2(src2, '$TMPDIR/')
"
PUSH_RESULT=$($ADB -s "$DEVICE_SERIAL" push "$TMPDIR/." "$DEVICE_INPUT/images/" 2>&1)
rm -rf "$TMPDIR"
PUSHED=$(echo "$PUSH_RESULT" | grep -oP '\d+ files? pushed' | grep -oP '\d+')
PUSHED=${PUSHED:-$TOTAL}
ok "Push: $PUSHED archivos subidos"

# También subir CSV para matching
$ADB -s "$DEVICE_SERIAL" push "$CSV" "$DEVICE_INPUT/manifest_subsample.csv" > /dev/null 2>&1 || true

# 7b. Lanzar DebugBatchActivity
info "Lanzando DebugBatchActivity (provider=$PROVIDER)..."
LOGCAT_FILE="$RUN_DIR/logcat.txt"

$ADB -s "$DEVICE_SERIAL" shell "am force-stop $PACKAGE" 2>/dev/null || true
sleep 1

# Clear logcat antes de empezar
$ADB -s "$DEVICE_SERIAL" logcat -c 2>/dev/null || true

# Background logcat capture
$ADB -s "$DEVICE_SERIAL" logcat -v time 2>/dev/null | grep --line-buffered -iE "batch|forense|gt-check|exception|onnx|model|error" > "$LOGCAT_FILE" 2>&1 &
LOGCAT_PID=$!

# Launch
LAUNCH_OUTPUT=$($ADB -s "$DEVICE_SERIAL" shell "am start -S -n '$PACKAGE/$ACTIVITY' \
    --es inputDir '$DEVICE_INPUT' \
    --es outputDir '$DEVICE_OUTPUT' \
    --es provider '$PROVIDER'" 2>&1) || true

if echo "$LAUNCH_OUTPUT" | grep -qi "not exported\|SecurityException\|Permission Denial"; then
    kill "$LOGCAT_PID" 2>/dev/null || true
    die "DebugBatchActivity no exported. Compilá con --build true --install true o revisá el AndroidManifest."
fi

echo "$LAUNCH_OUTPUT"
ok "Activity lanzada"

# 7c. Monitorear progreso
echo ""
echo -e "${MAG}  ── Progreso ──────────────────────────────────────────${RESET}"
echo -e "${GRAY}     Timeout: ${WAIT_TIMEOUT_MIN}min | Provider: $PROVIDER${RESET}"

ELAPSED=0
POLL=10
LAST_COUNT=0
NO_PROGRESS=0
BATCH_DONE=false

while [[ $ELAPSED -lt $(( WAIT_TIMEOUT_MIN * 60 )) ]]; do
    sleep "$POLL"
    ELAPSED=$(( ELAPSED + POLL ))

    # Count JSONs in device output
    JSON_COUNT=$($ADB -s "$DEVICE_SERIAL" shell "find '$DEVICE_OUTPUT' -name '*.json' ! -name 'manifest.json' 2>/dev/null | wc -l" 2>/dev/null | tr -d '\r ' || echo 0)

    # Check for manifest
    MANIFEST=$($ADB -s "$DEVICE_SERIAL" shell "find '$DEVICE_OUTPUT' -name 'manifest.json' 2>/dev/null | head -1" 2>/dev/null | tr -d '\r' || true)

    # Show progress
    if [[ "$JSON_COUNT" -gt "$LAST_COUNT" ]]; then
        PCT=0; [[ $TOTAL -gt 0 ]] && PCT=$(( JSON_COUNT * 100 / TOTAL ))
        ETA="?"; [[ $JSON_COUNT -gt 0 ]] && ETA=$(( (TOTAL - JSON_COUNT) * ELAPSED / JSON_COUNT / 60 ))
        echo -e "${CYAN}  >>>${RESET}  ${JSON_COUNT}/${TOTAL} JSONs  (${YELLOW}${PCT}%${RESET}, ~${ETA}min, ${ELAPSED}s)"
        LAST_COUNT=$JSON_COUNT
        NO_PROGRESS=0
    else
        NO_PROGRESS=$(( NO_PROGRESS + 1 ))
    fi

    if [[ -n "$MANIFEST" ]]; then
        echo ""
        ok "manifest.json detectado! ($(date +%H:%M:%S))"
        MANIFEST_DEVICE="$MANIFEST"
        BATCH_DONE=true
        break
    fi

    # Show periodic heartbeat
    if [[ $((ELAPSED % 60)) -eq 0 ]]; then
        if [[ $JSON_COUNT -eq 0 ]]; then
            echo -e "${YELLOW}  ⏱ ${ELAPSED}s — esperando inicio (carga de modelo ML)...${RESET}"
        else
            echo -e "${GRAY}  ⏱ ${ELAPSED}s — ${JSON_COUNT}/${TOTAL} JSONs${RESET}"
        fi
    fi

    # Check last logcat for errors
    if [[ $NO_PROGRESS -ge 6 ]]; then
        LAST_LOG=$($ADB -s "$DEVICE_SERIAL" logcat -d -t 20 2>/dev/null | grep -iE "exception|error|cannot|onnx|model|fatal" | tail -5 || true)
        if [[ -n "$LAST_LOG" ]]; then
            echo -e "${RED}  ⚠ Posible error (sin progreso por ${NO_PROGRESS} ciclos):${RESET}"
            echo "$LAST_LOG" | while IFS= read -r line; do echo -e "${RED}    $line${RESET}"; done
        fi
    fi

    # If no progress for 30+ cycles (~5min) and no images at all, abort
    if [[ $NO_PROGRESS -ge 30 && $JSON_COUNT -eq 0 ]]; then
        echo ""
        warn "Sin progreso por 30 ciclos (~5min) y 0 JSONs. Abortando."
        echo -e "${GRAY}Últimas líneas de logcat:${RESET}"
        $ADB -s "$DEVICE_SERIAL" logcat -d -t 50 2>/dev/null | grep -iE "batch|forense|onnx|model|exception|error|DebugBatch" | tail -10
        break
    fi
done

kill "$LOGCAT_PID" 2>/dev/null || true

if [[ "$BATCH_DONE" == "false" ]]; then
    warn "Timeout o error. Estado final:"
    FINAL_JSONS=$($ADB -s "$DEVICE_SERIAL" shell "find '$DEVICE_OUTPUT' -name '*.json' ! -name 'manifest.json' 2>/dev/null | wc -l" 2>/dev/null | tr -d '\r ' || echo 0)
    FINAL_MF=$($ADB -s "$DEVICE_SERIAL" shell "find '$DEVICE_OUTPUT' -name 'manifest.json' 2>/dev/null" | tr -d '\r' || true)
    info "JSONs: $FINAL_JSONS | Manifest: ${FINAL_MF:-NO}"
fi

# 7d. Descargar resultados
echo ""
info "Descargando resultados..."

if [[ -n "$MANIFEST_DEVICE" ]]; then
    REMOTE_RUN_DIR=$(dirname "$MANIFEST_DEVICE")
    info "Desde: $REMOTE_RUN_DIR"
    $ADB -s "$DEVICE_SERIAL" pull "$REMOTE_RUN_DIR/." "$RUN_DIR/" 2>&1 | tail -3
else
    # Pull todo lo que haya
    FINAL_DIRS=$($ADB -s "$DEVICE_SERIAL" shell "ls -1d '$DEVICE_OUTPUT'/run_* 2>/dev/null" | tr -d '\r' || true)
    if [[ -n "$FINAL_DIRS" ]]; then
        for d in $FINAL_DIRS; do
            info "Descargando $d ..."
            $ADB -s "$DEVICE_SERIAL" pull "$d/." "$RUN_DIR/" 2>&1 | tail -1
        done
    fi
    # Also pull any loose JSONs
    LOOSE_JSONS=$($ADB -s "$DEVICE_SERIAL" shell "ls '$DEVICE_OUTPUT'/*.json 2>/dev/null" | tr -d '\r' || true)
    if [[ -n "$LOOSE_JSONS" ]]; then
        $ADB -s "$DEVICE_SERIAL" pull "$DEVICE_OUTPUT/." "$RUN_DIR/" 2>&1 | tail -1
    fi
fi

# 7e. Organizar JSONs
REAL_MANIFEST=$(find "$RUN_DIR" -name 'manifest.json' 2>/dev/null | head -1)
if [[ -n "$REAL_MANIFEST" ]]; then
    ok "Manifest: $REAL_MANIFEST"
    cp "$REAL_MANIFEST" "$RUN_DIR/manifest.json" 2>/dev/null || true
fi

# Copiar JSONs a raíz del run
find "$RUN_DIR" -name '*.json' -not -name 'manifest_input.csv' -not -name 'manifest.json' -not -name 'resumen_metricas.json' -exec cp {} "$RUN_DIR/" \; 2>/dev/null || true
JSON_COUNT=$(ls "$RUN_DIR"/*.json 2>/dev/null | grep -v manifest_input.csv | grep -v manifest.json | grep -v resumen_metricas.json | wc -l)
info "JSONs recolectados: $JSON_COUNT"

# =================================================================
# 8. LIMPIEZA + EVAL + RESUMEN
# =================================================================
step "[8/8] Limpieza, evaluación y resumen"

info "Limpiando dispositivo..."
$ADB -s "$DEVICE_SERIAL" shell "rm -rf '$DEVICE_INPUT' '$DEVICE_OUTPUT'" 2>/dev/null || true
$ADB -s "$DEVICE_SERIAL" shell "am force-stop $PACKAGE" 2>/dev/null || true
ok "Dispositivo limpio"

# Evaluación
if [[ "$EVAL" == "true" && $JSON_COUNT -gt 0 ]]; then
    EVAL_SCRIPT=$(find "$DATA_ROOT" -name 'eval_jni_vs_gt.py' 2>/dev/null | head -1)
    [[ -z "$EVAL_SCRIPT" ]] && EVAL_SCRIPT=$(find "$PROJECT_ROOT" -name 'eval_jni_vs_gt.py' 2>/dev/null | head -1)
    if [[ -n "$EVAL_SCRIPT" ]]; then
        info "Evaluación forense..."
        python3 "$EVAL_SCRIPT" \
            --root-test-dir "$OUT_ROOT" \
            --gt-csv "$CSV" \
            --images-root "$IMAGES_ROOT" \
            --run-dir "$RUN_DIR" \
            --pair-policy "$PAIR_POLICY" \
            --fused "$FUSED" \
            --export-by-variety true \
            --project-root "$PROJECT_ROOT" \
            --data-root "$DATA_ROOT" \
            --provider "$PROVIDER" \
            2>&1 | tee "$RUN_DIR/eval_log.txt"
        ok "Evaluación completada"
    else
        warn "eval_jni_vs_gt.py no encontrado"
    fi
else
    warn "No hay JSONs para evaluar (o EVAL=false)"
fi

# Resumen
echo ""
echo "====================================================================="
echo "  RUN COMPLETADO: $RUN_ID"
echo "====================================================================="
echo "  Dispositivo:  $MODEL ($ARCH)"
echo "  CSV:          $CSV"
echo "  Imágenes:     $TOTAL"
echo "  Push:         $PUSHED"
echo "  Provider:     $PROVIDER"
echo "  JSONs:        $JSON_COUNT"
echo "  Manifest:     ${REAL_MANIFEST:-NO}"
echo "  Evaluación:   $EVAL"
echo "  Run dir:      $RUN_DIR"
echo "====================================================================="
echo ""
