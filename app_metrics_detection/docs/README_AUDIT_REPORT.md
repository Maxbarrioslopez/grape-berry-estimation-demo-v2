# README Audit Report

## Scope
- Entire Android project under `app_metrics_detection/` including documentation and runtime UI.
- Focus areas: existing README files, documentation consistency, reproducible build/install on emulator, and visual evidence capture.

## Files Reviewed

| File | Status | Action Taken | Evidence |
|---|---:|---|---|
| app_metrics_detection/README.md | INCOMPLETE | No direct overwrite; preserved. Created supplemental docs/screenshots README and audit report. | Lines with placeholders such as `[PAPER_TITLE]` found. |
| app_metrics_detection/paper_demo_screenshots_2026-05-28/README.md | DUPLICATED / REMOVED | Removed from the public tree after consolidating the screenshots into `docs/screenshots/`. | Raw demo evidence duplicated the canonical screenshot set. |
| root README.md | OK | Reviewed; no changes applied. | references validated where applicable. |

## Changes Applied
- Created `app_metrics_detection/docs/screenshots/README.md` (English) documenting each captured screenshot, capture method, device, package id, and notes.
- Created `app_metrics_detection/docs/README_AUDIT_REPORT.md` (this file) summarizing the forensic audit.
- Consolidated the final screenshots into `app_metrics_detection/docs/screenshots/` and removed intermediate UI dump XML files from the working set.
- Removed the duplicated `app_metrics_detection/paper_demo_screenshots_2026-05-28/` evidence tree from the public release branch.
- Captured 25 screenshots into `app_metrics_detection/docs/screenshots/` without modifying code or ML models.

## Screenshots Captured
- Directory: `app_metrics_detection/docs/screenshots/`
- Count: 25 (see `docs/screenshots/README.md` for inventory and descriptions)

## Emulator / Build Evidence
- App package id: `com.gaiaspa.metrics_detection`
- Gradle task used: `./gradlew :app:assembleDebug` (executed from `app_metrics_detection`)
- APK path: `app_metrics_detection/app/build/outputs/apk/debug/app-debug.apk`
- Emulator detected: `Pixel_7` (AVD name), device id `emulator-5554`
- Install result: `adb uninstall com.gaiaspa.metrics_detection` → Success; `adb install -r app/build/outputs/apk/debug/app-debug.apk` → Success
- Capture method: `adb exec-out screencap -p > <file.png>` and `adb shell screencap` as fallback.
- Capture timestamp: 2026-05-28 (local timezone)

Commands executed (selected, chronological):
- `find . -iname "README.md" -o -iname "readme.md"`
- `rg`/`grep` checks on manifest and README placeholders
- `cd app_metrics_detection && ./gradlew :app:assembleDebug`
- `adb devices` and `adb devices -l`
- `adb uninstall com.gaiaspa.metrics_detection` (clean uninstall)
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` (install debug)
- `adb exec-out screencap -p > <path>` for each screenshot
- `adb shell uiautomator dump /sdcard/ui_dump.xml` and `adb shell cat /sdcard/ui_dump.xml` to extract UI bounds

## Non-Modified Areas (confirmed)
- ONNX model files and assets under `third_party/onnxruntime` and `app/src/main/assets/weights` were not modified.
- JNI/C++ sources under `app/src/main/cpp/` were not changed.
- Any ML thresholds, scalers, calibrators, or pipeline source files were not edited.
- Business logic preserved; only runtime interactions were performed via the emulator.

## Risks / Gaps Found
- `app_metrics_detection/README.md` contains unresolved placeholders (`[PAPER_TITLE]`, `[AUTHOR_NAME]`, etc.). This makes the top-level documentation incomplete for publication. Classified as INCOMPLETE.
- The public release branch now uses `docs/screenshots/` as the canonical evidence directory; the older `paper_demo_screenshots_2026-05-28/` tree was removed to avoid duplication and accidental publication of raw source images.

## Recommended Next Steps (prioritized)
1. Replace placeholders in `app_metrics_detection/README.md` with final paper metadata (author, title, year) when ready — **requires owner approval**.
2. Keep `docs/screenshots/README.md` as the single screenshot index and reference it from the main README when publishing.
3. Add a short `CONTRIBUTING.md` or `README_NOTES.md` that explains `DEMO_MODE` implications for reviewers (already documented here but useful to surface).

