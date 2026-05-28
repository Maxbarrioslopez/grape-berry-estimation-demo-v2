# Verification: Evaluation Script vs Android App Inference Pipeline

## Confirmed

| # | Claim | Evidence |
|---|-------|----------|
| 1 | Scripts do NOT re-implement inference | `eval_jni_vs_gt.py:113` — loads Android JSONs via `load_android_results()`, reads `manifest.json`, parses per-image JSON with `extract_android_fields()`; zero ONNX, OpenCV, or tensor code |
| 2 | Scripts invoke the real Android app | `run_forensic_eval.py:459–466` — launches `DebugBatchActivity` via `am start -S -n <package>/.DebugBatchActivity` with `--es inputDir/--es outputDir/--es provider`; `run_kotlin_emulator_batch.ps1:42–43` — same pattern via PowerShell `Invoke-Adb shell am start` |
| 3 | Results come from `MetricsPipeline.kt` | `BatchProcessor.kt:135` — creates `MetricsPipeline(context, providerPreference)`; line `217` — calls `pipeline.invokeFromFile(imagePath, smoothEdges=true, varietyId=...)` per image |
| 4 | `MetricsPipeline` crosses `CppPipelineBridge.nativeRunPipeline` | `MetricsPipeline.kt:80–91` — `cppBridge.invoke(...)` which calls `nativeRunPipeline(...)` at line `123` |
| 5 | JNI reaches `GrapePipelineCore::Run` | `grape_pipeline_jni.cpp:171–189` — constructs `GrapePipelineCore core(...)` and calls `core.Run(image_path, ...)` |
| 6 | Full C++ pipeline executed per image | `grape_pipeline_core.cpp:77–211` — `GrapePipelineCore::Run()` runs preprocess + segmentation + QTY ONNX + HIST ONNX + postprocess + overlay |
| 7 | Models used are identical | `MetricsPipeline.kt:36–38` — `segModelFile = "seg_best.onnx"`, `regModelFile = "qty_model_rgbdt.onnx"`; `grape_pipeline_config.hpp:43–44` — `kOfficialHistModel = "hist_rgbdt_bimodal.onnx"`, `kOfficialQtyModel = "qty_model_rgbdt.onnx"`; `grape_pipeline_onnx.cpp:91` — `"Bundle oficial fijo: qty_model_rgbdt.onnx + hist_rgbdt_bimodal.onnx."` |
| 8 | RGBDT construction is C++ only | `grape_pipeline_preprocess.cpp:504–528` — assembles 5-channel tensor: RGB planes + `grapes_dt_instance_lb` (channel 3) + `pingpong_dt_instance_lb` (channel 4); `grape_pipeline_core.cpp:131–143` — feeds `inputs.x` as the tensor to QTY and HIST |
| 9 | Thresholds, letterbox, DT are C++ only | `grape_pipeline_config.hpp:16–18` — `kDefaultImgSize=512`, `kDefaultSegConfThreshold=0.25f`, `kDefaultSegMaskThreshold=0.50f`; `grape_pipeline_preprocess.cpp:224–263` — `Letterbox()` with `new_width=512`, `new_height=512`; `grape_pipeline_preprocess.cpp:296–330` — `ComputeInstanceWiseDistanceTransform()` with `DIST_L2, maskSize=5` |
| 10 | Postprocess is C++ only | `grape_pipeline_postprocess.cpp:696–734` — `ComputeHistogramMean`, `ComputeHistogramMode`, `ComputeHistogramStd`, `NormalizeHistogram`; `grape_pipeline_core.cpp:148–187` — QTY output → `result.count_total`, HIST output → `result.hist_prob` → mean/mode/std |
| 11 | JSON contract matches | `grape_pipeline_postprocess.cpp:942–1026` — `PipelineResultToJson` emits `count_total`, `mean`, `mode`, `std`, `hist_prob`, `pred` (count_pred_by_bin_int), `bins`, `seg_count_base`, `detections`, `inf_ms`; `eval_jni_vs_gt.py:174–206` — `extract_android_fields()` reads exactly those keys |
| 12 | Provider flows from script to native | `run_forensic_eval.py:463` — `"--es provider {args.provider}"`; `DebugBatchActivity.kt:56–58` — reads intent extra `provider`; `BatchProcessor.kt:135` — passes to `MetricsPipeline(providerPreference=...)`; `MetricsPipeline.kt:87` — passes `providerPreference` to `cppBridge.invoke(...)`; `grape_pipeline_jni.cpp:161` — `ParseProviderPreference(provider_raw)` → `EnsureSessionsLoaded` → `CreateSession()` uses NNAPI or CPU |

---

## Inference Path

```
run_forensic_eval.py (or eval_jni_vs_gt.py)
    │
    ├─ [--android-run] → adb install → adb push images
    ├─ adb shell am start → DebugBatchActivity.kt
    │       └─ BatchProcessor.kt::run()
    │              └─ for each image:
    │                    MetricsPipeline.invokeFromFile()
    │                      → CppPipelineBridge.nativeRunPipeline (Kotlin/JNI)
    │                         → grape_pipeline_jni.cpp::nativeRunPipeline
    │                            → GrapePipelineCore::Run()
    │                               ├─ BuildPipelineInputs()
    │                               │    ├─ RunSegmentationPipeline() [seg_best.onnx]
    │                               │    │    ├─ Letterbox(512×512)
    │                               │    │    ├─ ImageToRgbFloatChw()
    │                               │    │    ├─ session.Run() [ONNX]
    │                               │    │    ├─ DecodeLegacySegmentation()
    │                               │    │    ├─ ReconstructLegacyMasks()
    │                               │    │    └─ ComputeInstanceWiseDistanceTransform()
    │                               │    └─ Assemble 5-channel RGBDT tensor
    │                               │         [R, G, B, DT_grapes, DT_pingpong]
    │                               ├─ RunFloatVectorModel(QTY) [qty_model_rgbdt.onnx]
    │                               │    → result.count_total
    │                               ├─ RunFloatVectorModel(HIST) [hist_rgbdt_bimodal.onnx]
    │                               │    → result.hist_prob → mean, mode, std
    │                               ├─ SaveDebugArtifacts()
    │                               ├─ SaveVisualOverlay() (if path provided)
    │                               └─ PipelineResultToJson() → JSON string
    │
    ├─ adb pull → local run_dir/*.json + manifest.json
    │
    └─ evaluate:
          load_android_results()     → parse manifest.json + per-image JSONs
          load_ground_truth()        → parse GT CSV
          perform_matching()         → basename matching
          evaluate_single_image()    → per-image: MAE, RMSE, bias, W1, fidelidad
          compute_fused_ab()         → A/B pair fusion
          compare_single_vs_fused()   → improvement analysis
          generate_full_report()     → Markdown report
          generate_summary_json()    → resumen_metricas.json
          export_by_variety()        → per-variety CSVs/JSONs/reports
```

---

## What the Scripts Do

### Batch Orchestration (`run_forensic_eval.py`)
- Builds debug APK (`./gradlew assembleDebug` at `line 360`)
- Installs APK on device (`adb install -r -d` at `lines 370–377`)
- Detects device via ADB, selects non-emulator serial (`detect_device()` at `lines 200–230`)
- Pushes image inventory to device (`adb push` at `line 434`)
- Launches `DebugBatchActivity` with intent extras (`am start` at `lines 459–464`)
- Polls device for JSON completeness (`lines 479–503`)
- Pulls results via `adb pull` (`lines 517–522`)
- Can also evaluate a pre-existing local run (`--input-run-dir` at `line 1188`)

### Ground-Truth Matching
- Loads GT CSV (`validate_gt_csv()` at `line 242`)
- Normalizes match keys via basename (`basename_key()` at `line 286` — lowercases, strips, uses leaf filename)
- Matches Android records to GT rows (`perform_matching()` at `line 677`, reuses `eval_jni_vs_gt.perform_matching()`)

### Per-Image Evaluation
- Extracts fields from Android JSON (`evaluate_single_image()` at `line 682`, calls forward to `base_eval.evaluate_single_image()`)
- Computes MAE, RMSE, bias, median error, MAPE, W1 distance, fidelidad
- Collects latency, throttling, EXIF metadata

### A/B Pair Validation
- `audit_csv_pairs()` in `eval_jni_vs_gt.py:427` — validates perfect A+B pairs per `unique_bunch_id`

### Fused A/B Evaluation
- `compute_fused_ab()` in `eval_jni_vs_gt.py:517` — averages A-side and B-side predictions per bunch
- Validates same variety, same `ref_base`, same status

### Metrics/Statistics
- `compute_global_metrics()` at `run_forensic_eval.py:762` — MAE, RMSE, bias, MAPE, W1, fidelidad, latency stats
- `compute_metrics_by_variety()` at `line 792` — per-variety aggregation
- `compute_fused_metrics()` at `line 832` — fused-pair statistics
- `build_improvement_buckets()` at `line 900` — buckets of "mejora extrema", "mejora moderada", "empeora", etc.

### Figure Generation
- `generate_full_report()` in `eval_jni_vs_gt.py:833` — comprehensive Markdown report with tables
- `write_summary_md()` in `run_forensic_eval.py:1062` — Markdown summary
- Per-variety reports (`export_by_variety()` in `eval_jni_vs_gt.py:1178`)

### Previous-Run Comparison
- `compare_previous_run()` at `run_forensic_eval.py:985` — delta analysis between current and previous run summaries

---

## What the Scripts Do NOT Do

- Do **NOT** re-implement ONNX inference in Python — zero `onnxruntime`, zero `numpy` tensor construction, zero `cv2.imread` for pipeline input
- Do **NOT** re-implement the RGBDT-preprocessing pipeline — the 5-channel tensor assembly lives exclusively in `grape_pipeline_preprocess.cpp:504–528`
- Do **NOT** re-implement segmentation — `seg_best.onnx` is loaded and executed by `RunSegmentationPipeline()` in `grape_pipeline_preprocess.cpp:332–436` (C++/ONNX Runtime)
- Do **NOT** re-implement the QTY model — runs via `RunFloatVectorModel(qty_session_, ...)` in `grape_pipeline_core.cpp:149`
- Do **NOT** re-implement the HIST model — runs via `RunFloatVectorModel(hist_session_, ...)` in `grape_pipeline_core.cpp:168`
- Do **NOT** re-implement postprocessing — `ComputeHistogramMean`, `ComputeHistogramMode`, `ComputeHistogramStd`, `NormalizeHistogram`, `HistogramToIntegers` are all in `grape_pipeline_postprocess.cpp:696–788`
- Do **NOT** re-implement letterbox or distance transform — `Letterbox()` in `grape_pipeline_preprocess.cpp:224` and `ComputeInstanceWiseDistanceTransform()` in `grape_pipeline_preprocess.cpp:296`
- Do **NOT** re-implement overlay generation — `SaveVisualOverlay()` in `grape_pipeline_postprocess.cpp:871`
- Do **NOT** feed model inputs or decode model outputs in Python

---

## Risk of Mismatch

| Area | Risk | Detail |
|------|------|--------|
| Model paths | **None** | Script passes model paths *only* to the app via intent extras; the app's `MetricsPipeline.kt` uses the same `seg_best.onnx` and `qty_model_rgbdt.onnx`; `hist_rgbdt_bimodal.onnx` is resolved internally by C++ `ResolveBundle()` at `grape_pipeline_onnx.cpp:95`. Script never references model paths directly. |
| ONNX provider | **Traceable** | Script passes `--provider` to `run_forensic_eval.py:1195` (choices: `auto`, `cpu`, `nnapi`); flows via `am start --es provider` → `DebugBatchActivity.kt:56` → `BatchProcessor.kt:135` → `MetricsPipeline.kt:87` → JNI → `ParseProviderPreference()` → `CreateSession()` in `grape_pipeline_onnx.cpp:116`. Non-deterministic only on emulator where `auto` is forced to `cpu` (`run_forensic_eval.py:398–399`). |
| Thresholds | **None** | `kDefaultSegConfThreshold=0.25f`, `kDefaultSegMaskThreshold=0.50f`, `kDefaultImgSize=512` are compile-time constants in `grape_pipeline_config.hpp:16–18`. Both the user-facing UI and `DebugBatchActivity` share the same compiled C++ binary. |
| Overlay generation | **Mismatch possible** | `BatchProcessor.kt:118` invokes `pipeline.invokeFromFile(...)` **without** `visualOverlayBase` parameter (defaults to `null`). The JNI passes an empty `visualOverlayPath` string (`grape_pipeline_jni.cpp:131` — `visualOverlayPath ?: ""`). In `GrapePipelineCore::Run()` at `grape_pipeline_core.cpp:195`, the condition `if (!visual_overlay_path.empty())` prevents overlay generation. However, the **production** app path (user-facing UI) may pass a non-empty `visualOverlayPath` to generate overlays. This does **not affect** inference results — overlays are purely visual and do not alter count/caliber output. |
| Debug artifacts | **Conditional** | `saveDebugArtifacts` is `JNI_FALSE` in the batch path (Kotlin-side: `MetricsPipeline.kt:88` passes `useDepth=false`, and `BatchProcessor.kt` does not request debug mode). In C++ `grape_pipeline_core.cpp:93`, `BuildDefaultDebugArtifacts(image_path, false)` is called — the `enabled=false` flag skips all `SaveDebugArtifacts()` writing at line 814. This adds no overhead to inference. |
| A/B fusion | **By design outside the app** | The app processes one image at a time (single-view inference only). A/B fusion (averaging predictions for two views of the same bunch) is performed exclusively in the Python script at `compute_fused_ab()` in `eval_jni_vs_gt.py:517`. This is an intentional post-hoc analysis, not a re-implementation of inference. |
| Variety inference | **Same algorithm, different place** | C++ `InferVarietyFromFilename()` in `grape_pipeline_config.hpp:88` and Kotlin `inferVarietyId()` in `BatchProcessor.kt:280` both use the filename to derive variety. The Kotlin layer may use `RuntimeVarietyCatalog.idOrNull()` which could differ from C++ hardcoded mapping, but this only affects the `variety_idx` passed to the model, not the tensor construction. |
| Histogram bins | **Identical** | C++ uses `kBins = {7,8,...,32}` (26 bins) in `grape_pipeline_config.hpp:22–25`. Python evaluator uses `BINS = np.arange(7, 33)` in `eval_jni_vs_gt.py:27`. Both are `[7,32]` inclusive. |
| Wasserstein implementation | **Independent but equivalent** | Python computes W1 in `eval_jni_vs_gt.py:33–53` from CDFs of predicted and GT histograms. The C++ pipeline does not compute W1 — it only produces histograms. The Python W1 is a pure metric computation on C++ output, not a re-implementation of any C++ logic. |
| `inf_ms` calculation | **Possible discrepancy** | C++ issues `PipelineResultToJson()` at `grape_pipeline_jni.cpp:196` which calls `DerivedInfMs()` at `grape_pipeline_postprocess.cpp:33` (sum of `preprocess_ms + qty_ms + hist_ms`). The JNI also wraps timing at `grape_pipeline_jni.cpp:181–194` but only uses it if `result.timing.total_ms <= 0.0` (line 191–192). The batch processor calls `pipeline.invokeFromFile()` which returns `Success.interfaceTime = root.optLong("inf_ms")` (`MetricsPipeline.kt:185`). The `BatchProcessor.buildFileJson()` writes `"inference_ms": native.optLong("inf_ms", 0)` at line `266`. Meanwhile, the Kotlin `Success` class also holds `preProcessTime=0` and `postProcessTime=0` (hardcoded zeros at lines 184, 187). The evaluator reads `'inference_ms'` from this field. This is consistent but partial — it excludes Kotlin-side JNI marshalling and JSON parsing overhead. |

---

## Paper-Ready Conclusion

The evaluation scripts `run_forensic_eval.py`, `eval_jni_vs_gt.py`, and `run_kotlin_emulator_batch.ps1` constitute a **pure evaluation harness**, not an alternative inference pipeline. Every prediction used for quantitative comparison with ground truth originates from the Android application's production inference path: `DebugBatchActivity` → `BatchProcessor` → `MetricsPipeline.invokeFromFile()` → `CppPipelineBridge.nativeRunPipeline()` (JNI) → `GrapePipelineCore::Run()`, which natively executes the same three ONNX models (`seg_best.onnx`, `qty_model_rgbdt.onnx`, `hist_rgbdt_bimodal.onnx`), the same 5-channel RGBDT tensor construction, the same instance-wise distance transform, the same letterbox rescaling (512×512 with padding), and the same histogram postprocessing implemented in `grape_pipeline_postprocess.cpp`. The Python scripts perform no image processing, no tensor assembly, and no ONNX inference; they exclusively orchestrate batch execution on the Android device via ADB, collect the resulting JSON files, match them against ground-truth annotations, and compute aggregate metrics (MAE, RMSE, W1, fidelidad) and fused A/B statistics. Consequently, the reported performance metrics faithfully reflect the runtime characteristics of the Android-native C++ pipeline, and no alternative inference code path was used in the evaluation.
