# Grape Bunch Analysis — Android Offline-First System

## Academic Demonstration Version

This repository contains the source code and build configuration for an offline-first Android application that performs on-device analysis of grape bunches using computer vision and deep learning. The app accompanies the research manuscript:

> **An Offline-First Android System for Operational Analysis of Grape Bunches via On-Device Inference and Multi-View A/B Fusion**

The application processes RGB images of grape bunches, runs ONNX-based segmentation and regression models entirely on-device via C++/JNI, and produces structured predictions (berry count, mean diameter, mode, standard deviation, and size distribution histogram).

Core capabilities available without network access:

- image capture and gallery loading
- local inference using ONNX Runtime and OpenCV
- local persistence with Room
- history and detail views
- PDF export and sharing

---

## Repository Structure

```
├── README.md                          # This file
├── requirements.txt                   # Optional Python dependencies (analysis only)
├── .gitignore                         # Unified ignore rules
├── .gitattributes                     # Git LFS tracking rules
├── app_screenshots/                   # Paper/demo screenshot evidence (30 images + README)
│   └── README.md
│
└── app_metrics_detection/             # Main Android project (Gradle root)
    ├── README.md                      # Detailed build instructions and metadata
    ├── LICENSE                        # CC BY-NC-ND 4.0
    ├── TERMS_AND_CONDITIONS.md        # Usage terms
    ├── build.gradle.kts               # Root build configuration
    ├── settings.gradle.kts
    ├── gradle.properties
    ├── gradle/
    │   ├── libs.versions.toml         # Version catalog
    │   └── wrapper/
    ├── scripts/
    │   ├── prepare_native_deps_linux.sh   # Download ONNX Runtime + OpenCV (Linux)
    │   └── prepare_native_deps.ps1        # Download ONNX Runtime + OpenCV (Windows)
    ├── third_party/                   # Native libraries (downloaded by scripts)
    │   ├── onnxruntime/
    │   └── opencv/
    └── app/                           # Android application module
        ├── build.gradle.kts           # App build config (DEMO_MODE, deps, signing)
        ├── proguard-rules.pro
        └── src/
            ├── main/
            │   ├── AndroidManifest.xml
            │   ├── cpp/               # JNI/C++ pipeline (ONNX inference)
            │   ├── java/.../          # Kotlin source
            │   └── res/               # Android resources
            └── test/                  # Unit tests
```

---

## Key Versions

| Component | Version | Notes |
|-----------|---------|-------|
| Android SDK (compile/target) | 34 | — |
| Android SDK (min) | 28 | — |
| NDK | 26.x | Required for C++ compilation |
| CMake | 3.22.1+ | — |
| JDK | 17 | Recommended for AGP 8.x |
| Kotlin | 1.9.x | — |
| ONNX Runtime | 1.24.3 | Prebuilt AAR/JNI from third_party/ |
| OpenCV Android SDK | 4.x | Prebuilt SDK from third_party/ |
| Room | 2.5.2 | Local persistence |
| Retrofit | 2.9.0 | HTTP client (disabled in demo) |
| OkHttp | 4.9.1 | HTTP engine (disabled in demo) |
| WorkManager | 2.7.1 | Background sync (disabled in demo) |

---

## Alignment With The Paper

This README separates two kinds of statements:

- **Implementation facts** — verified against the current codebase.
- **Manuscript-reported results** — benchmark figures from the evaluation protocol described in the paper; not recomputed here.

---

## Verified In Code (Current App)

### Build mode and public demo behavior

- `DEMO_MODE` is enabled for both debug and release in `app_metrics_detection/app/build.gradle.kts`.
- In demo mode, the app shows the academic agreement at startup.
- The real login, register, and recovery UIs are preserved; authentication actions are intercepted locally (no backend call in the demo path).
- Cloud sync workers exit early in demo mode (cloud operations disabled).

### Inference and architecture

- Native ML path: Kotlin → JNI → C++ (`MetricsPipeline` → `nativeRunPipeline`).
- ONNX models are extracted to internal storage at app startup.
- Native dependencies: ONNX Runtime for Android and OpenCV Android SDK.
- The app stores data locally with Room and operates without connectivity.

### Multi-view A/B support

- The A/B workflow is present in the app capture flow (front/back handling).
- Pairwise fusion logic in `FusionEngine` computes fused quantity and statistics with safeguards.

---

## Manuscript-Reported Results

The draft paper reports final benchmark results (e.g., MAE reduction with A/B fusion, 548 images, 274 A/B pairs, 14.4% relative improvement). Those numbers come from the manuscript evaluation protocol and are not re-executed by this README.

---

## Screenshots

Canonical screenshot evidence is stored in:

- `app_screenshots/`
- `app_screenshots/README.md`

The README inside `app_screenshots/` documents what each image represents.

---

## Build And Run

### Prerequisites

- Android SDK 34
- NDK 26.x
- CMake 3.22.1+
- JDK 17 (recommended for AGP 8.x projects)

### Step 1 — Download native dependencies

The project requires ONNX Runtime and OpenCV native libraries placed under `third_party/`. Run the preparation script for your platform from `app_metrics_detection/`:

**Linux / macOS:**

```bash
cd app_metrics_detection
./scripts/prepare_native_deps_linux.sh
```

**Windows (PowerShell):**

```powershell
cd app_metrics_detection
powershell -ExecutionPolicy Bypass -File scripts/prepare_native_deps.ps1
```

These scripts download and extract ONNX Runtime for Android (v1.24.3) and the OpenCV Android SDK to the expected `third_party/` paths.

### Step 2 — Build debug APK

```bash
cd app_metrics_detection
./gradlew assembleDebug
```

### Step 3 — Install on device/emulator

```bash
cd app_metrics_detection
./gradlew installDebug
```

---

## Optional Python Tooling

The Android application does **not** require Python to build or run. The file `requirements.txt` pins optional dependencies used for offline analysis and reproducibility scripts described in the paper:

- **numpy, pandas, scipy** — numerical data manipulation
- **matplotlib, seaborn** — figure and visualization generation
- **statsmodels, openpyxl** — statistical tests and Excel data export
- **tqdm, python-dateutil** — progress reporting and date handling
- **pytest** — unit testing for analysis scripts

Install with:

```bash
pip install -r requirements.txt
```

---

## Repository Scope

Kept in this repository:

- Android source code
- JNI/C++ inference integration
- Native dependency preparation scripts (Linux/Windows)
- Screenshot evidence for the paper and demo

Removed as extraneous:

- Internal forensic and audit markdown reports
- Editor and machine-specific metadata files
- Unused Gradle dependencies (smile-core, boofcv, ucrop)
- Development-only scripts and debug batch activity
- Unused assets and duplicate model files

---

## Legacy Code Preserved (Intentionally Inactive)

The following packages are kept in the repository but **not active at runtime**.
They are preserved for documentation, future extensibility, and academic transparency.

| Package / File | Status | Reason Preserved |
|----------------|--------|------------------|
| `depth_estimation/` (MIDASModel, BitmapUtils, DrawingOverlay, Logger) | Commented out / unreferenced | MiDaS depth estimation pipeline (TensorFlow Lite). Replaced by the current ONNX/C++ path. Preserved as reference for potential future depth-based metrics. |
| `i18n/LanguagePreferenceManager.kt` | Gated behind `FEATURE_LANGUAGE_SWITCH=false` | Full multi-language support infrastructure. The app is currently English-only, but the i18n layer is fully implemented and can be reactivated by setting the feature flag to `true`. Includes language selector UI logic. |
| `model/` (legacy Lote, CalPredict, AuthState) | 0 references | Previous non-Room data models. Replaced by `data.model/` Room entities. Kept for historical reference of the data model evolution. |
| `data/local/delete/` (legacy converters) | 0 references | Previous Room type converters. Replaced by `data.local.Converter.kt`. Kept as reference for conversion strategies. |

**To reactivate i18n:** set `FeatureFlags.FEATURE_LANGUAGE_SWITCH = true` and provide translated `strings.xml` variants.

**To explore MiDaS:** uncomment `MIDASModel.kt` and add TensorFlow Lite dependencies back to `build.gradle.kts`.

---

## License

See:

- `app_metrics_detection/LICENSE`
- `app_metrics_detection/TERMS_AND_CONDITIONS.md`
