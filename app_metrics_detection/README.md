# Grape Berry Size & Quantity Estimation — Android Application

## Academic Demonstration Version

This repository contains a **source-available academic demonstration version** of an Android application for on-device estimation of grape berry caliber (size) and quantity using computer vision and deep learning.

The application processes RGB images of grape bunches, runs ONNX-based segmentation and regression models entirely on-device via C++/JNI, and produces structured predictions (berry count, mean diameter, mode, standard deviation, and size distribution histogram).

---

## Research Context

An Android offline-first system for operational grape bunch analysis through on-device inference and multi-view A/B fusion  
ScaleAiLab - Universidad Adolfo Ibanez; Universidad de Vina del Mar; Universidad Tecnologica de Chile INACAP  
2026 (draft manuscript)

This implementation accompanies the draft research manuscript and serves as a reproducibility artifact, reference implementation, and demonstration of the on-device ML pipeline described in the paper.

---

## Demo Mode (Local-Only)

**The public version runs in local-only demo mode.** Backend authentication, cloud synchronization, remote storage, and production API calls are intentionally disabled.

| Feature | Status |
|---------|--------|
| Image capture (camera / gallery) | Enabled |
| On-device ML inference | Enabled |
| Berry size histogram | Enabled |
| Batch / bunch management | Enabled |
| Local Room database persistence | Enabled |
| PDF report generation | Enabled |
| User authentication | Disabled |
| Cloud synchronization | Disabled |
| Remote upload / download | Disabled |
| Backend API calls | Disabled |
| Periodic sync workers | Disabled |

**No username or password is required in demo mode.**

All analyses and saved records remain stored locally on the device only.

Every app launch presents a mandatory academic demonstration agreement that must be accepted before accessing the application. Acceptance is session-only and is never persisted.

---

## Authentication Interface Demonstration

The public academic build **preserves the original authentication interface and navigation flow** for architectural and UI demonstration purposes. Reviewers can inspect the real login, registration, and password recovery screens as they exist in the full production application.

| Screen | Behavior in Demo Mode |
|--------|-----------------------|
| Academic Agreement | Shown on every launch; acceptance required |
| Login | Real UI preserved; login button shows demo message and proceeds to main app |
| Registration | Real UI preserved; two-stage form visible; submit shows demo message |
| Password Recovery | Real UI preserved; fields visible; submit shows demo message |

**In DEMO_MODE, authentication actions are intercepted locally:**

- No backend communication occurs.
- No valid credentials are required.
- No tokens are written to encrypted storage.
- Registration and password recovery are presented as non-operational UI demonstrations.
- The original backend architecture remains preserved for future private/production reactivation.

This allows paper reviewers and the academic community to:

- Visually verify the authentication architecture described in the paper.
- Navigate the complete login, registration, and recovery flow.
- Inspect form layouts, validation rules, and UI structure.
- Confirm the app was designed for a real multi-tenant backend.

---

## How to Build and Run

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 34
- NDK 26.x (for native C++ compilation)
- CMake 3.22.1+
- Kotlin 1.9.x

### Third-Party Libraries

The project depends on two native libraries expected under `third_party/`:

- **ONNX Runtime** — `third_party/onnxruntime/onnxruntime-android-1.24.3/`
- **OpenCV Android SDK** — `third_party/opencv/OpenCV-android-sdk/`

If these directories are omitted from a slim public artifact due to size or licensing review, download the libraries from their official sources and place them in the expected directories before building.

### Build Steps

```bash
cd app_metrics_detection
./gradlew assembleDebug
```

### Install on Device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## DEMO_MODE Build Configuration

The public repository keeps every default build path in local-only demo mode. The mode is controlled by `buildConfigField` in `app/build.gradle.kts`:

```
// File: app/build.gradle.kts
buildTypes {
    debug {
        buildConfigField("boolean", "DEMO_MODE", "true")   // Academic demo
    }
    release {
        buildConfigField("boolean", "DEMO_MODE", "true")   // Public academic demo release
        // ...
    }
}
```

Do not publish a public build with `DEMO_MODE=false`.

---

## Backend Reactivation

Backend code is preserved but disabled by `DEMO_MODE`. To reactivate backend behavior, set `DEMO_MODE=false` in the intended private/production build configuration, restore private signing/backend configuration outside the public repository, set the correct backend base URL, and validate login, token refresh, upload, download, and sync flows.

Public repository files intentionally use a placeholder backend URL and do not include real credentials, signing keys, service-account files, or private endpoint configuration.

---

## Architecture Overview

```
User → Camera/Gallery → Image Pipeline → ONNX Inference (C++/JNI) → Room DB ↔ UI
                                                                        ↕
                                                               SyncWorker (disabled in demo)
```

- **ML pipeline**: C++ via JNI with ONNX Runtime for segmentation (grape detection) and regression (quantity/caliber estimation).
- **Local storage**: Room database for batch/bunch metadata, image paths, and prediction results.
- **UI**: Android Views with Material Design, Bottom Navigation (Home, History, Profile, Support).
- **Networking**: Retrofit + OkHttp (preserved but guarded by `DEMO_MODE` flag).
- **Sync**: WorkManager-based periodic and manual sync (skipped in demo mode).

---

## Repository Safety Notes

The following have been **excluded or redacted** from this public repository:

- Signing keys (`keystore.properties`, `.jks`, `.keystore` files)
- Production backend URL (replaced with a placeholder in `ApiClient.kt`)
- Google/Firebase service account credentials
- Test datasets and image collections
- Debug output, private batch-processing results, and local-only datasets
- User data, local database dumps, and captured screenshots

Do not upload `_not_for_public_repo/`, `keystores/`, `keystore.properties`, `local.properties`, `.env*`, `google-services.json`, service-account JSON files, `images/`, or `paper_demo_screenshots_*/`. Use `keystore.properties.example` as the public signing template; `local.properties` should be generated locally by Android Studio or the Android SDK tooling.

---

## License and Terms

### Code License

This repository is provided under the **Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International** (CC BY-NC-ND 4.0) license.

See [LICENSE](./LICENSE) for the full legal text.

In summary:

- **Attribution required** — cite the research paper and authors.
- **Non-commercial use only** — no commercial deployment or sale.
- **No derivatives** — redistribution of modified versions is not permitted.
- **No warranty** — provided "as is" without warranty of any kind.

### Academic Use

If you use this code or the accompanying research in an academic context, please cite:

```
Soto, M.; Barrios, M.; Ormeno-Arriagada, P.; Vasquez, J. "An Android offline-first system for operational grape bunch analysis through on-device inference and multi-view A/B fusion". Draft manuscript, 2026.
```

### Commercial Use

Commercial use requires prior written permission from the authors. Contact jorge.vasquez.a@uai.cl for inquiries.

### Terms and Conditions

Please review [TERMS_AND_CONDITIONS.md](./TERMS_AND_CONDITIONS.md) before using this software.

This license notice should be reviewed by the project owner or legal advisor before formal publication. Creative Commons licenses are not always ideal for source code.

---

## Disclaimer

**This is not a production-ready system.** It is an academic research prototype provided for reproducibility and demonstration purposes only.

- Do not deploy this software in production agricultural or commercial environments without proper validation, security hardening, and infrastructure setup.
- The accuracy, reliability, and performance figures correspond to the specific research conditions described in the associated paper.
- The authors make no claims about the suitability of this software for any particular purpose beyond academic research and demonstration.

---

## Contact

Jorge Vasquez (corresponding author)  
Facultad de Ingenieria y Ciencias, Universidad Adolfo Ibanez  
jorge.vasquez.a@uai.cl
