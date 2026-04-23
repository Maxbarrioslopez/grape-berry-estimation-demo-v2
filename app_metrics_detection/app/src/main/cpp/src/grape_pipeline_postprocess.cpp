#include "grape_pipeline_postprocess.hpp"

#include "grape_pipeline_config.hpp"
#include <android/log.h>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

#include <algorithm>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <numeric>
#include <sstream>

namespace grape {
namespace {

namespace fs = std::filesystem;

#define GP_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "NATIVE_OVERLAY", __VA_ARGS__)
#define GP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "NATIVE_OVERLAY", __VA_ARGS__)

double SafeNumber(double value) {
    return std::isfinite(value) ? value : 0.0;
}

float SafePositiveFloat(float value) {
    return (std::isfinite(value) && value > 0.0f) ? value : 0.0f;
}

double DerivedInfMs(const PipelineResult& result) {
    return SafeNumber(result.timing.preprocess_ms)
        + SafeNumber(result.timing.qty_ms)
        + SafeNumber(result.timing.hist_ms);
}

std::string JsonEscape(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 16);
    for (char ch : input) {
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

cv::Mat ToU8Image(const cv::Mat& input) {
    if (input.empty()) {
        return input;
    }
    if (input.type() == CV_8U) {
        return input.clone();
    }
    cv::Mat output;
    input.convertTo(output, CV_8U, 255.0);
    return output;
}

cv::Mat ColorizeMask(const cv::Mat& input, int colormap) {
    cv::Mat u8 = ToU8Image(input);
    if (u8.empty()) {
        return u8;
    }
    cv::Mat color;
    cv::applyColorMap(u8, color, colormap);
    return color;
}

cv::Mat DrawHistogramDebug(const std::vector<int>& bins, const std::vector<int>& values) {
    constexpr int kWidth = 1000;
    constexpr int kHeight = 420;
    constexpr int kMargin = 50;

    cv::Mat canvas(kHeight, kWidth, CV_8UC3, cv::Scalar(255, 255, 255));
    cv::line(
        canvas,
        cv::Point(kMargin, kHeight - kMargin),
        cv::Point(kWidth - kMargin, kHeight - kMargin),
        cv::Scalar(0, 0, 0),
        2);
    cv::line(
        canvas,
        cv::Point(kMargin, kHeight - kMargin),
        cv::Point(kMargin, kMargin),
        cv::Scalar(0, 0, 0),
        2);

    const int max_value = values.empty() ? 1 : std::max(1, *std::max_element(values.begin(), values.end()));
    const int usable_width = kWidth - (2 * kMargin);
    const int usable_height = kHeight - (2 * kMargin);
    const int bar_width = std::max(8, usable_width / static_cast<int>(std::max<size_t>(bins.size(), 1)));

    for (size_t i = 0; i < bins.size() && i < values.size(); ++i) {
        const int x = kMargin + static_cast<int>(i * bar_width);
        const int h = static_cast<int>(
            std::round((static_cast<double>(values[i]) / max_value) * usable_height));
        cv::rectangle(
            canvas,
            cv::Point(x, kHeight - kMargin - h),
            cv::Point(x + bar_width - 4, kHeight - kMargin),
            cv::Scalar(52, 152, 219),
            cv::FILLED);
        cv::putText(
            canvas,
            std::to_string(bins[i]),
            cv::Point(x, kHeight - 20),
            cv::FONT_HERSHEY_SIMPLEX,
            0.35,
            cv::Scalar(0, 0, 0),
            1,
            cv::LINE_AA);
    }

    return canvas;
}

void TryWriteImage(const std::string& path, const cv::Mat& image) {
    if (!path.empty() && !image.empty()) {
        cv::imwrite(path, image);
    }
}

void AppendFloatArray(std::ostringstream& oss, const char* name, const std::vector<float>& values) {
    oss << "\"" << name << "\":[";
    for (size_t i = 0; i < values.size(); ++i) {
        if (i > 0) {
            oss << ",";
        }
        oss << std::fixed << std::setprecision(6) << SafeNumber(values[i]);
    }
    oss << "]";
}

void AppendIntArray(std::ostringstream& oss, const char* name, const std::vector<int>& values) {
    oss << "\"" << name << "\":[";
    for (size_t i = 0; i < values.size(); ++i) {
        if (i > 0) {
            oss << ",";
        }
        oss << values[i];
    }
    oss << "]";
}

}  // namespace

float ComputeHistogramMean(const std::vector<float>& hist_prob, const std::vector<int>& bins) {
    if (hist_prob.empty() || bins.empty()) {
        return 0.0f;
    }
    const size_t size = std::min(hist_prob.size(), bins.size());
    double mean = 0.0;
    for (size_t i = 0; i < size; ++i) {
        mean += static_cast<double>(SafePositiveFloat(hist_prob[i])) * static_cast<double>(bins[i]);
    }
    return static_cast<float>(mean);
}

float ComputeHistogramMode(const std::vector<float>& count_pred_by_bin, const std::vector<int>& bins) {
    if (count_pred_by_bin.empty() || bins.empty()) {
        return 0.0f;
    }
    const auto it = std::max_element(count_pred_by_bin.begin(), count_pred_by_bin.end());
    const size_t index = static_cast<size_t>(std::distance(count_pred_by_bin.begin(), it));
    return index < bins.size() ? static_cast<float>(bins[index]) : 0.0f;
}

float ComputeHistogramStd(const std::vector<float>& hist_prob, float mean, const std::vector<int>& bins) {
    if (hist_prob.empty() || bins.empty()) {
        return 0.0f;
    }
    const size_t size = std::min(hist_prob.size(), bins.size());
    double variance = 0.0;
    double total = 0.0;
    for (size_t i = 0; i < size; ++i) {
        const double weight = SafePositiveFloat(hist_prob[i]);
        const double delta = static_cast<double>(bins[i]) - static_cast<double>(mean);
        variance += weight * delta * delta;
        total += weight;
    }
    if (total <= 0.0) {
        return 0.0f;
    }
    return static_cast<float>(std::sqrt(variance / total));
}

std::vector<float> NormalizeHistogram(const std::vector<float>& hist_prob) {
    std::vector<float> normalized = hist_prob;
    double sum = 0.0;
    for (float value : normalized) {
        sum += SafePositiveFloat(value);
    }
    if (sum <= 0.0) {
        return std::vector<float>(
            hist_prob.size(),
            hist_prob.empty() ? 0.0f : 1.0f / static_cast<float>(hist_prob.size()));
    }
    for (float& value : normalized) {
        value = static_cast<float>(SafePositiveFloat(value) / sum);
    }
    return normalized;
}

std::vector<int> HistogramToIntegers(const std::vector<float>& count_pred_by_bin, float total_count) {
    const int total = std::max(0, static_cast<int>(std::round(total_count)));
    if (count_pred_by_bin.empty() || total <= 0) {
        return std::vector<int>(count_pred_by_bin.size(), 0);
    }

    double sum = 0.0;
    for (float value : count_pred_by_bin) {
        sum += SafePositiveFloat(value);
    }
    if (sum <= 0.0) {
        return std::vector<int>(count_pred_by_bin.size(), 0);
    }

    std::vector<int> base(count_pred_by_bin.size(), 0);
    std::vector<double> fraction(count_pred_by_bin.size(), 0.0);
    int assigned = 0;
    for (size_t i = 0; i < count_pred_by_bin.size(); ++i) {
        const double scaled = static_cast<double>(count_pred_by_bin[i]) * static_cast<double>(total) / sum;
        base[i] = static_cast<int>(std::floor(scaled));
        fraction[i] = scaled - base[i];
        assigned += base[i];
    }

    std::vector<size_t> indices(count_pred_by_bin.size());
    std::iota(indices.begin(), indices.end(), 0U);
    std::sort(indices.begin(), indices.end(), [&](size_t lhs, size_t rhs) {
        return fraction[lhs] > fraction[rhs];
    });

    for (int i = 0; i < total - assigned && i < static_cast<int>(indices.size()); ++i) {
        ++base[indices[static_cast<size_t>(i)]];
    }

    return base;
}

DebugArtifacts BuildDefaultDebugArtifacts(const std::string& image_path, bool enabled) {
    DebugArtifacts debug;
    debug.enabled = enabled;
    if (!enabled) {
        return debug;
    }

    const fs::path image = fs::path(image_path);
    const fs::path output_dir = image.parent_path() / (image.stem().string() + kDebugDirSuffix);
    std::error_code ec;
    fs::create_directories(output_dir, ec);

    debug.output_dir = output_dir.string();
    debug.overlay_path = (output_dir / "overlay.png").string();
    debug.grapes_mask_path = (output_dir / "grapes_mask.png").string();
    debug.pingpong_mask_path = (output_dir / "pingpong_mask.png").string();
    debug.dt_grapes_path = (output_dir / "dt_grapes.png").string();
    debug.dt_pingpong_path = (output_dir / "dt_pingpong.png").string();
    debug.histogram_csv_path = (output_dir / "histogram.csv").string();
    debug.histogram_png_path = (output_dir / "histogram.png").string();
    debug.runtime_json_path = (output_dir / "runtime_result.json").string();
    return debug;
}

void SaveDebugArtifacts(const PipelineInputs& inputs, PipelineResult& result) {
    if (!result.debug.enabled || result.debug.output_dir.empty()) {
        return;
    }

    std::error_code ec;
    fs::create_directories(result.debug.output_dir, ec);

    if (!inputs.seg.orig_bgr.empty()) {
        cv::Mat overlay = inputs.seg.orig_bgr.clone();
        for (const DetectionSummary& detection : result.detections) {
            const bool is_grape = detection.class_name.find("grape") != std::string::npos;
            const cv::Scalar color = is_grape ? cv::Scalar(0, 200, 0) : cv::Scalar(0, 0, 255);
            cv::rectangle(
                overlay,
                cv::Rect(
                    static_cast<int>(std::round(detection.x)),
                    static_cast<int>(std::round(detection.y)),
                    static_cast<int>(std::round(detection.w)),
                    static_cast<int>(std::round(detection.h))),
                color,
                2);
        }
        TryWriteImage(result.debug.overlay_path, overlay);
    }

    TryWriteImage(result.debug.grapes_mask_path, inputs.seg.grapes_global_lb);
    TryWriteImage(result.debug.pingpong_mask_path, inputs.seg.pingpong_global_lb);
    TryWriteImage(result.debug.dt_grapes_path, ColorizeMask(inputs.grapes_dt_instance_lb, cv::COLORMAP_VIRIDIS));
    TryWriteImage(result.debug.dt_pingpong_path, ColorizeMask(inputs.pingpong_dt_instance_lb, cv::COLORMAP_MAGMA));
    TryWriteImage(result.debug.histogram_png_path, DrawHistogramDebug(result.bins, result.count_pred_by_bin_int));

    if (!result.debug.histogram_csv_path.empty()) {
        std::ofstream csv(result.debug.histogram_csv_path, std::ios::out | std::ios::trunc);
        if (csv) {
            csv << "bin_mm,hist_prob,count_pred_float,count_pred_int\n";
            for (size_t i = 0; i < result.bins.size(); ++i) {
                csv << result.bins[i] << ","
                    << std::fixed << std::setprecision(6)
                    << SafeNumber(i < result.hist_prob.size() ? result.hist_prob[i] : 0.0f) << ","
                    << std::fixed << std::setprecision(6)
                    << SafeNumber(i < result.count_pred_by_bin.size() ? result.count_pred_by_bin[i] : 0.0f)
                    << ","
                    << (i < result.count_pred_by_bin_int.size() ? result.count_pred_by_bin_int[i] : 0)
                    << "\n";
            }
        }
    }

    if (!result.debug.runtime_json_path.empty()) {
        std::ofstream json(result.debug.runtime_json_path, std::ios::out | std::ios::trunc);
        if (json) {
            json << PipelineResultToJson(result);
        }
    }
}

    void SaveVisualOverlay(const std::string& path, const PipelineInputs& inputs, const PipelineResult& result) {
        if (path.empty() || !fs::exists(path)) return;

        cv::Mat canvas = cv::imread(path);
        if (canvas.empty()) return;

        const int orig_w = inputs.seg.orig_bgr.cols;
        const int orig_h = inputs.seg.orig_bgr.rows;
        if (orig_w <= 0 || orig_h <= 0) return;

        const float scaleX = static_cast<float>(canvas.cols) / static_cast<float>(orig_w);
        const float scaleY = static_cast<float>(canvas.rows) / static_cast<float>(orig_h);

        // Máscara global de ocupación: define dónde "ya hay uva"
        cv::Mat global_occ = cv::Mat::zeros(canvas.size(), CV_8UC1);

        struct DrawItem {
            cv::Point2f center;
            cv::Size2f visual_axes;  // tamaño del contorno visible
            cv::Size2f occ_axes;     // tamaño usado para oclusión / corte
            cv::Scalar color;
        };

        std::vector<DrawItem> items;
        items.reserve(result.detections.size());

        // Estilo cliente final
        const cv::Scalar kClientGreen(76, 175, 80);  // uvas
        const cv::Scalar kTechBlue(255, 180, 50);    // ping pong

        // 1) Preparar items y construir máscara global de ocupación
        for (const auto& det : result.detections) {
            if (det.class_name.find("bunch") != std::string::npos) {
                continue;
            }

            if (det.score < 0.50f) {
                continue;
            }
            const bool is_ping = (det.class_name.find("ping") != std::string::npos);
            const cv::Scalar color = is_ping ? kTechBlue : kClientGreen;

            const cv::Point2f center(
                    (det.x + det.w / 2.0f) * scaleX,
                    (det.y + det.h / 2.0f) * scaleY
            );

            // visual_axes: lo que el usuario ve
            const cv::Size2f visual_axes(
                    (det.w * 0.43f) * scaleX,
                    (det.h * 0.43f) * scaleY
            );

            // occ_axes: lo que usa el sistema para cortar contornos
            const cv::Size2f occ_axes(
                    (det.w * 0.40f) * scaleX,
                    (det.h * 0.40f) * scaleY
            );

            items.push_back({center, visual_axes, occ_axes, color});

            // La ocupación global debe usar occ_axes, no visual_axes
            cv::ellipse(
                    global_occ,
                    center,
                    occ_axes,
                    0.0,
                    0.0,
                    360.0,
                    cv::Scalar(255),
                    cv::FILLED
            );
        }

        GP_LOGD("NATIVE_OVERLAY_STYLE: FINAL_CLIENT_GREEN_BLUE_OCCLUSION | Detections: %zu", items.size());

        // 2) Renderizado local por ROI con corte de contornos
        for (const auto& item : items) {
            const int pad = 2;

            // El ROI debe cubrir completamente el contorno visible
            cv::Rect roi_rect(
                    static_cast<int>(std::floor(item.center.x - item.visual_axes.width - pad)),
                    static_cast<int>(std::floor(item.center.y - item.visual_axes.height - pad)),
                    static_cast<int>(std::ceil(item.visual_axes.width * 2.0f + pad * 2.0f)),
                    static_cast<int>(std::ceil(item.visual_axes.height * 2.0f + pad * 2.0f))
            );

            roi_rect &= cv::Rect(0, 0, canvas.cols, canvas.rows);
            if (roi_rect.width <= 0 || roi_rect.height <= 0) {
                continue;
            }

            cv::Mat local_stroke = cv::Mat::zeros(roi_rect.size(), CV_8UC1);
            cv::Mat self_fill    = cv::Mat::zeros(roi_rect.size(), CV_8UC1);

            const cv::Point2f local_center(
                    item.center.x - static_cast<float>(roi_rect.x),
                    item.center.y - static_cast<float>(roi_rect.y)
            );

            // Contorno visible: usa visual_axes
            cv::ellipse(
                    local_stroke,
                    local_center,
                    item.visual_axes,
                    0.0,
                    0.0,
                    360.0,
                    cv::Scalar(255),
                    1,
                    cv::LINE_AA
            );

            // Relleno propio para restarse de la ocupación: usa occ_axes
            cv::ellipse(
                    self_fill,
                    local_center,
                    item.occ_axes,
                    0.0,
                    0.0,
                    360.0,
                    cv::Scalar(255),
                    cv::FILLED
            );

            // Otras uvas = ocupación global menos mi propio cuerpo
            cv::Mat others_occ = global_occ(roi_rect).clone();
            cv::subtract(others_occ, self_fill, others_occ);

            // Invertimos para quedarnos solo con zonas libres
            cv::Mat others_inv;
            cv::bitwise_not(others_occ, others_inv);

            // El contorno visible solo puede pintarse donde no invade otra uva
            cv::Mat visible_stroke;
            cv::bitwise_and(local_stroke, others_inv, visible_stroke);

            // Pintado final
            canvas(roi_rect).setTo(item.color, visible_stroke);
        }

        cv::imwrite(path, canvas);
    }

std::string PipelineResultToJson(const PipelineResult& result) {
    std::ostringstream oss;
    oss << std::fixed << std::setprecision(6) << "{";
    oss << "\"status\":" << (result.status ? "true" : "false") << ",";
    oss << "\"error\":\"" << JsonEscape(result.error) << "\",";
    oss << "\"variety\":\"" << JsonEscape(result.variety) << "\",";
    oss << "\"variety_idx\":" << result.variety_idx << ",";
    oss << "\"input_mode\":\"" << JsonEscape(result.input_mode) << "\",";
    oss << "\"used_synthetic_fallback\":" << (result.used_synthetic_fallback ? "true" : "false") << ",";
    oss << "\"count_total\":" << SafeNumber(result.count_total) << ",";
    oss << "\"mean\":" << SafeNumber(result.mean) << ",";
    oss << "\"mode\":" << SafeNumber(result.mode) << ",";
    oss << "\"std\":" << SafeNumber(result.std) << ",";
    oss << "\"peak_bin_mm\":" << result.peak_bin_mm << ",";
    oss << "\"seg_count_base\":" << SafeNumber(result.seg_count_base) << ",";
    oss << "\"inf_ms\":" << DerivedInfMs(result) << ",";
    oss << "\"post_ms\":" << SafeNumber(result.timing.post_ms) << ",";

    AppendIntArray(oss, "bins", result.bins);
    oss << ",";
    AppendFloatArray(oss, "hist_prob", result.hist_prob);
    oss << ",";
    AppendFloatArray(oss, "count_pred_by_bin", result.count_pred_by_bin);
    oss << ",";
    AppendIntArray(oss, "count_pred_by_bin_int", result.count_pred_by_bin_int);
    oss << ",";
    AppendIntArray(oss, "pred", result.count_pred_by_bin_int);
    oss << ",";

    oss << "\"providers\":{";
    oss << "\"seg\":\"" << JsonEscape(result.seg_provider) << "\",";
    oss << "\"qty\":\"" << JsonEscape(result.qty_provider) << "\",";
    oss << "\"hist\":\"" << JsonEscape(result.hist_provider) << "\"";
    oss << "},";

    oss << "\"model_paths\":{";
    oss << "\"segmentation\":\"" << JsonEscape(result.segmentation_model_path) << "\",";
    oss << "\"qty\":\"" << JsonEscape(result.qty_model_path) << "\",";
    oss << "\"hist\":\"" << JsonEscape(result.hist_model_path) << "\"";
    oss << "},";

    oss << "\"timing_ms\":{";
    oss << "\"segmentation_ms\":" << SafeNumber(result.timing.segmentation_ms) << ",";
    oss << "\"preprocess_ms\":" << SafeNumber(result.timing.preprocess_ms) << ",";
    oss << "\"qty_ms\":" << SafeNumber(result.timing.qty_ms) << ",";
    oss << "\"hist_ms\":" << SafeNumber(result.timing.hist_ms) << ",";
    oss << "\"post_ms\":" << SafeNumber(result.timing.post_ms) << ",";
    oss << "\"total_ms\":" << SafeNumber(result.timing.total_ms);
    oss << "},";

    oss << "\"detections\":[";
    for (size_t i = 0; i < result.detections.size(); ++i) {
        if (i > 0) {
            oss << ",";
        }
        const DetectionSummary& det = result.detections[i];
        oss << "{";
        oss << "\"cls\":" << det.class_id << ",";
        oss << "\"class_id\":" << det.class_id << ",";
        oss << "\"class_name\":\"" << JsonEscape(det.class_name) << "\",";
        oss << "\"score\":" << SafeNumber(det.score) << ",";
        oss << "\"box\":["
            << SafeNumber(det.x) << ","
            << SafeNumber(det.y) << ","
            << SafeNumber(det.w) << ","
            << SafeNumber(det.h) << "]";
        oss << "}";
    }
    oss << "],";

    oss << "\"debug\":{";
    oss << "\"enabled\":" << (result.debug.enabled ? "true" : "false") << ",";
    oss << "\"output_dir\":\"" << JsonEscape(result.debug.output_dir) << "\",";
    oss << "\"overlay\":\"" << JsonEscape(result.debug.overlay_path) << "\",";
    oss << "\"grapes_mask\":\"" << JsonEscape(result.debug.grapes_mask_path) << "\",";
    oss << "\"pingpong_mask\":\"" << JsonEscape(result.debug.pingpong_mask_path) << "\",";
    oss << "\"dt_grapes\":\"" << JsonEscape(result.debug.dt_grapes_path) << "\",";
    oss << "\"dt_pingpong\":\"" << JsonEscape(result.debug.dt_pingpong_path) << "\",";
    oss << "\"histogram_csv\":\"" << JsonEscape(result.debug.histogram_csv_path) << "\",";
    oss << "\"histogram_png\":\"" << JsonEscape(result.debug.histogram_png_path) << "\",";
    oss << "\"runtime_json\":\"" << JsonEscape(result.debug.runtime_json_path) << "\"";
    oss << "}";
    oss << "}";
    return oss.str();
}

std::string ErrorToJson(const std::string& error_message) {
    PipelineResult result;
    result.status = false;
    result.error = error_message;
    result.bins = DefaultBinsVector();
    return PipelineResultToJson(result);
}

}  // namespace grape
