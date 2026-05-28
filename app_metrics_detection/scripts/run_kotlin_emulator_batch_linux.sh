#!/usr/bin/env bash
# =========================================================================
# run_kotlin_emulator_batch_linux.sh v4.0 — Robust pipeline with resume
# =========================================================================

# ---- defaults ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${PROJECT_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
DATA_ROOT="${DATA_ROOT:-$PROJECT_ROOT/data}"
CSV="${CSV:-$DATA_ROOT/csv/val_sample_pairs.csv}"
IMAGES_ROOT="${IMAGES_ROOT:-$DATA_ROOT/images}"
OUT_ROOT="${OUT_ROOT:-$DATA_ROOT/resultados_test_uvas}"
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
Usage: $0 [OPTIONS]

Android batch pipeline: push, run, pull, eval.
Pushes all images at once, DebugBatchActivity processes them sequentially.

OPTIONS:
  --project-root DIR  Android project      (default: $PROJECT_ROOT)
  --data-root DIR     Dataset root         (default: $DATA_ROOT)
  --csv FILE          Ground truth CSV     (default: $CSV)
  --images-root DIR   Images               (default: $IMAGES_ROOT)
  --out-root DIR      Results              (default: $OUT_ROOT)
  --limit N           Limit images
  --build true|false  Build APK            (default: false)
  --install true|false Install APK         (default: false)
    --eval true|false   Evaluation report    (default: true)
  --provider PROVIDER auto|cpu|nnapi       (default: auto)
  --fused true|false  Fused A/B            (default: true)
  --pair-policy P     auto|strict|single-view
  --skip-uninstall    Do not uninstall at start
  --help              This help
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
        *) die "Unknown argument: $1" ;;
    esac
done

# =================================================================
# 1. ADB + DEVICE
# =================================================================
step "[1/8] ADB and device"

ADB=$(command -v adb || true)
[[ -z "$ADB" ]] && ADB="$HOME/Android/Sdk/platform-tools/adb"
[[ -x "$ADB" ]] || die "adb not found. Install: sudo apt install adb"
ok "adb: $ADB"

# Wait for device (up to 30s)
$ADB wait-for-device 2>/dev/null || true
sleep 2

DEVICES=$($ADB devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ -z "$DEVICES" ]]; then
    die "No device connected. Connect your phone with USB debugging."
fi

# Prefer real device over emulator
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
ok "Device: $DEVICE_SERIAL  ($MODEL, $ARCH)"

if [[ "$IS_EMU" == "1" ]]; then
    warn "Using EMULATOR — ONNX models may not work (no GPU/NNAPI)."
    warn "If it fails, connect the real device."
    PROVIDER="cpu"
fi

# =================================================================
# 2. VALIDATE DIRECTORIES
# =================================================================
step "[2/8] Local directories"
for d in "$PROJECT_ROOT" "$DATA_ROOT" "$IMAGES_ROOT"; do
    [[ -d "$d" ]] || die "Directory does not exist: $d"
done
[[ -f "$CSV" ]] || die "CSV not found: $CSV"
ok "All OK"

# =================================================================
# 3. CREATE OUTPUT STRUCTURE
# =================================================================
step "[3/8] Creating run"
mkdir -p "$OUT_ROOT"/{runs,por_variedad,fused,informes,logs,unmatched,failed}
RUN_ID="run_$(date +%Y%m%d_%H%M%S)"
RUN_DIR="$OUT_ROOT/runs/$RUN_ID"
mkdir -p "$RUN_DIR"
ok "Run: $RUN_DIR"

# =================================================================
# 4. READ CSV AND RESOLVE IMAGES
# =================================================================
step "[4/8] Reading CSV ($CSV)"

declare -a IMAGE_FILES=()
while IFS= read -r line; do
    r="${line#\"}"; r="${r%\"}"
    c1="$IMAGES_ROOT/$(basename "$r")"
    if [[ -f "$c1" ]]; then IMAGE_FILES+=("$c1"); continue; fi
    c2="$IMAGES_ROOT/$r"
    if [[ -f "$c2" ]]; then IMAGE_FILES+=("$c2"); continue; fi
    warn "Not found: $r"
done < <(python3 -c "
import csv,sys
with open('$CSV') as f:
    for row in csv.DictReader(f): print(row['image_path'])
" 2>/dev/null) || die "Error reading CSV."

TOTAL=${#IMAGE_FILES[@]}
[[ $TOTAL -eq 0 ]] && die "Zero images found."
info "Total: $TOTAL images"

if [[ -n "$LIMIT" && $LIMIT -gt 0 ]]; then
    IMAGE_FILES=("${IMAGE_FILES[@]:0:$LIMIT}")
    TOTAL=$LIMIT
    info "Limited to $TOTAL"
fi

echo "$CSV" > "$RUN_DIR/manifest_input.csv"

# =================================================================
# 5. PREPARE DEVICE
# =================================================================
step "[5/8] Preparing device"

if [[ "$SKIP_UNINSTALL" != "true" ]]; then
    info "Uninstalling previous version..."
    $ADB -s "$DEVICE_SERIAL" uninstall "$PACKAGE" > /dev/null 2>&1 || true
    $ADB -s "$DEVICE_SERIAL" uninstall --user 0 "$PACKAGE" > /dev/null 2>&1 || true
    ok "App uninstalled (if it existed)"
fi

info "Cleaning directories on device..."
$ADB -s "$DEVICE_SERIAL" shell "rm -rf '$DEVICE_INPUT' '$DEVICE_OUTPUT'; mkdir -p '$DEVICE_INPUT/images' '$DEVICE_OUTPUT'" 2>/dev/null || true
ok "Directories ready"

# =================================================================
# 6. BUILD + INSTALL
# =================================================================
if [[ "$BUILD" == "true" ]]; then
    step "[6a/8] Building debug APK"
    ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
    export ANDROID_HOME
    pushd "$PROJECT_ROOT" > /dev/null
    ./gradlew assembleDebug 2>&1 | tee "$RUN_DIR/build_log.txt"
    popd > /dev/null
    ok "Build OK"
fi

if [[ "$INSTALL" == "true" ]]; then
    step "[6b/8] Installing APK"
    APK=$(find "$PROJECT_ROOT/app/build/outputs/apk/debug" -name '*.apk' 2>/dev/null | head -1)
    [[ -z "$APK" ]] && APK=$(find "$PROJECT_ROOT" -path '*/outputs/apk/debug/*.apk' 2>/dev/null | head -1)
    [[ -n "$APK" ]] || die "APK not found. Build with --build true."

    INSTALL_OK=0
    if $ADB -s "$DEVICE_SERIAL" install -r -d "$APK" >> "$RUN_DIR/build_log.txt" 2>&1; then
        INSTALL_OK=1
    elif $ADB -s "$DEVICE_SERIAL" install -r -d --user 0 "$APK" >> "$RUN_DIR/build_log.txt" 2>&1; then
        INSTALL_OK=1
    else
        warn "Could not install. Enable 'Install via USB' in Developer Options."
    fi
    [[ $INSTALL_OK -eq 1 ]] && ok "APK installed"

    info "Clearing cache for $PACKAGE ..."
    $ADB -s "$DEVICE_SERIAL" shell "pm clear $PACKAGE" > /dev/null 2>&1 || true
    $ADB -s "$DEVICE_SERIAL" shell "cmd package compile -f $PACKAGE" > /dev/null 2>&1 || true
fi

$ADB -s "$DEVICE_SERIAL" shell "appops set $PACKAGE MANAGE_EXTERNAL_STORAGE allow" 2>/dev/null || true

# =================================================================
# 7. PUSH → RUN → WAIT → PULL
# =================================================================
step "[7/8] Processing on device ($TOTAL images)"

# 7a. Push images in batch (single adb push of the entire directory)
info "Uploading $TOTAL images to device..."
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
ok "Push: $PUSHED files uploaded"

# Also push CSV for matching
$ADB -s "$DEVICE_SERIAL" push "$CSV" "$DEVICE_INPUT/manifest_subsample.csv" > /dev/null 2>&1 || true

# 7b. Launch DebugBatchActivity
info "Launching DebugBatchActivity (provider=$PROVIDER)..."
LOGCAT_FILE="$RUN_DIR/logcat.txt"

$ADB -s "$DEVICE_SERIAL" shell "am force-stop $PACKAGE" 2>/dev/null || true
sleep 1

# Clear logcat before starting
$ADB -s "$DEVICE_SERIAL" logcat -c 2>/dev/null || true

# Background logcat capture
$ADB -s "$DEVICE_SERIAL" logcat -v time 2>/dev/null | grep --line-buffered -iE "batch|forense|forensic|gt-check|exception|onnx|model|error" > "$LOGCAT_FILE" 2>&1 &
LOGCAT_PID=$!

# Launch
LAUNCH_OUTPUT=$($ADB -s "$DEVICE_SERIAL" shell "am start -S -n '$PACKAGE/$ACTIVITY' \
    --es inputDir '$DEVICE_INPUT' \
    --es outputDir '$DEVICE_OUTPUT' \
    --es provider '$PROVIDER'" 2>&1) || true

if echo "$LAUNCH_OUTPUT" | grep -qi "not exported\|SecurityException\|Permission Denial"; then
    kill "$LOGCAT_PID" 2>/dev/null || true
    die "DebugBatchActivity not exported. Build with --build true --install true or check AndroidManifest."
fi

echo "$LAUNCH_OUTPUT"
ok "Activity launched"

# 7c. Monitor progress
echo ""
echo -e "${MAG}  ── Progress ──────────────────────────────────────────${RESET}"
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
        ok "manifest.json detected! ($(date +%H:%M:%S))"
        MANIFEST_DEVICE="$MANIFEST"
        BATCH_DONE=true
        break
    fi

    # Show periodic heartbeat
    if [[ $((ELAPSED % 60)) -eq 0 ]]; then
        if [[ $JSON_COUNT -eq 0 ]]; then
            echo -e "${YELLOW}  ⏱ ${ELAPSED}s — waiting for start (ML model loading)...${RESET}"
        else
            echo -e "${GRAY}  ⏱ ${ELAPSED}s — ${JSON_COUNT}/${TOTAL} JSONs${RESET}"
        fi
    fi

    # Check last logcat for errors
    if [[ $NO_PROGRESS -ge 6 ]]; then
        LAST_LOG=$($ADB -s "$DEVICE_SERIAL" logcat -d -t 20 2>/dev/null | grep -iE "exception|error|cannot|onnx|model|fatal" | tail -5 || true)
        if [[ -n "$LAST_LOG" ]]; then
            echo -e "${RED}  ⚠ Possible error (no progress for ${NO_PROGRESS} cycles):${RESET}"
            echo "$LAST_LOG" | while IFS= read -r line; do echo -e "${RED}    $line${RESET}"; done
        fi
    fi

    # If no progress for 30+ cycles (~5min) and no images at all, abort
    if [[ $NO_PROGRESS -ge 30 && $JSON_COUNT -eq 0 ]]; then
        echo ""
        warn "No progress for 30 cycles (~5min) and 0 JSONs. Aborting."
        echo -e "${GRAY}Last logcat lines:${RESET}"
        $ADB -s "$DEVICE_SERIAL" logcat -d -t 50 2>/dev/null | grep -iE "batch|forense|forensic|onnx|model|exception|error|DebugBatch" | tail -10
        break
    fi
done

kill "$LOGCAT_PID" 2>/dev/null || true

if [[ "$BATCH_DONE" == "false" ]]; then
    warn "Timeout or error. Final state:"
    FINAL_JSONS=$($ADB -s "$DEVICE_SERIAL" shell "find '$DEVICE_OUTPUT' -name '*.json' ! -name 'manifest.json' 2>/dev/null | wc -l" 2>/dev/null | tr -d '\r ' || echo 0)
    FINAL_MF=$($ADB -s "$DEVICE_SERIAL" shell "find '$DEVICE_OUTPUT' -name 'manifest.json' 2>/dev/null" | tr -d '\r' || true)
    info "JSONs: $FINAL_JSONS | Manifest: ${FINAL_MF:-NO}"
fi

# 7d. Download results
echo ""
info "Downloading results..."

if [[ -n "$MANIFEST_DEVICE" ]]; then
    REMOTE_RUN_DIR=$(dirname "$MANIFEST_DEVICE")
    info "From: $REMOTE_RUN_DIR"
    $ADB -s "$DEVICE_SERIAL" pull "$REMOTE_RUN_DIR/." "$RUN_DIR/" 2>&1 | tail -3
else
    # Pull everything available
    FINAL_DIRS=$($ADB -s "$DEVICE_SERIAL" shell "ls -1d '$DEVICE_OUTPUT'/run_* 2>/dev/null" | tr -d '\r' || true)
    if [[ -n "$FINAL_DIRS" ]]; then
        for d in $FINAL_DIRS; do
            info "Downloading $d ..."
            $ADB -s "$DEVICE_SERIAL" pull "$d/." "$RUN_DIR/" 2>&1 | tail -1
        done
    fi
    # Also pull any loose JSONs
    LOOSE_JSONS=$($ADB -s "$DEVICE_SERIAL" shell "ls '$DEVICE_OUTPUT'/*.json 2>/dev/null" | tr -d '\r' || true)
    if [[ -n "$LOOSE_JSONS" ]]; then
        $ADB -s "$DEVICE_SERIAL" pull "$DEVICE_OUTPUT/." "$RUN_DIR/" 2>&1 | tail -1
    fi
fi

# 7e. Organize JSONs
REAL_MANIFEST=$(find "$RUN_DIR" -name 'manifest.json' 2>/dev/null | head -1)
if [[ -n "$REAL_MANIFEST" ]]; then
    ok "Manifest: $REAL_MANIFEST"
    cp "$REAL_MANIFEST" "$RUN_DIR/manifest.json" 2>/dev/null || true
fi

# Copy JSONs to run root
find "$RUN_DIR" -name '*.json' -not -name 'manifest_input.csv' -not -name 'manifest.json' -not -name 'resumen_metricas.json' -exec cp {} "$RUN_DIR/" \; 2>/dev/null || true
JSON_COUNT=$(ls "$RUN_DIR"/*.json 2>/dev/null | grep -v manifest_input.csv | grep -v manifest.json | grep -v resumen_metricas.json | wc -l)
info "JSONs collected: $JSON_COUNT"

# =================================================================
# 8. CLEANUP + EVAL + SUMMARY
# =================================================================
step "[8/8] Cleanup, evaluation and summary"

info "Cleaning device..."
$ADB -s "$DEVICE_SERIAL" shell "rm -rf '$DEVICE_INPUT' '$DEVICE_OUTPUT'" 2>/dev/null || true
$ADB -s "$DEVICE_SERIAL" shell "am force-stop $PACKAGE" 2>/dev/null || true
ok "Device clean"

# Evaluation
if [[ "$EVAL" == "true" && $JSON_COUNT -gt 0 ]]; then
    EVAL_SCRIPT=$(find "$DATA_ROOT" -name 'eval_jni_vs_gt.py' 2>/dev/null | head -1)
    [[ -z "$EVAL_SCRIPT" ]] && EVAL_SCRIPT=$(find "$PROJECT_ROOT" -name 'eval_jni_vs_gt.py' 2>/dev/null | head -1)
    if [[ -n "$EVAL_SCRIPT" ]]; then
        info "Forensic evaluation..."
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
        ok "Evaluation completed"
    else
        warn "eval_jni_vs_gt.py not found"
    fi
else
    warn "No JSONs to evaluate (or EVAL=false)"
fi

# Summary
echo ""
echo "====================================================================="
echo "  RUN COMPLETED: $RUN_ID"
echo "====================================================================="
echo "  Device:       $MODEL ($ARCH)"
echo "  CSV:          $CSV"
echo "  Images:       $TOTAL"
echo "  Push:         $PUSHED"
echo "  Provider:     $PROVIDER"
echo "  JSONs:        $JSON_COUNT"
echo "  Manifest:     ${REAL_MANIFEST:-NO}"
echo "  Evaluation:   $EVAL"
echo "  Run dir:      $RUN_DIR"
echo "====================================================================="
echo ""
