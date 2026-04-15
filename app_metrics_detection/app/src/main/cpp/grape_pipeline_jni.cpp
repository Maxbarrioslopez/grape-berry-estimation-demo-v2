/**
 * grape_pipeline_jni.cpp - v6.0 DEFINITIVA
 * Motor de inferencia con Caché de Modelos y Exportación Forense Sincronizada.
 * Sincronizado con BatchProcessor.kt para guardado de evidencia técnica.
 * Maneja valores NaN/Inf para evitar errores de parseo en Android.
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
        if (s == nullptr) return std::string();
        const char* c = env->GetStringUTFChars(s, nullptr);
        std::string out = (c == nullptr) ? std::string() : std::string(c);
        if (c != nullptr) env->ReleaseStringUTFChars(s, c);
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

    std::string build_jni_path(const std::string& image_path, const std::string& suffix, const std::string& ext) {
        namespace fs = std::filesystem;
        try {
            fs::path p(image_path);
            return (p.parent_path() / (p.stem().string() + "_jni_" + suffix + "." + ext)).string();
        } catch (...) { return ""; }
    }

    void save_jni_evidences(const PipelineResult& result, const std::string& pro_p, const std::string& seg_p, const std::string& raw_p) {
        const auto& seg_out = result.pipe.seg_out;
        if (seg_out.orig_rgb.empty()) return;

        // 1. PRO OVERLAY (Detecciones con bordes)
        cv::Mat pro_img;
        cv::cvtColor(seg_out.orig_rgb, pro_img, cv::COLOR_RGB2BGR);
        for (size_t i = 0; i < seg_out.masks.size(); ++i) {
            int cid = seg_out.cls_ids[i];
            cv::Scalar color = (cid >= 3 && cid <= 5) ? cv::Scalar(0, 255, 0) : cv::Scalar(0, 0, 255);
            std::vector<std::vector<cv::Point>> contours;
            cv::findContours(seg_out.masks[i], contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
            cv::drawContours(pro_img, contours, -1, color, 2);
        }
        if (!pro_p.empty()) cv::imwrite(pro_p, pro_img);

        // 2. SEG VISUAL (Máscara coloreada verde)
        if (!seg_p.empty()) {
            cv::Mat seg_vis;
            cv::cvtColor(result.pipe.global_masks.grapes, seg_vis, cv::COLOR_GRAY2BGR);
            for(int r=0; r<seg_vis.rows; ++r) {
                for(int c=0; c<seg_vis.cols; ++c) {
                    if(seg_vis.at<cv::Vec3b>(r,c)[0] > 0) seg_vis.at<cv::Vec3b>(r,c) = cv::Vec3b(40, 200, 40);
                }
            }
            cv::imwrite(seg_p, seg_vis);
        }

        // 3. RAW MASK (Binaria pura para validación técnica)
        if (!raw_p.empty()) cv::imwrite(raw_p, result.pipe.global_masks.grapes);
    }

    std::string build_success_json(const PipelineResult& result, const std::string& pro_p, const std::string& seg_p, const std::string& raw_p, long long inf, long long post) {
        std::ostringstream oss;
        auto safe_f = [](float f) { return std::isfinite(f) ? f : 0.0f; };

        oss << std::setprecision(6) << std::fixed << "{";
        oss << "\"status\":true,\"variety\":\"" << json_escape(result.pipe.variety) << "\",";
        oss << "\"count_total\":" << safe_f(result.reg_out.count_total) << ",";
        oss << "\"mean\":" << safe_f(result.reg_out.mean) << ",";
        oss << "\"mode\":" << safe_f(result.reg_out.mode) << ",";
        oss << "\"std\":" << safe_f(result.reg_out.std) << ",";
        oss << "\"seg_count_base\":" << result.pipe.seg_count_base << ",";

        oss << "\"jni_paths\":{";
        oss << "\"pro\":\"" << json_escape(pro_p) << "\",";
        oss << "\"seg\":\"" << json_escape(seg_p) << "\",";
        oss << "\"raw_mask\":\"" << json_escape(raw_p) << "\"";
        oss << "},";

        oss << "\"detections\":[";
        const auto& seg_out = result.pipe.seg_out;
        for (size_t i = 0; i < seg_out.boxes.size(); ++i) {
            if (i > 0) oss << ",";
            oss << "{\"cls\":" << seg_out.cls_ids[i] << ",\"score\":" << safe_f(seg_out.scores[i]);
            oss << ",\"box\":[" << seg_out.boxes[i].x << "," << seg_out.boxes[i].y << "," << seg_out.boxes[i].width << "," << seg_out.boxes[i].height << "]}";
        }
        oss << "],";

        oss << "\"inf_ms\":" << inf << ",\"post_ms\":" << post << ",";

        auto append_arr = [&](const std::string& name, const auto& vec) {
            oss << "\"" << name << "\":[";
            for (size_t i = 0; i < vec.size(); ++i) oss << (i > 0 ? "," : "") << vec[i];
            oss << "]";
        };
        append_arr("pred", result.hist_counts_int); oss << ",";
        append_arr("bins", BINS);
        oss << "}";
        return oss.str();
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gaiaspa_metrics_1detection_ml_CppPipelineBridge_nativeRunPipeline(
        JNIEnv* env, jobject, jstring imagePath, jstring segModelPath, jstring regModelPath, jint varietyId, jstring providerPreference, jboolean, jboolean) {
    auto& ctx = get_ctx();
    std::lock_guard<std::mutex> lock(ctx.mtx);
    try {
        const std::string img_p = jstring_to_std(env, imagePath);
        const std::string seg_p = jstring_to_std(env, segModelPath);
        const std::string reg_p = jstring_to_std(env, regModelPath);
        ProviderPreference pref = parse_provider_preference(jstring_to_std(env, providerPreference));

        if (!ctx.seg_sess || ctx.last_seg_path != seg_p) {
            std::string d; ctx.seg_sess = std::make_unique<Ort::Session>(create_session(ctx.env, seg_p, pref, d));
            ctx.last_seg_path = seg_p;
        }
        if (!ctx.reg_sess || ctx.last_reg_path != reg_p) {
            std::string d; ctx.reg_sess = std::make_unique<Ort::Session>(create_session(ctx.env, reg_p, pref, d));
            ctx.last_reg_path = reg_p;
        }

        std::string variety = (varietyId >= 0) ? VARIETY_CLASSES[varietyId] : infer_variety_from_filename(img_p);
        auto t1 = Clock::now();
        PipelineResult res = run_pipeline(*ctx.seg_sess, *ctx.reg_sess, img_p, variety);
        auto t2 = Clock::now();

        std::string pro_p = build_jni_path(img_p, "pro", "jpg");
        std::string seg_p_img = build_jni_path(img_p, "seg", "jpg");
        std::string raw_p = build_jni_path(img_p, "raw", "png");
        save_jni_evidences(res, pro_p, seg_p_img, raw_p);
        auto t3 = Clock::now();

        return env->NewStringUTF(build_success_json(res, pro_p, seg_p_img, raw_p,
            std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count(),
            std::chrono::duration_cast<std::chrono::milliseconds>(t3 - t2).count()).c_str());
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
