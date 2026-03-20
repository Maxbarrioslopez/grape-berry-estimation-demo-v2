#include <jni.h>

#include <algorithm>
#include <cmath>
#include <filesystem>
#include <iomanip>
#include <numeric>
#include <sstream>
#include <string>

#define GRAPE_PIPELINE_AS_LIBRARY
#include "c_code/main.cpp"

namespace {

std::string jstring_to_std(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = (c == nullptr) ? std::string() : std::string(c);
    if (c != nullptr) {
        env->ReleaseStringUTFChars(s, c);
    }
    return out;
}

std::string json_escape(const std::string& in) {
    std::string out;
    out.reserve(in.size() + 8);
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

std::string build_overlay_path(const std::string& image_path) {
    namespace fs = std::filesystem;
    fs::path p(image_path);
    fs::path out = p.parent_path() / "cpp_seg_overlay_last.png";
    return out.string();
}

std::string class_name_lower(int cls_id) {
    auto it = CLASS_NAMES.find(cls_id);
    std::string name = (it != CLASS_NAMES.end()) ? it->second : std::to_string(cls_id);
    std::transform(name.begin(), name.end(), name.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return name;
}

void append_int_array_json(std::ostringstream& oss, const std::vector<int>& values) {
    oss << "[";
    for (size_t i = 0; i < values.size(); ++i) {
        if (i > 0) oss << ",";
        oss << values[i];
    }
    oss << "]";
}

void append_float_array_json(std::ostringstream& oss, const std::vector<float>& values) {
    oss << "[";
    for (size_t i = 0; i < values.size(); ++i) {
        if (i > 0) oss << ",";
        oss << values[i];
    }
    oss << "]";
}

// Creates a transparent PNG overlay that highlights grapes and pingpong detections.
void write_segmentation_overlay_png(const PipelineResult& result, const std::string& overlay_path) {
    const auto& seg_out = result.pipe.seg_out;
    if (seg_out.orig_rgb.empty()) return;

    cv::Mat overlay(seg_out.orig_rgb.rows, seg_out.orig_rgb.cols, CV_8UC4, cv::Scalar(0, 0, 0, 0));

    for (size_t i = 0; i < seg_out.masks.size() && i < seg_out.cls_ids.size(); ++i) {
        const cv::Mat& mask = seg_out.masks[i];
        if (mask.empty()) continue;

        const std::string cname = class_name_lower(seg_out.cls_ids[i]);

        cv::Vec4b fill_color(0, 0, 0, 0);
        cv::Vec4b edge_color(0, 0, 0, 0);

        if (cname.find("grape") != std::string::npos) {
            fill_color = cv::Vec4b(40, 220, 60, 95);
            edge_color = cv::Vec4b(30, 255, 30, 255);
        } else if (cname.find("ping") != std::string::npos) {
            fill_color = cv::Vec4b(10, 120, 255, 120);
            edge_color = cv::Vec4b(255, 50, 20, 255);
        } else {
            continue;
        }

        for (int y = 0; y < mask.rows; ++y) {
            const uint8_t* mp = mask.ptr<uint8_t>(y);
            cv::Vec4b* op = overlay.ptr<cv::Vec4b>(y);
            for (int x = 0; x < mask.cols; ++x) {
                if (mp[x]) {
                    op[x] = fill_color;
                }
            }
        }

        std::vector<std::vector<cv::Point>> contours;
        cv::Mat mask_u8;
        if (mask.type() != CV_8U) {
            mask.convertTo(mask_u8, CV_8U, 255.0);
        } else {
            mask_u8 = mask;
        }
        cv::findContours(mask_u8, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
        if (!contours.empty()) {
            cv::drawContours(
                overlay,
                contours,
                -1,
                cv::Scalar(edge_color[0], edge_color[1], edge_color[2], edge_color[3]),
                2,
                cv::LINE_AA
            );
        }
    }

    cv::imwrite(overlay_path, overlay);
}

std::string build_success_json(
    const PipelineResult& result,
    const std::string& overlay_path,
    const std::string& provider_requested,
    const std::string& seg_provider_used,
    const std::string& reg_provider_used
) {
    const int count_total_final = std::accumulate(
        result.hist_counts_int.begin(),
        result.hist_counts_int.end(),
        0
    );

    float mode_val = 0.0f;
    float std_val = 0.0f;

    if (!result.hist_counts_int.empty()) {
        auto max_it = std::max_element(result.hist_counts_int.begin(), result.hist_counts_int.end());
        size_t max_idx = std::distance(result.hist_counts_int.begin(), max_it);
        if (max_idx < BINS.size()) {
            mode_val = static_cast<float>(BINS[max_idx]);
        }

        const float total = static_cast<float>(count_total_final);
        if (total > 0.0f) {
            float weighted_mean = 0.0f;
            for (size_t i = 0; i < result.hist_counts_int.size() && i < BINS.size(); ++i) {
                weighted_mean += BINS[i] * result.hist_counts_int[i];
            }
            weighted_mean /= total;

            float variance = 0.0f;
            for (size_t i = 0; i < result.hist_counts_int.size() && i < BINS.size(); ++i) {
                float delta = BINS[i] - weighted_mean;
                variance += delta * delta * result.hist_counts_int[i];
            }
            variance /= total;
            std_val = std::sqrt(variance);
        }
    }

    const bool raw_outputs_present =
        !result.reg_out.hist_logits.empty() &&
        !result.reg_out.hist_prob.empty() &&
        !result.reg_out.hist_counts.empty();

    const std::string provider_summary =
        (seg_provider_used == reg_provider_used)
            ? seg_provider_used
            : ("seg=" + seg_provider_used + ", reg=" + reg_provider_used);

    std::ostringstream oss;
    oss << std::setprecision(8);
    oss << "{";
    oss << "\"status\":true,";
    oss << "\"error\":\"\",";
    oss << "\"variety\":\"" << json_escape(result.pipe.variety) << "\",";
    oss << "\"variety_idx\":" << result.pipe.variety_idx[0] << ",";
    oss << "\"count_total_raw\":" << result.reg_out.count_total << ",";
    oss << "\"count_total\":" << count_total_final << ",";
    oss << "\"mean\":" << result.reg_out.mean << ",";
    oss << "\"mode\":" << mode_val << ",";
    oss << "\"std\":" << std_val << ",";
    oss << "\"seg_overlay_path\":\"" << json_escape(overlay_path) << "\",";
    oss << "\"num_grape_det\":" << result.pipe.global_masks.num_grape_det << ",";
    oss << "\"num_pingpong_det\":" << result.pipe.global_masks.num_pingpong_det << ",";
    oss << "\"provider_requested\":\"" << json_escape(provider_requested) << "\",";
    oss << "\"provider\":\"" << json_escape(provider_summary) << "\",";
    oss << "\"provider_seg\":\"" << json_escape(seg_provider_used) << "\",";
    oss << "\"provider_reg\":\"" << json_escape(reg_provider_used) << "\",";
    oss << "\"input_tensor_shape\":[1,5," << IMGSZ << "," << IMGSZ << "],";
    oss << "\"raw_outputs_present\":" << (raw_outputs_present ? "true" : "false") << ",";
    oss << "\"mm_per_px\":null,";
    oss << "\"pre_ms\":null,";
    oss << "\"infer_ms\":null,";
    oss << "\"post_ms\":null,";

    oss << "\"hist_counts_float\":";
    append_float_array_json(oss, result.reg_out.hist_counts);
    oss << ",";

    oss << "\"pred\":";
    append_int_array_json(oss, result.hist_counts_int);
    oss << ",";

    oss << "\"bins\":[";
    for (size_t i = 0; i < BINS.size(); ++i) {
        if (i > 0) oss << ",";
        oss << BINS[i];
    }
    oss << "]";

    oss << "}";
    return oss.str();
}

std::string build_error_json(const std::string& err) {
    std::ostringstream oss;
    oss << "{";
    oss << "\"status\":false,";
    oss << "\"error\":\"" << json_escape(err) << "\"";
    oss << "}";
    return oss.str();
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_gaiaspa_metrics_1detection_ml_CppPipelineBridge_nativeRunPipeline(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring imagePath,
    jstring segModelPath,
    jstring regModelPath,
    jint varietyId,
    jstring providerPreference,
    jboolean /*smoothEdges*/,
    jboolean /*useDepth*/
) {
    try {
        const std::string image_path = jstring_to_std(env, imagePath);
        const std::string seg_path = jstring_to_std(env, segModelPath);
        const std::string reg_path = jstring_to_std(env, regModelPath);
        std::string provider_raw = jstring_to_std(env, providerPreference);

        if (image_path.empty()) {
            const std::string out = build_error_json("imagePath vacio");
            return env->NewStringUTF(out.c_str());
        }

        if (provider_raw.empty()) {
            provider_raw = "auto";
        }
        const ProviderPreference provider_preference = parse_provider_preference(provider_raw);

        std::string variety;
        if (varietyId >= 0 && static_cast<size_t>(varietyId) < VARIETY_CLASSES.size()) {
            variety = VARIETY_CLASSES[static_cast<size_t>(varietyId)];
        } else {
            variety = infer_variety_from_filename(image_path);
        }

        Ort::Env ort_env(ORT_LOGGING_LEVEL_WARNING, "grape_pipeline_android");
        std::string seg_provider_used;
        std::string reg_provider_used;

        Ort::Session seg_session = create_session(
            ort_env,
            seg_path,
            provider_preference,
            seg_provider_used
        );

        Ort::Session reg_session = create_session(
            ort_env,
            reg_path,
            provider_preference,
            reg_provider_used
        );

        PipelineResult result = run_pipeline(seg_session, reg_session, image_path, variety);
        const std::string overlay_path = build_overlay_path(image_path);
        write_segmentation_overlay_png(result, overlay_path);
        const std::string out = build_success_json(
            result,
            overlay_path,
            provider_raw,
            seg_provider_used,
            reg_provider_used
        );
        return env->NewStringUTF(out.c_str());
    } catch (const std::exception& e) {
        const std::string out = build_error_json(e.what());
        return env->NewStringUTF(out.c_str());
    } catch (...) {
        const std::string out = build_error_json("Error nativo desconocido");
        return env->NewStringUTF(out.c_str());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_gaiaspa_metrics_1detection_ml_CppPipelineBridge_nativeRelease(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
}
