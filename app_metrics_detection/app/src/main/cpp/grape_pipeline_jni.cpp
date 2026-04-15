#include <jni.h>
#include <algorithm>
#include <cmath>
#include <filesystem>
#include <iomanip>
#include <numeric>
#include <sstream>
#include <string>
#include <chrono>

#define GRAPE_PIPELINE_AS_LIBRARY
#include "c_code/main.cpp"

namespace {
    using Clock = std::chrono::high_resolution_clock;

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
                case '\r': out += "\\r"; break;
                case '\t': out += "\\t"; break;
                default: out += ch; break;
            }
        }
        return out;
    }

    std::string build_jni_output_path(const std::string& image_path, const std::string& suffix, const std::string& ext) {
        namespace fs = std::filesystem;
        fs::path p(image_path);
        std::string stem = p.stem().string();
        return (p.parent_path() / (stem + "_jni_" + suffix + "." + ext)).string();
    }

    void save_technical_evidences(const PipelineResult& result, const std::string& pro_p, const std::string& seg_p, const std::string& raw_p) {
        const auto& seg_out = result.pipe.seg_out;
        if (seg_out.orig_rgb.empty()) return;

        // 1. PRO OVERLAY
        cv::Mat pro_img;
        cv::cvtColor(seg_out.orig_rgb, pro_img, cv::COLOR_RGB2BGR);
        for (size_t i = 0; i < seg_out.masks.size(); ++i) {
            int cid = seg_out.cls_ids[i];
            cv::Scalar color = (cid >= 3 && cid <= 5) ? cv::Scalar(0, 255, 0) : cv::Scalar(0, 0, 255);
            std::vector<std::vector<cv::Point>> contours;
            cv::findContours(seg_out.masks[i], contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
            cv::drawContours(pro_img, contours, -1, color, 2);
        }
        cv::imwrite(pro_p, pro_img);

        // 2. SEG VISUAL
        cv::Mat seg_vis;
        cv::cvtColor(result.pipe.global_masks.grapes, seg_vis, cv::COLOR_GRAY2BGR);
        for(int r=0; r<seg_vis.rows; ++r) {
            for(int c=0; c<seg_vis.cols; ++c) {
                if(seg_vis.at<cv::Vec3b>(r,c)[0] > 0) seg_vis.at<cv::Vec3b>(r,c) = cv::Vec3b(40, 200, 40);
            }
        }
        cv::imwrite(seg_p, seg_vis);

        // 3. RAW MASK
        cv::imwrite(raw_p, result.pipe.global_masks.grapes);
    }

    std::string build_success_json(const PipelineResult& result,
                                 const std::string& pro_p, const std::string& seg_p, const std::string& raw_p,
                                 const std::string& s_prov, const std::string& r_prov,
                                 long long pre_ms, long long infer_ms, long long post_ms) {
        std::ostringstream oss;
        oss << std::setprecision(8) << "{";
        oss << "\"status\":true,\"variety\":\"" << json_escape(result.pipe.variety) << "\",";
        oss << "\"count_total\":" << result.reg_out.count_total << ",";
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
            oss << "{\"cls\":" << seg_out.cls_ids[i] << ",\"score\":" << seg_out.scores[i];
            oss << ",\"box\":[" << seg_out.boxes[i].x << "," << seg_out.boxes[i].y << "," << seg_out.boxes[i].width << "," << seg_out.boxes[i].height << "]}";
        }
        oss << "],";

        oss << "\"num_grape_det\":" << result.pipe.global_masks.num_grape_det << ",";
        oss << "\"num_pingpong_det\":" << result.pipe.global_masks.num_pingpong_det << ",";
        oss << "\"inf_ms\":" << infer_ms << ",\"post_ms\":" << post_ms << ",";

        auto append_array = [&](const std::string& name, const auto& vec) {
            oss << "\"" << name << "\":[";
            for (size_t i = 0; i < vec.size(); ++i) oss << (i > 0 ? "," : "") << vec[i];
            oss << "]";
        };
        append_array("hist_prob", result.reg_out.hist_prob);
        oss << "}";
        return oss.str();
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gaiaspa_metrics_1detection_ml_CppPipelineBridge_nativeRunPipeline(
        JNIEnv* env, jobject, jstring imagePath, jstring segModelPath, jstring regModelPath, jint varietyId, jstring providerPreference, jboolean, jboolean) {
    try {
        auto t0 = Clock::now();
        const std::string img_p = jstring_to_std(env, imagePath);
        ProviderPreference pref = parse_provider_preference(jstring_to_std(env, providerPreference));

        std::string variety = (varietyId >= 0) ? VARIETY_CLASSES[varietyId] : infer_variety_from_filename(img_p);

        Ort::Env ort_env(ORT_LOGGING_LEVEL_WARNING, "grape_pipeline");
        std::string sp, rp;
        Ort::Session seg_s = create_session(ort_env, jstring_to_std(env, segModelPath), pref, sp);
        Ort::Session reg_s = create_session(ort_env, jstring_to_std(env, regModelPath), pref, rp);

        auto t1 = Clock::now();
        PipelineResult res = run_pipeline(seg_s, reg_s, img_p, variety);
        auto t2 = Clock::now();

        std::string pro_p = build_jni_output_path(img_p, "pro", "jpg");
        std::string seg_p = build_jni_output_path(img_p, "seg", "jpg");
        std::string raw_p = build_jni_output_path(img_p, "raw", "png");
        save_technical_evidences(res, pro_p, seg_p, raw_p);
        auto t3 = Clock::now();

        return env->NewStringUTF(build_success_json(res, pro_p, seg_p, raw_p, sp, rp, 0,
            std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count(),
            std::chrono::duration_cast<std::chrono::milliseconds>(t3 - t2).count()).c_str());
    } catch (const std::exception& e) {
        return env->NewStringUTF((std::string("{\"status\":false,\"error\":\"") + json_escape(e.what()) + "\"}").c_str());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_gaiaspa_metrics_1detection_ml_CppPipelineBridge_nativeRelease(JNIEnv*, jobject) {}
