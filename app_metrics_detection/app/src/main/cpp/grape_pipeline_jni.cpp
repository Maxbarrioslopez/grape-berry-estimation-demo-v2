/**
 * grape_pipeline_jni.cpp - v10.0 NATIVE OVERLAY MIGRATION
 * Optimized engine to reuse the upload asset as visual base.
 */
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <filesystem>
#include <iomanip>
#include <numeric>
#include <sstream>
#include <string>
#include <chrono>
#include <memory>
#include <mutex>

#define GRAPE_PIPELINE_AS_LIBRARY
#include "c_code/main.cpp"
#include "include/grape_pipeline_postprocess.hpp"

namespace {
    using Clock = std::chrono::high_resolution_clock;

    struct NativeContext {
        Ort::Env env;
        std::unique_ptr<Ort::Session> seg_sess;
        std::unique_ptr<Ort::Session> reg_sess;
        std::string last_seg_path;
        std::string last_reg_path;
        std::mutex mtx;
        NativeContext() : env(ORT_LOGGING_LEVEL_WARNING, "grape_pipeline_android") {}
    };

    static NativeContext& get_ctx() {
        static NativeContext ctx;
        return ctx;
    }

    std::string jstring_to_std(JNIEnv* env, jstring s) {
        if (s == nullptr) return "";
        const char* c = env->GetStringUTFChars(s, nullptr);
        std::string out(c);
        env->ReleaseStringUTFChars(s, c);
        return out;
    }

    std::string json_escape(const std::string& in) {
        std::string out; out.reserve(in.size() + 8);
        for (char ch : in) {
            switch (ch) {
                case '\\': out += "\\\\"; break;
                case '"': out += "\\\""; break;
                case '\n': out += "\\n"; break;
                default: out += ch; break;
            }
        }
        return out;
    }

    std::string build_success_json(const PipelineResult& result, const std::string& visual_p, long long inf) {
        std::ostringstream oss;
        auto safe_f = [](float f) { return std::isfinite(f) ? f : 0.0f; };

        oss << std::setprecision(6) << std::fixed << "{";
        oss << "\"status\":true,\"variety\":\"" << json_escape(result.variety) << "\",";
        oss << "\"count_total\":" << safe_f(result.count_total) << ",";
        oss << "\"mean\":" << safe_f(result.mean) << ",";
        oss << "\"mode\":" << safe_f(result.mode) << ",";
        oss << "\"std\":" << safe_f(result.std) << ",";
        oss << "\"seg_count_base\":" << safe_f(result.seg_count_base) << ",";

        // ✅ NEW STRUCTURE: pro now points to the final visual result generated in C++
        oss << "\"jni_paths\":{";
        oss << "\"pro\":\"" << json_escape(visual_p) << "\",";
        oss << "\"debug_overlay\":\"" << json_escape(result.debug.overlay_path) << "\"";
        oss << "},";

        oss << "\"inf_ms\":" << inf << ",";

        // Detections for fallback in Kotlin if needed
        oss << "\"detections\":[";
        for (size_t i = 0; i < result.detections.size(); ++i) {
            if (i > 0) oss << ",";
            const auto& det = result.detections[i];
            oss << "{\"class_name\":\"" << json_escape(det.class_name) << "\",";
            oss << "\"score\":" << safe_f(det.score) << ",";
            oss << "\"box\":[" << det.x << "," << det.y << "," << det.w << "," << det.h << "]}";
        }
        oss << "],";

        auto append_arr = [&](const std::string& name, const auto& vec) {
            oss << "\"" << name << "\":[";
            for (size_t i = 0; i < vec.size(); ++i) oss << (i > 0 ? "," : "") << vec[i];
            oss << "]";
        };
        append_arr("pred", result.count_pred_by_bin_int); oss << ",";
        append_arr("bins", result.bins);
        oss << "}";
        return oss.str();
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gaiaspa_metrics_1detection_ml_CppPipelineBridge_nativeRunPipeline(
        JNIEnv* env, jobject,
        jstring imagePath,
        jstring segModelPath,
        jstring regModelPath,
        jint varietyId,
        jstring providerPreference,
        jboolean saveDebug,
        jboolean allowSynthetic,
        jstring visualOverlayPath) { // ✅ NEW PARAMETER

    auto& ctx = get_ctx();
    std::lock_guard<std::mutex> lock(ctx.mtx);
    try {
        const std::string img_p = jstring_to_std(env, imagePath);
        const std::string seg_p = jstring_to_std(env, segModelPath);
        const std::string reg_p = jstring_to_std(env, regModelPath);
        const std::string vis_p = jstring_to_std(env, visualOverlayPath);

        // 1. Run Inference (Core unchanged)
        auto t1 = Clock::now();
        PipelineResult res = run_pipeline(*ctx.seg_sess, *ctx.reg_sess, img_p, (varietyId >= 0 ? VARIETY_CLASSES[varietyId] : ""));
        auto t2 = Clock::now();

        // 2. Generate Debug Artifacts (Technical BBoxes)
        if (saveDebug) {
            grape::SaveDebugArtifacts(BuildPipelineInputs(img_p, res.variety, res.variety_idx, {}, ctx.seg_sess.get(), allowSynthetic), res);
        }

        // 3. ✅ GENERATE FINAL VISUAL OVERLAY (Ovals over the upload copy)
        if (!vis_p.empty()) {
            // We need the inputs for scaling (orig_bgr)
            PipelineInputs inputs = BuildPipelineInputs(img_p, res.variety, res.variety_idx, {}, ctx.seg_sess.get(), allowSynthetic);
            grape::SaveVisualOverlay(vis_p, inputs, res);
        }

        return env->NewStringUTF(build_success_json(res, vis_p,
            std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count()).c_str());

    } catch (const std::exception& e) {
        return env->NewStringUTF((std::string("{\"status\":false,\"error\":\"") + json_escape(e.what()) + "\"}").c_str());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_gaiaspa_metrics_1detection_ml_CppPipelineBridge_nativeRelease(JNIEnv*, jobject) {
    auto& ctx = get_ctx();
    std::lock_guard<std::mutex> lock(ctx.mtx);
    ctx.seg_sess.reset(); ctx.reg_sess.reset();
}
