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

cv::Mat MakeBinaryOverlayMask(const cv::Mat& input) {
    if (input.empty()) {
        return {};
    }

    cv::Mat gray;
    if (input.channels() == 1) {
        gray = input;
    } else {
        cv::cvtColor(input, gray, cv::COLOR_BGR2GRAY);
    }

    cv::Mat u8;
    if (gray.depth() == CV_8U) {
        u8 = gray.clone();
    } else {
        gray.convertTo(u8, CV_8U);
    }

    cv::Mat binary;
    cv::threshold(u8, binary, 127.0, 255.0, cv::THRESH_BINARY);
    return binary;
}

cv::Mat MergeInstanceMasksForOverlay(
    const std::vector<cv::Mat>& instance_masks,
    const cv::Size& fallback_size) {
    cv::Size merged_size = fallback_size;
    for (const cv::Mat& instance_mask : instance_masks) {
        if (!instance_mask.empty()) {
            merged_size = instance_mask.size();
            break;
        }
    }
    if (merged_size.width <= 0 || merged_size.height <= 0) {
        return {};
    }

    cv::Mat merged = cv::Mat::zeros(merged_size, CV_8U);
    for (const cv::Mat& instance_mask : instance_masks) {
        cv::Mat binary = MakeBinaryOverlayMask(instance_mask);
        if (binary.empty()) {
            continue;
        }
        if (binary.size() != merged.size()) {
            cv::resize(binary, binary, merged.size(), 0.0, 0.0, cv::INTER_NEAREST);
        }
        cv::max(merged, binary, merged);
    }
    return merged;
}

cv::Mat BuildGlobalGrapeMaskForOverlay(const PipelineInputs& inputs, const cv::Size& canvas_size) {
    cv::Mat mask = MakeBinaryOverlayMask(inputs.seg.grapes_global_orig);

    if (mask.empty() || cv::countNonZero(mask) == 0) {
        mask = MakeBinaryOverlayMask(inputs.seg.grapes_global_lb);
    }

    if (mask.empty() || cv::countNonZero(mask) == 0) {
        mask = MergeInstanceMasksForOverlay(
            inputs.seg.grape_instance_masks_lb,
            inputs.seg.grapes_global_lb.size());
    }

    if (mask.empty() || canvas_size.width <= 0 || canvas_size.height <= 0) {
        return {};
    }

    if (mask.size() != canvas_size) {
        cv::resize(mask, mask, canvas_size, 0.0, 0.0, cv::INTER_NEAREST);
    }

    cv::threshold(mask, mask, 127.0, 255.0, cv::THRESH_BINARY);
    if (cv::countNonZero(mask) == 0) {
        return {};
    }

    const cv::Mat kernel = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(3, 3));
    cv::morphologyEx(mask, mask, cv::MORPH_CLOSE, kernel);
    cv::threshold(mask, mask, 127.0, 255.0, cv::THRESH_BINARY);
    if (cv::countNonZero(mask) == 0) {
        return {};
    }

    cv::GaussianBlur(mask, mask, cv::Size(3, 3), 0.0);
    cv::threshold(mask, mask, 96.0, 255.0, cv::THRESH_BINARY);
    if (cv::countNonZero(mask) == 0) {
        return {};
    }

    return mask;
}

void ApplyMagentaMaskOverlay(cv::Mat& canvas, const cv::Mat& mask) {
    if (canvas.empty() || mask.empty()) {
        return;
    }

    const cv::Scalar fill_color(180, 0, 180);
    const cv::Scalar contour_color(255, 0, 255);
    constexpr double kFillAlpha = 0.24;

    cv::Mat color_layer(canvas.size(), canvas.type(), fill_color);
    cv::Mat blended;
    cv::addWeighted(canvas, 1.0 - kFillAlpha, color_layer, kFillAlpha, 0.0, blended);
    blended.copyTo(canvas, mask);

    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(mask.clone(), contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
    if (contours.empty()) {
        return;
    }

    const int contour_thickness = std::max(2, std::min(4, canvas.cols / 420));
    cv::drawContours(canvas, contours, -1, contour_color, contour_thickness, cv::LINE_AA);
}

cv::Mat BuildGlobalPingpongMaskForOverlay(const PipelineInputs& inputs, const cv::Size& canvas_size) {
    cv::Mat mask = MakeBinaryOverlayMask(inputs.seg.pingpong_global_orig);

    if (mask.empty() || cv::countNonZero(mask) == 0) {
        mask = MakeBinaryOverlayMask(inputs.seg.pingpong_global_lb);
    }

    if (mask.empty() || cv::countNonZero(mask) == 0) {
        mask = MergeInstanceMasksForOverlay(
            inputs.seg.pingpong_instance_masks_lb,
            inputs.seg.pingpong_global_lb.size());
    }

    if (mask.empty() || canvas_size.width <= 0 || canvas_size.height <= 0) {
        return {};
    }

    if (mask.size() != canvas_size) {
        cv::resize(mask, mask, canvas_size, 0.0, 0.0, cv::INTER_NEAREST);
    }

    cv::threshold(mask, mask, 127.0, 255.0, cv::THRESH_BINARY);
    if (cv::countNonZero(mask) == 0) {
        return {};
    }

    const cv::Mat kernel = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(3, 3));
    cv::morphologyEx(mask, mask, cv::MORPH_CLOSE, kernel);
    cv::threshold(mask, mask, 127.0, 255.0, cv::THRESH_BINARY);
    if (cv::countNonZero(mask) == 0) {
        return {};
    }

    cv::GaussianBlur(mask, mask, cv::Size(3, 3), 0.0);
    cv::threshold(mask, mask, 96.0, 255.0, cv::THRESH_BINARY);
    if (cv::countNonZero(mask) == 0) {
        return {};
    }

    return mask;
}

void ApplyPingpongMaskOverlay(cv::Mat& canvas, const cv::Mat& mask) {
    if (canvas.empty() || mask.empty()) {
        return;
    }

    const cv::Scalar fill_color(0, 220, 255);
    const cv::Scalar contour_color(0, 255, 255);
    constexpr double kFillAlpha = 0.20;

    cv::Mat color_layer(canvas.size(), canvas.type(), fill_color);
    cv::Mat blended;
    cv::addWeighted(canvas, 1.0 - kFillAlpha, color_layer, kFillAlpha, 0.0, blended);
    blended.copyTo(canvas, mask);

    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(mask.clone(), contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
    if (contours.empty()) {
        return;
    }

    const int contour_thickness = std::max(2, std::min(3, canvas.cols / 560));
    cv::drawContours(canvas, contours, -1, contour_color, contour_thickness, cv::LINE_AA);
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
    (void)result;
    if (path.empty() || !fs::exists(path)) {
        return;
    }

    cv::Mat canvas = cv::imread(path, cv::IMREAD_COLOR);
    if (canvas.empty()) {
        return;
    }

    cv::Mat grape_mask = BuildGlobalGrapeMaskForOverlay(inputs, canvas.size());
    cv::Mat ping_mask = BuildGlobalPingpongMaskForOverlay(inputs, canvas.size());

    const bool has_grape = !grape_mask.empty() && cv::countNonZero(grape_mask) > 0;
    const bool has_ping  = !ping_mask.empty()  && cv::countNonZero(ping_mask)  > 0;

    if (!has_grape && !has_ping) {
        GP_LOGD("NATIVE_OVERLAY_STYLE: MAGENTA_CYAN | empty both masks, keeping base image");
        return;
    }

    if (has_grape) {
        ApplyMagentaMaskOverlay(canvas, grape_mask);
    }

    if (has_ping) {
        ApplyPingpongMaskOverlay(canvas, ping_mask);
    }

    cv::imwrite(path, canvas);
    GP_LOGD(
        "NATIVE_OVERLAY_STYLE: MAGENTA_CYAN | grape_pixels=%d ping_pixels=%d",
        has_grape ? cv::countNonZero(grape_mask) : 0,
        has_ping  ? cv::countNonZero(ping_mask)  : 0);
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
