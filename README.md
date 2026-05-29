# Grape Bunch Analysis — Android Offline-First System

[![License: CC BY-NC-ND 4.0](https://img.shields.io/badge/License-CC%20BY--NC--ND%204.0-lightgrey.svg)](https://creativecommons.org/licenses/by-nc-nd/4.0/)
[![Paper Status](https://img.shields.io/badge/Paper-Draft%20Manuscript-informational)](#alignment-with-the-paper)
[![Android](https://img.shields.io/badge/Android-34-green.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blueviolet.svg)]()
[![ONNX Runtime](https://img.shields.io/badge/ONNX_Runtime-1.24.3-orange.svg)]()

## Quick Start

```bash
# 1. Clone
git clone https://github.com/Maxbarrioslopez/grape-berry-estimation-demo-v2.git
cd grape-berry-estimation-demo-v2/app_metrics_detection

# 2. Download native dependencies (ONNX Runtime + OpenCV)
./scripts/prepare_native_deps_linux.sh   # Linux/macOS
# powershell -ExecutionPolicy Bypass -File scripts/prepare_native_deps.ps1  # Windows

# 3. Build & install
./gradlew installDebug
```

> The debug APK builds with `DEMO_MODE=true` — no backend, no credentials, local-only inference. Launch the app, accept the academic agreement, tap the Login button, and start analyzing grape bunches.

---

## About

This repository contains the source code for an **offline-first Android application** that performs on-device grape bunch analysis using computer vision and deep learning. The app accompanies the research manuscript:

> **An Offline-First Android System for Operational Analysis of Grape Bunches via On-Device Inference and Multi-View A/B Fusion**  
> Soto, M.; Barrios, M.; Ormeño-Arriagada, P.; Vasquez, J. — Draft manuscript, 2026

The system processes RGB images of grape bunches through a Kotlin/JNI/C++ pipeline, runs ONNX-based segmentation and regression models entirely on-device, and produces structured predictions: berry count, mean diameter, mode, standard deviation, and size distribution histogram.

**Core capabilities without network access:**
- Image capture (camera) and gallery loading
- Local inference using ONNX Runtime and OpenCV via JNI
- Local persistence with Room (SQLite)
- History and detail views with histogram visualization
- PDF report generation and sharing

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    ANDROID APPLICATION                       │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────┐  │
│  │  Camera  │  │  Gallery │  │  Login   │  │  Settings  │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬───────┘  │
│       │              │             │              │          │
│  ┌────▼──────────────▼─────────────▼──────────────▼───────┐ │
│  │                 HOME VIEWMODEL                          │ │
│  │  Image capture → Normalize → Pipeline → Save → Sync    │ │
│  └────┬────────────────────────────────────────────┬──────┘ │
│       │                                             │        │
│  ┌────▼──────────┐                          ┌──────▼──────┐ │
│  │  KOTLIN LAYER │                          │  ROOM DB    │ │
│  │ MetricsPipeline│                         │ Lote + Cal  │ │
│  │  ImageUtils   │                          │  Predict    │ │
│  └────┬──────────┘                          └─────────────┘ │
│       │ JNI call                                            │
│  ┌────▼──────────────────────────────────────────────────┐  │
│  │              C++ NATIVE LAYER (JNI)                    │  │
│  │  ┌────────────┐  ┌──────────┐  ┌───────────────────┐  │  │
│  │  │ Preprocess │  │ ONNX Inf │  │ Postprocess       │  │  │
│  │  │ (OpenCV)   │→ │ (ONNX RT)│→ │ (Qty, Hist, Draw) │  │  │
│  │  └────────────┘  └──────────┘  └───────────────────┘  │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  NETWORK LAYER (disabled in DEMO_MODE)                │   │
│  │  Retrofit → AuthInterceptor → TokenAuthenticator     │   │
│  │  SyncWorker → Upload / Download / Delete              │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## ONNX Models

| Model | File | Size | Purpose |
|-------|------|------|---------|
| Segmentation | `weights/modelos/legacy/seg_best.onnx` | 11.0 MB | Instance segmentation — detects individual berries |
| Quantity Regression | `weights/modelos/qty_model_rgbdt.onnx` | 436 KB | Predicts berry count from RGBDT representation |
| Quantity Weights | `weights/modelos/qty_model_rgbdt.onnx.data` | 4.4 MB | External data weights for quantity model |
| Histogram | `weights/modelos/hist_rgbdt_bimodal.onnx` | 461 KB | Predicts caliber (berry size) distribution |
| Histogram Weights | `weights/modelos/hist_rgbdt_bimodal.onnx.data` | 4.4 MB | External data weights for histogram model |

All models are extracted from APK assets to internal storage (`filesDir/weights/`) at first launch. Model training and export are described in a companion manuscript currently under review [1]. This repository consumes the exported ONNX artifacts and focuses on their mobile integration.

---

## Paper Results (Verified From Final Evaluation Run)

### Global Metrics — 548 Images, 274 A/B Pairs

| Metric | Single-Image | Fused A/B | Improvement |
|--------|:-----------:|:---------:|:-----------:|
| MAE (berry count) | 5.50 | 4.71 | −14.4% |
| RMSE | 10.23 | 9.43 | −7.8% |
| Median Absolute Error | 3.60 | 2.84 | −21.3% |
| W1 Distance (caliber, mm) | 1.209 | 1.174 | −2.9% |

> Wilcoxon signed-rank test: W = 2184, p = 1.94 × 10⁻²¹ (paired, N = 274)

### Latency (on-device, median)

| Mode | p50 (ms) | p95 (ms) |
|------|:--------:|:--------:|
| Single-image inference | 2893 | 3548 |
| Fused A/B (2 inferences) | 5786 | 7029 |

### Per-Variety Breakdown (Fused A/B, 12 varieties)

| Variety | Pairs | MAE | W1 (mm) |
|---------|:-----:|:---:|:-------:|
| Magenta | 30 | 3.39 | 0.979 |
| Red Globe | 30 | 4.65 | 1.042 |
| Autumn Crisp | 30 | 5.31 | 1.082 |
| Sweet Globe | 30 | 5.47 | 0.768 |
| Scarlotta | 24 | 5.77 | 0.623 |
| Allison | 30 | 5.84 | 0.898 |
| Timpson | 22 | 5.39 | 0.706 |
| Crimson | 30 | 6.80 | 0.511 |
| Thompson | 22 | 8.84 | 0.801 |
| Ivory | 25 | 8.49 | 1.040 |
| Superior | 25 | 8.56 | 0.913 |
| Timco | 30 | 8.78 | 0.767 |

> Single-image and per-variety full tables available in the manuscript.

---

## Citation

If you use this code or the accompanying research in an academic context, please cite:

### BibTeX

```bibtex
@article{soto2026grape,
  title   = {An Offline-First {Android} System for Operational Analysis of
             Grape Bunches via On-Device Inference and Multi-View {A/B} Fusion},
  author  = {Soto, Matias and Barrios, Maximiliano and
             Orme{\~n}o-Arriagada, Pablo and Vasquez, Jorge},
  year    = {2026},
  note    = {Draft manuscript}
}
```

### Plain Text

```
Soto, M.; Barrios, M.; Ormeño-Arriagada, P.; Vasquez, J.
"An Offline-First Android System for Operational Analysis of Grape Bunches
 via On-Device Inference and Multi-View A/B Fusion".
ScaleAiLab — Universidad Adolfo Ibáñez; Universidad de Viña del Mar;
Universidad Tecnológica de Chile INACAP. Draft manuscript, 2026.
```

---

## Repository Structure

```
├── README.md                          # This file
├── requirements.txt                   # Optional Python deps (analysis only)
├── .gitignore                         # Unified ignore rules
├── .gitattributes                     # Git LFS tracking rules
├── app_screenshots/                   # 30 screenshots + README
│   └── README.md
│
└── app_metrics_detection/             # Android project (Gradle root)
    ├── README.md                      # Build instructions + metadata
    ├── LICENSE                        # CC BY-NC-ND 4.0
    ├── TERMS_AND_CONDITIONS.md
    ├── build.gradle.kts
    ├── settings.gradle.kts
    ├── gradle.properties
    ├── gradle/
    │   ├── libs.versions.toml         # Version catalog
    │   └── wrapper/
    ├── scripts/
    │   ├── prepare_native_deps_linux.sh
    │   └── prepare_native_deps.ps1
    ├── third_party/                   # Downloaded by scripts
    │   ├── onnxruntime/ (v1.24.3)
    │   └── opencv/ (Android SDK 4.x)
    └── app/
        ├── build.gradle.kts
        ├── proguard-rules.pro
        └── src/
            ├── main/
            │   ├── AndroidManifest.xml
            │   ├── cpp/               # JNI/C++ ONNX pipeline
            │   ├── java/.../          # Kotlin (com.gaiaspa.metrics_detection)
            │   └── res/               # Android resources
            └── test/                  # Unit tests
```

---

## Key Versions

| Component | Version |
|-----------|---------|
| Android SDK (compile/target) | 34 |
| Android SDK (min) | 28 |
| NDK | 26.x |
| CMake | 3.22.1+ |
| JDK | 17 |
| Kotlin | 1.9.x |
| ONNX Runtime for Android | 1.24.3 |
| OpenCV Android SDK | 4.x |
| Room | 2.5.2 |
| Retrofit | 2.9.0 |
| OkHttp | 4.9.1 |
| WorkManager | 2.7.1 |

---

## Verified In Code (Current App)

### Demo mode

- `DEMO_MODE` is enabled for both debug and release in `app/build.gradle.kts`.
- Academic agreement shown on every app launch (session-only, never persisted).
- Real login/register/recovery UIs preserved; authentication intercepted locally.
- Cloud sync workers exit early; no backend communication in demo mode.

### Inference

- Native ML path: Kotlin → JNI → C++ (`MetricsPipeline` → `nativeRunPipeline`).
- ONNX models extracted to internal storage at first launch.
- Inference runs on CPU via ONNX Runtime (no GPU delegate required).

### A/B fusion

- Front/back capture workflow present in the UI.
- Pairwise fusion in `FusionEngine` averages quantities, statistics, and histograms.
- Disagreement metric computed per pair as a quality indicator.

---

## Build From Source

### Prerequisites

- Android SDK 34
- NDK 26.x
- CMake 3.22.1+
- JDK 17
- ~2 GB free disk space (third-party libraries)

### Hardware Requirements

| Component | Minimum | Recommended |
|-----------|---------|-------------|
| RAM | 4 GB | 8 GB |
| Storage (app) | ~200 MB | — |
| Android version | 9.0 (API 28) | 14+ (API 34) |
| CPU | arm64-v8a | Snapdragon 8-series or equivalent |

Devices tested: Pixel 7 (Android emulator), Xiaomi 13T (physical), Samsung Galaxy S21 (physical).

### Step 1 — Download native dependencies

```bash
cd app_metrics_detection
./scripts/prepare_native_deps_linux.sh      # Linux
# or: powershell -File scripts/prepare_native_deps.ps1   # Windows
```

### Step 2 — Build

```bash
./gradlew assembleDebug     # Debug APK with DEMO_MODE=true
./gradlew assembleRelease   # Release APK (requires signing keys)
```

### Step 3 — Install

```bash
./gradlew installDebug
# or: adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Optional Python Tooling

The Android application does **not** require Python. `requirements.txt` pins optional dependencies for offline analysis and reproducibility:

```bash
pip install -r requirements.txt
```

| Package | Purpose |
|---------|---------|
| numpy, pandas, scipy | Numerical analysis and data manipulation |
| matplotlib, seaborn | Figure generation |
| statsmodels, openpyxl | Statistical tests and Excel export |
| tqdm, python-dateutil | Progress reporting, date parsing |
| pytest | Unit testing for analysis scripts |

---

## Known Limitations

- **iOS not supported.** The JNI/C++ pipeline targets Android NDK only.
- **CPU inference.** ONNX Runtime runs on CPU; no GPU or NNAPI delegate configured.
- **Lighting sensitivity.** Performance degrades under harsh shadows or direct sunlight.
- **Occlusion.** Heavily occluded bunches may produce undercounts; the A/B fusion strategy mitigates but does not eliminate this.
- **Model generalization.** Models were trained on 12 table-grape varieties under Chilean field conditions. Performance on other varieties or environments is not guaranteed.
- **Single-bunch capture.** The pipeline expects one bunch per image pair. Multi-bunch scenes are not supported.
- **No real-time video.** Inference runs on still images (JPEG capture), not video streams.

---

## Troubleshooting

| Symptom | Likely Cause | Solution |
|---------|-------------|----------|
| `CMake not found` | CMake not installed or wrong version | Install CMake 3.22.1+ or set path in `local.properties` |
| `NDK not found` | NDK not installed via SDK Manager | Install NDK 26.x from Android Studio → SDK Manager |
| `Models not found on disk` | First launch failed to extract assets | Clear app data and relaunch; check device storage |
| `Native library not loaded` | Wrong ABI or missing .so files | Ensure `third_party/onnxruntime/jni/<abi>/libonnxruntime.so` exists |
| `Build fails with kapt errors` | Stale Gradle cache | `./gradlew clean` then rebuild |
| `Clone fails with LFS errors` | Git LFS not installed | `git lfs install && git lfs pull` |
| `App shows blank screen` | Model extraction still in progress | Wait 5-10 seconds on first launch |

---

## Legacy Code Preserved (Intentionally Inactive)

These packages are kept for documentation, extensibility, and academic transparency:

| Package | Status | Purpose |
|---------|--------|---------|
| `depth_estimation/` | Commented out | MiDaS depth pipeline (TFLite). Replaced by ONNX/C++. Preserved for future depth metrics. |
| `i18n/LanguagePreferenceManager.kt` | Gated (`FEATURE_LANGUAGE_SWITCH=false`) | Full i18n infrastructure. Reactivate flag + provide translated strings. |
| `model/` (legacy Lote, CalPredict, AuthState) | 0 references | Pre-Room data models. Replaced by `data.model/` Room entities. |
| `data/local/delete/` | 0 references | Legacy Room converters. Replaced by `data.local.Converter.kt`. |

---

## License

This repository is provided under **CC BY-NC-ND 4.0** (Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International).

- **Attribution required** — cite the paper and authors.
- **Non-commercial use only** — no commercial deployment or sale.
- **No derivatives** — redistribution of modified versions is not permitted.
- **No warranty** — provided "as is."

Full license: [`app_metrics_detection/LICENSE`](app_metrics_detection/LICENSE)  
Terms: [`app_metrics_detection/TERMS_AND_CONDITIONS.md`](app_metrics_detection/TERMS_AND_CONDITIONS.md)

**Contact:** Jorge Vasquez — jorge.vasquez.a@uai.cl  
Facultad de Ingeniería y Ciencias, Universidad Adolfo Ibáñez, Santiago, Chile
