#include "grape_pipeline_postprocess.hpp"

#include "grape_pipeline_config.hpp"
#include "grape_pipeline_preprocess.hpp"
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

// ══════════════════════════════════════════════════════════════════════════════
// VISUAL-ONLY OVERLAY PIPELINE — modular helpers
// ══════════════════════════════════════════════════════════════════════════════

namespace ov = overlay_visual;

std::tuple<float, float, float> RecomputeLetterboxParams(
    const cv::Size& original_size,
    int model_img_size) {
    const float ratio = std::min(
        static_cast<float>(model_img_size) / static_cast<float>(original_size.width),
        static_cast<float>(model_img_size) / static_cast<float>(original_size.height));
    const int rw = static_cast<int>(std::round(original_size.width * ratio));
    const int rh = static_cast<int>(std::round(original_size.height * ratio));
    const float dw = (static_cast<float>(model_img_size) - rw) / 2.0f;
    const float dh = (static_cast<float>(model_img_size) - rh) / 2.0f;
    return {ratio, dw, dh};
}

cv::Mat CleanMaskForOverlay(cv::Mat mask) {
    if (mask.empty() || cv::countNonZero(mask) == 0) {
        return {};
    }
    cv::threshold(mask, mask, ov::kBinaryThreshold, ov::kBinaryMaxVal, cv::THRESH_BINARY);
    if (cv::countNonZero(mask) == 0) {
        return {};
    }
    const cv::Mat kernel = cv::getStructuringElement(
        cv::MORPH_ELLIPSE,
        cv::Size(ov::kMorphKernelSize, ov::kMorphKernelSize));
    cv::morphologyEx(mask, mask, cv::MORPH_CLOSE, kernel);
    cv::threshold(mask, mask, ov::kBinaryThreshold, ov::kBinaryMaxVal, cv::THRESH_BINARY);
    if (cv::countNonZero(mask) == 0) {
        return {};
    }
    cv::GaussianBlur(
        mask, mask,
        cv::Size(ov::kBlurKernelSize, ov::kBlurKernelSize), 0.0);
    cv::threshold(mask, mask, ov::kSoftThreshold, ov::kBinaryMaxVal, cv::THRESH_BINARY);
    return mask;
}

cv::Mat MapMaskToCanvas(
    const cv::Mat& mask_src,
    const cv::Size& orig_size,
    const cv::Size& canvas_size,
    int model_img_size) {
    if (mask_src.empty() || canvas_size.width <= 0 || canvas_size.height <= 0) {
        return {};
    }
    cv::Mat binary = MakeBinaryOverlayMask(mask_src);
    if (binary.empty() || cv::countNonZero(binary) == 0) {
        return {};
    }

    cv::Mat mask_in_orig;
    if (binary.size() == orig_size) {
        mask_in_orig = binary;
    } else {
        const auto [ratio, dw, dh] = RecomputeLetterboxParams(orig_size, model_img_size);
        mask_in_orig = ScaleMaskBackToOriginal(binary, ratio, dw, dh, orig_size);
    }

    if (mask_in_orig.empty() || cv::countNonZero(mask_in_orig) == 0) {
        return {};
    }

    if (mask_in_orig.size() != canvas_size) {
        cv::Mat resized;
        cv::resize(mask_in_orig, resized, canvas_size, 0.0, 0.0, cv::INTER_NEAREST);
        return CleanMaskForOverlay(resized);
    }
    return CleanMaskForOverlay(mask_in_orig.clone());
}

cv::Point2f ComputeVisualCentroid(const cv::Mat& mask) {
    if (mask.empty()) {
        return cv::Point2f(-1.0f, -1.0f);
    }
    const auto m = cv::moments(mask, true);
    if (m.m00 > 0.0) {
        return cv::Point2f(
            static_cast<float>(m.m10 / m.m00),
            static_cast<float>(m.m01 / m.m00));
    }
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(mask, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
    if (!contours.empty()) {
        const cv::Rect br = cv::boundingRect(contours.front());
        return cv::Point2f(
            br.x + br.width / 2.0f,
            br.y + br.height / 2.0f);
    }
    return cv::Point2f(-1.0f, -1.0f);
}

cv::Mat BuildCombinedBunchGrapeMaskForOverlay(
    const PipelineInputs& inputs,
    const cv::Size& canvas_size,
    int model_img_size) {
    const cv::Size orig_size = inputs.seg.orig_bgr.size();
    if (orig_size.width <= 0 || orig_size.height <= 0) {
        return {};
    }

    cv::Mat combined_orig = cv::Mat::zeros(orig_size, CV_8U);
    {
        cv::Mat bunch_bin = MakeBinaryOverlayMask(inputs.seg.bunch_global_orig);
        if (!bunch_bin.empty()) cv::max(combined_orig, bunch_bin, combined_orig);
    }
    {
        cv::Mat grape_bin = MakeBinaryOverlayMask(inputs.seg.grapes_global_orig);
        if (!grape_bin.empty()) cv::max(combined_orig, grape_bin, combined_orig);
    }

    if (cv::countNonZero(combined_orig) > 0) {
        cv::Mat resized;
        cv::resize(combined_orig, resized, canvas_size, 0.0, 0.0, cv::INTER_NEAREST);
        return CleanMaskForOverlay(resized);
    }

    const auto [ratio, dw, dh] = RecomputeLetterboxParams(orig_size, model_img_size);
    cv::Mat combined_lb = cv::Mat::zeros(cv::Size(model_img_size, model_img_size), CV_8U);
    {
        cv::Mat bunch_lb_bin = MakeBinaryOverlayMask(inputs.seg.bunch_global_lb);
        if (!bunch_lb_bin.empty()) cv::max(combined_lb, bunch_lb_bin, combined_lb);
    }
    {
        cv::Mat grape_lb_bin = MakeBinaryOverlayMask(inputs.seg.grapes_global_lb);
        if (!grape_lb_bin.empty()) cv::max(combined_lb, grape_lb_bin, combined_lb);
    }
    if (cv::countNonZero(combined_lb) == 0) {
        combined_lb = MergeInstanceMasksForOverlay(
            inputs.seg.grape_instance_masks_lb,
            cv::Size(model_img_size, model_img_size));
    }

    if (cv::countNonZero(combined_lb) > 0) {
        cv::Mat orig_mask = ScaleMaskBackToOriginal(combined_lb, ratio, dw, dh, orig_size);
        cv::Mat resized;
        cv::resize(orig_mask, resized, canvas_size, 0.0, 0.0, cv::INTER_NEAREST);
        return CleanMaskForOverlay(resized);
    }

    return {};
}

cv::Mat BuildGlobalPingpongMaskForOverlay(
    const PipelineInputs& inputs,
    const cv::Size& canvas_size,
    int model_img_size) {
    const cv::Size orig_size = inputs.seg.orig_bgr.size();
    if (orig_size.width <= 0 || orig_size.height <= 0) {
        return {};
    }

    cv::Mat mask = MapMaskToCanvas(
        inputs.seg.pingpong_global_orig, orig_size, canvas_size, model_img_size);

    if (mask.empty() || cv::countNonZero(mask) == 0) {
        const auto [ratio, dw, dh] = RecomputeLetterboxParams(orig_size, model_img_size);
        cv::Mat lb_mask = MakeBinaryOverlayMask(inputs.seg.pingpong_global_lb);
        if (lb_mask.empty() || cv::countNonZero(lb_mask) == 0) {
            lb_mask = MergeInstanceMasksForOverlay(
                inputs.seg.pingpong_instance_masks_lb,
                cv::Size(model_img_size, model_img_size));
        }
        if (!lb_mask.empty() && cv::countNonZero(lb_mask) > 0) {
            cv::Mat orig_mask = ScaleMaskBackToOriginal(lb_mask, ratio, dw, dh, orig_size);
            cv::Mat resized;
            cv::resize(orig_mask, resized, canvas_size, 0.0, 0.0, cv::INTER_NEAREST);
            mask = CleanMaskForOverlay(resized);
        }
    }

    return mask;
}

void DrawFilledCentroid(
    cv::Mat& canvas,
    const cv::Point2f& centroid,
    const cv::Scalar& color,
    int radius) {
    if (centroid.x < 0 || centroid.y < 0) return;
    cv::circle(canvas, centroid, radius + 1, cv::Scalar(25, 25, 25), cv::FILLED, cv::LINE_AA);
    cv::circle(canvas, centroid, radius, color, cv::FILLED, cv::LINE_AA);
}

std::vector<cv::Point2f> RenderOfficialGrapeDots(
    cv::Mat& canvas,
    const std::vector<cv::Mat>& grape_instance_masks_lb,
    const cv::Size& orig_size,
    int model_img_size) {
    std::vector<cv::Point2f> points;
    if (canvas.empty() || grape_instance_masks_lb.empty()) return points;
    if (orig_size.width <= 0 || orig_size.height <= 0) return points;

    const auto [ratio, dw, dh] = RecomputeLetterboxParams(orig_size, model_img_size);

    const int radius = std::max(
        ov::kCentroidRadiusMinGrape,
        std::min(ov::kCentroidRadiusMaxGrape,
                 static_cast<int>(canvas.cols / ov::kCentroidRadiusFactorGrape)));

    const float scale_x = static_cast<float>(canvas.cols) / static_cast<float>(orig_size.width);
    const float scale_y = static_cast<float>(canvas.rows) / static_cast<float>(orig_size.height);

    for (const cv::Mat& mask_lb : grape_instance_masks_lb) {
        if (mask_lb.empty()) continue;

        const cv::Point2f centroid_lb = ComputeVisualCentroid(mask_lb);
        if (centroid_lb.x < 0 || centroid_lb.y < 0) continue;

        float orig_x = std::clamp(
            (centroid_lb.x - dw) / ratio, 0.0f,
            static_cast<float>(orig_size.width - 1));
        float orig_y = std::clamp(
            (centroid_lb.y - dh) / ratio, 0.0f,
            static_cast<float>(orig_size.height - 1));

        const cv::Point2f canvas_pt(orig_x * scale_x, orig_y * scale_y);
        DrawFilledCentroid(canvas, canvas_pt, ov::kColorGrapeDot, radius);
        points.push_back(canvas_pt);
    }

    return points;
}

struct AuxBerryStats {
    int raw_candidates       = 0;
    int rejected_low_dt      = 0;
    int peak_candidates      = 0;
    int rejected_near_off    = 0;
    int rejected_duplicates  = 0;
    int after_nms            = 0;
};

AuxBerryStats BuildAuxiliaryBerryPoints(
    std::vector<cv::Point2f>& aux_points_out,
    const cv::Mat& bunch_grape_mask,
    const cv::Mat& pingpong_mask,
    const std::vector<cv::Point2f>& official_points,
    const cv::Size& canvas_size) {
    AuxBerryStats stats;
    aux_points_out.clear();
    if (bunch_grape_mask.empty() || cv::countNonZero(bunch_grape_mask) == 0) {
        return stats;
    }

    cv::Mat dt;
    cv::distanceTransform(bunch_grape_mask, dt, cv::DIST_L2, 5);

    const int window_size = std::max(
        ov::kAuxWindowMin,
        std::min(ov::kAuxWindowMax,
                 static_cast<int>(canvas_size.width / ov::kAuxWindowFactor)));
    const cv::Mat kernel = cv::getStructuringElement(
        cv::MORPH_ELLIPSE, cv::Size(window_size, window_size));
    cv::Mat dilated;
    cv::dilate(dt, dilated, kernel);

    const float peak_min = std::max(
        ov::kAuxPeakMinAbs,
        canvas_size.width / ov::kAuxPeakMinFactor);

    const int min_dist_off = std::max(
        ov::kAuxOffDistMin,
        static_cast<int>(canvas_size.width / ov::kAuxOffDistFactor));
    const int min_dist_nms = std::max(
        ov::kAuxNmsDistMin,
        static_cast<int>(canvas_size.width / ov::kAuxNmsDistFactor));
    const int min_dist_off_sq = min_dist_off * min_dist_off;
    const int min_dist_nms_sq = min_dist_nms * min_dist_nms;

    const int max_points = std::max(
        ov::kAuxMaxPointsMin,
        std::min(ov::kAuxMaxPointsMax,
                 static_cast<int>(canvas_size.width / ov::kAuxMaxPointsFactor)));

    std::vector<std::pair<cv::Point2f, float>> candidates;
    candidates.reserve(static_cast<size_t>(dt.rows * dt.cols / 32));

    bool has_pingpong = !pingpong_mask.empty() && cv::countNonZero(pingpong_mask) > 0;

    for (int y = 0; y < dt.rows; ++y) {
        const float* dt_row  = dt.ptr<float>(y);
        const float* dil_row = dilated.ptr<float>(y);
        for (int x = 0; x < dt.cols; ++x) {
            const float val = dt_row[x];
            if (val <= 0.0f) continue;               // background
            stats.raw_candidates++;

            if (val < ov::kAuxDtMinRadius) continue;  // too small
            if (val > ov::kAuxDtMaxRadius) continue;  // large stem/blob

            if (val < peak_min) {
                stats.rejected_low_dt++;
                continue;
            }

            if (val != dil_row[x]) continue;           // not a local maximum

            if (has_pingpong && pingpong_mask.at<uchar>(y, x) > 0) continue;

            stats.peak_candidates++;

            const cv::Point2f pt(static_cast<float>(x), static_cast<float>(y));

            bool too_close = false;
            for (const cv::Point2f& op : official_points) {
                const float dx = pt.x - op.x;
                const float dy = pt.y - op.y;
                if (dx * dx + dy * dy < static_cast<float>(min_dist_off_sq)) {
                    too_close = true;
                    break;
                }
            }
            if (too_close) {
                stats.rejected_near_off++;
                continue;
            }

            candidates.emplace_back(pt, val);
        }
    }

    std::sort(candidates.begin(), candidates.end(),
              [](const auto& a, const auto& b) { return a.second > b.second; });

    for (const auto& [pt, dt_val] : candidates) {
        if (static_cast<int>(aux_points_out.size()) >= max_points) break;

        bool too_close = false;
        for (const cv::Point2f& ap : aux_points_out) {
            const float dx = pt.x - ap.x;
            const float dy = pt.y - ap.y;
            if (dx * dx + dy * dy < static_cast<float>(min_dist_nms_sq)) {
                too_close = true;
                break;
            }
        }
        if (too_close) {
            stats.rejected_duplicates++;
            continue;
        }

        // Refine center: find local DT maximum in small window
        constexpr int rhw = ov::kAuxRefineHalfWin;
        int best_x = static_cast<int>(pt.x);
        int best_y = static_cast<int>(pt.y);
        float best_val = dt.at<float>(best_y, best_x);
        for (int dy = -rhw; dy <= rhw; ++dy) {
            for (int dx = -rhw; dx <= rhw; ++dx) {
                const int nx = best_x + dx;
                const int ny = best_y + dy;
                if (nx < 0 || nx >= dt.cols || ny < 0 || ny >= dt.rows) continue;
                const float v = dt.at<float>(ny, nx);
                if (v > best_val) {
                    best_val = v;
                    best_x = nx;
                    best_y = ny;
                }
            }
        }
        aux_points_out.emplace_back(
            static_cast<float>(best_x), static_cast<float>(best_y));
    }

    stats.after_nms = static_cast<int>(aux_points_out.size());
    return stats;
}

void RenderAuxiliaryBerryDots(
    cv::Mat& canvas,
    const std::vector<cv::Point2f>& aux_points) {
    if (canvas.empty() || aux_points.empty()) return;

    const int radius = std::max(
        ov::kCentroidRadiusMinGrape,
        std::min(ov::kCentroidRadiusMaxGrape,
                 static_cast<int>(canvas.cols / ov::kCentroidRadiusFactorAux)));

    for (const cv::Point2f& pt : aux_points) {
        DrawFilledCentroid(canvas, pt, ov::kColorAuxiliaryDot, radius);
    }
}

void RenderCentroids(
    cv::Mat& canvas,
    const cv::Mat& bunch_grape_mask,
    const cv::Mat& pingpong_mask) {
    const bool has_bg = !bunch_grape_mask.empty() && cv::countNonZero(bunch_grape_mask) > 0;
    const bool has_pp = !pingpong_mask.empty()    && cv::countNonZero(pingpong_mask)    > 0;

    const int bg_radius = std::max(
        ov::kCentroidRadiusMinGrape,
        std::min(ov::kCentroidRadiusMaxGrape,
                 static_cast<int>(canvas.cols / ov::kCentroidRadiusFactorGrape)));
    const int pp_radius = std::max(
        ov::kCentroidRadiusMinPingpong,
        std::min(ov::kCentroidRadiusMaxPingpong,
                 static_cast<int>(canvas.cols / ov::kCentroidRadiusFactorPingpong)));

    if (has_bg) {
        const cv::Point2f bg_c = ComputeVisualCentroid(bunch_grape_mask);
        if (bg_c.x >= 0 && bg_c.y >= 0) {
            DrawFilledCentroid(canvas, bg_c, ov::kColorCentroidBunchGrape, bg_radius);
        }
    }
    if (has_pp) {
        const cv::Point2f pp_c = ComputeVisualCentroid(pingpong_mask);
        if (pp_c.x >= 0 && pp_c.y >= 0) {
            DrawFilledCentroid(canvas, pp_c, ov::kColorCentroidPingpong, pp_radius);
        }
    }
}

void RenderVisualOverlayLayers(
    cv::Mat& canvas,
    const cv::Mat& bunch_grape_mask,
    const cv::Mat& pingpong_mask) {
    const bool has_bg = !bunch_grape_mask.empty() && cv::countNonZero(bunch_grape_mask) > 0;
    const bool has_pp = !pingpong_mask.empty()    && cv::countNonZero(pingpong_mask)    > 0;

    if (!has_bg && !has_pp) return;

    // Layer 1: Soft fill bunch+grape
    if (has_bg) {
        cv::Mat color_layer(canvas.size(), canvas.type(), ov::kColorFillBunchGrape);
        cv::Mat blended;
        cv::addWeighted(
            canvas, 1.0 - ov::kFillAlphaBunchGrape,
            color_layer, ov::kFillAlphaBunchGrape, 0.0, blended);
        blended.copyTo(canvas, bunch_grape_mask);
    }

    // Capa 2: Fill pingpong
    if (has_pp) {
        cv::Mat color_layer(canvas.size(), canvas.type(), ov::kColorFillPingpong);
        cv::Mat blended;
        cv::addWeighted(
            canvas, 1.0 - ov::kFillAlphaPingpong,
            color_layer, ov::kFillAlphaPingpong, 0.0, blended);
        blended.copyTo(canvas, pingpong_mask);
    }

    // Layer 3: Pingpong contour
    if (has_pp) {
        std::vector<std::vector<cv::Point>> pp_contours;
        cv::findContours(pingpong_mask.clone(), pp_contours,
                         cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
        if (!pp_contours.empty()) {
            const int pp_thickness = std::max(
                ov::kContourThickMinPingpong,
                std::min(ov::kContourThickMaxPingpong,
                         static_cast<int>(canvas.cols / ov::kContourWidthFactorPingpong)));
            cv::drawContours(
                canvas, pp_contours, -1,
                ov::kColorContourPingpong, pp_thickness, cv::LINE_AA);
        }
    }

    // Layer 4: Outer bunch+grape contour (cyan, simplified)
    if (has_bg) {
        std::vector<std::vector<cv::Point>> bg_contours;
        cv::findContours(bunch_grape_mask.clone(), bg_contours,
                         cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
        if (!bg_contours.empty()) {
            const int bg_thickness = std::max(
                ov::kContourThickMinBunchGrape,
                std::min(ov::kContourThickMaxBunchGrape,
                         static_cast<int>(canvas.cols / ov::kContourWidthFactorBunchGrape)));

            std::vector<std::vector<cv::Point>> simplified;
            simplified.reserve(bg_contours.size());
            for (const auto& c : bg_contours) {
                const double peri = cv::arcLength(c, true);
                const double eps = ov::kApproxPolyEpsilonFactor * peri;
                std::vector<cv::Point> approx;
                cv::approxPolyDP(c, approx, eps, true);
                simplified.push_back(std::move(approx));
            }
            cv::drawContours(
                canvas, simplified, -1,
                ov::kColorContourBunchGrape, bg_thickness, cv::LINE_AA);
        }
    }
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

    constexpr int kModelImgSize = 512;

    cv::Mat bunch_grape_mask = BuildCombinedBunchGrapeMaskForOverlay(
        inputs, canvas.size(), kModelImgSize);
    cv::Mat ping_mask = BuildGlobalPingpongMaskForOverlay(
        inputs, canvas.size(), kModelImgSize);

    const bool has_bg = !bunch_grape_mask.empty() && cv::countNonZero(bunch_grape_mask) > 0;
    const bool has_pp = !ping_mask.empty()        && cv::countNonZero(ping_mask)        > 0;

    if (!has_bg && !has_pp) {
        GP_LOGD("OVERLAY_DIAG canvas=%dx%d bunch_nonzero=0 pingpong_nonzero=0 centroids=0 contours=0",
                canvas.cols, canvas.rows);
        return;
    }

    RenderVisualOverlayLayers(canvas, bunch_grape_mask, ping_mask);

    const std::vector<cv::Point2f> official_pts = RenderOfficialGrapeDots(
        canvas,
        inputs.seg.grape_instance_masks_lb,
        inputs.seg.orig_bgr.size(),
        kModelImgSize);

    int aux_peak = 0;
    int aux_after_nms = 0;
    AuxBerryStats aux_stats;
    if (has_bg) {
        std::vector<cv::Point2f> aux_pts;
        aux_stats = BuildAuxiliaryBerryPoints(
            aux_pts, bunch_grape_mask, ping_mask, official_pts, canvas.size());
        aux_peak = aux_stats.peak_candidates;
        aux_after_nms = aux_stats.after_nms;
        if (!aux_pts.empty()) {
            RenderAuxiliaryBerryDots(canvas, aux_pts);
        }
    }

    RenderCentroids(canvas, bunch_grape_mask, ping_mask);

    int off_drawn  = static_cast<int>(official_pts.size());
    int final_pts  = off_drawn + aux_after_nms;

    cv::imwrite(path, canvas);
    GP_LOGD("OVERLAY_POINTS canvas=%dx%d official=%d"
            " aux_raw=%d aux_peaks=%d aux_after_nms=%d"
            " rejected_near_official=%d rejected_duplicates=%d rejected_low_dt=%d"
            " pingpong=%d final=%d",
            canvas.cols, canvas.rows,
            off_drawn,
            aux_stats.raw_candidates,
            aux_peak,
            aux_after_nms,
            aux_stats.rejected_near_off,
            aux_stats.rejected_duplicates,
            aux_stats.rejected_low_dt,
            (has_pp ? 1 : 0),
            final_pts);
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
