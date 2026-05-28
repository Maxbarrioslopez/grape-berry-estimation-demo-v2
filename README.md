# Metrics Detection

## Android Offline-First Grape Bunch Analysis (Academic Demo)

This repository contains an Android application for operational grape bunch analysis on-device.

The app is implemented as a Kotlin + JNI/C++ pipeline and is designed to keep core functionality available without network access:

- image capture/loading
- local inference using ONNX Runtime and OpenCV
- local persistence with Room
- history and detail views
- PDF export/share

## Alignment With The Paper

Draft manuscript title:

- An Android offline-first system for operational grape bunch analysis through on-device inference and multi-view A/B fusion

This README intentionally separates two kinds of statements:

- verified in code now (implementation facts)
- reported by manuscript experiments (paper results)

## Verified In Code (Current App)

### Build mode and public demo behavior

- `DEMO_MODE` is enabled for both debug and release in `app_metrics_detection/app/build.gradle.kts`.
- In demo mode, the app shows the academic agreement at startup.
- The real login/register/recovery UIs are preserved, but auth actions are intercepted locally (no backend auth call in demo path).
- Cloud sync worker exits early in demo mode (cloud operations disabled).

### Inference and architecture

- Native ML path is Kotlin -> JNI -> C++ (`MetricsPipeline` -> `nativeRunPipeline`).
- ONNX models are extracted to internal storage at app startup.
- Native dependencies are ONNX Runtime Android and OpenCV Android SDK.
- The app stores data locally with Room and can run without immediate connectivity.

### Multi-view A/B support

- A/B workflow is present in the app capture flow (front/back handling).
- Pairwise fusion logic exists in `FusionEngine` and computes fused quantity/statistics with safeguards.

## Manuscript-Reported Results (Not Recomputed Here)

The draft paper reports final benchmark results (for example MAE reduction with A/B fusion).
Those numbers come from the manuscript evaluation protocol and are not re-executed by this README.

If you publish quantitative numbers in this repository, include:

- dataset split details
- exact scripts/commands
- run artifacts and reproducible environment

## Screenshots (Do Not Remove)

Canonical screenshot evidence is stored in:

- `app_screenshots/`
- `app_screenshots/README.md`

`app_screenshots/README.md` documents what each image represents in English.

## Build And Run

### Prerequisites

- Android SDK 34
- NDK 26.x
- CMake 3.22.1+
- JDK 17 (recommended for AGP 8.x projects)

### Prepare native dependencies

From `app_metrics_detection/`:

Linux:

```bash
./scripts/prepare_native_deps_linux.sh
```

Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/prepare_native_deps.ps1
```

### Build debug APK

```bash
cd app_metrics_detection
./gradlew assembleDebug
```

### Install on device/emulator

```bash
cd app_metrics_detection
./gradlew installDebug
```

## Optional Python Tooling

The Android application itself does not require Python to build or run.

A pinned optional environment for analysis/repro tooling is provided in:

- `requirements.txt`

## Repository Scope

Kept in this repository:

- Android source code
- JNI/C++ inference integration
- native dependency preparation scripts (Linux/Windows)
- screenshot evidence for the paper/demo

Removed as legacy/noise:

- old forensic/audit markdown reports
- editor/machine-specific metadata files
- unused Gradle dependencies (smile-core, boofcv, ucrop)
- dev-only scripts and debug batch activity
- unused assets and duplicate model files

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

## License

See:

- `app_metrics_detection/LICENSE`
- `app_metrics_detection/TERMS_AND_CONDITIONS.md`
