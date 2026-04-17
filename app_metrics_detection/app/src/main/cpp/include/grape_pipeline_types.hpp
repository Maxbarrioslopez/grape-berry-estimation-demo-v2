#pragma once

#include <array>
#include <cstdint>
#include <opencv2/core.hpp>
#include <string>
#include <vector>

namespace grape {

enum class ProviderPreference {
    AndroidAuto,
    Nnapi,
    Cpu,
};

struct LetterboxResult {
    cv::Mat image;
    float ratio = 1.0f;
    float dw = 0.0f;
    float dh = 0.0f;
};

struct DetectionSummary {
    int class_id = -1;
    std::string class_name;
    float score = 0.0f;
    float x = 0.0f;
    float y = 0.0f;
    float w = 0.0f;
    float h = 0.0f;
};

struct SegmentationOutput {
    cv::Mat orig_bgr;
    cv::Mat orig_rgb;
    cv::Mat rgb_lb;

    cv::Mat bunch_global_orig;
    cv::Mat grapes_global_orig;
    cv::Mat pingpong_global_orig;

    cv::Mat bunch_global_lb;
    cv::Mat grapes_global_lb;
    cv::Mat pingpong_global_lb;

    cv::Mat grapes_dt_instance_lb;
    cv::Mat pingpong_dt_instance_lb;

    std::vector<cv::Mat> grape_instance_masks_lb;
    std::vector<cv::Mat> pingpong_instance_masks_lb;
    std::vector<DetectionSummary> detections;

    int num_bunch_det = 0;
    int num_grape_det = 0;
    int num_pingpong_det = 0;
    bool used_synthetic_fallback = false;
};

struct PipelineInputs {
    std::vector<float> x;
    std::array<int64_t, 1> variety_idx {0};
    std::array<float, 1> seg_count_base {0.0f};
    std::string variety_name;
    int input_channels = 0;

    cv::Mat rgb_lb;
    cv::Mat grapes_dt_instance_lb;
    cv::Mat pingpong_dt_instance_lb;

    SegmentationOutput seg;
    bool used_synthetic_fallback = false;
};

struct SessionMetadata {
    std::vector<std::string> input_names;
    std::vector<std::string> output_names;
    std::string x_input_name;
    std::string variety_input_name;
    std::string seg_count_input_name;
    std::string main_output_name;
    int x_channels = 0;
    int img_size = 0;
    int output_bins = 0;
    bool has_seg_count_base = false;
};

struct RuntimeModelContract {
    int img_size = 512;
    int input_channels = 0;
    int output_bins = 26;
    bool uses_seg_count_base = true;
    std::string input_mode = "rgbdt";

    std::string hist_x_input_name;
    std::string hist_variety_input_name;
    std::string hist_output_name;

    std::string qty_x_input_name;
    std::string qty_variety_input_name;
    std::string qty_seg_count_input_name;
    std::string qty_output_name;
};

struct BundleResolution {
    std::string bundle_dir;
    std::string segmentation_path;
    std::string qty_path;
    std::string hist_path;
    std::vector<std::string> variety_classes;
    std::string note;
};

struct TimingBreakdown {
    double segmentation_ms = 0.0;
    double preprocess_ms = 0.0;
    double qty_ms = 0.0;
    double hist_ms = 0.0;
    double post_ms = 0.0;
    double total_ms = 0.0;
};

struct DebugArtifacts {
    bool enabled = false;
    std::string output_dir;
    std::string overlay_path;
    std::string grapes_mask_path;
    std::string pingpong_mask_path;
    std::string dt_grapes_path;
    std::string dt_pingpong_path;
    std::string histogram_csv_path;
    std::string histogram_png_path;
    std::string runtime_json_path;
};

struct PipelineResult {
    bool status = false;
    std::string error;

    std::string variety;
    int variety_idx = -1;
    std::string input_mode;
    bool used_synthetic_fallback = false;

    std::string seg_provider;
    std::string qty_provider;
    std::string hist_provider;

    std::string segmentation_model_path;
    std::string qty_model_path;
    std::string hist_model_path;

    float seg_count_base = 0.0f;
    float count_total = 0.0f;
    float mean = 0.0f;
    float mode = 0.0f;
    float std = 0.0f;
    int peak_bin_mm = 0;

    std::vector<int> bins;
    std::vector<float> hist_prob;
    std::vector<float> count_pred_by_bin;
    std::vector<int> count_pred_by_bin_int;

    std::vector<DetectionSummary> detections;
    DebugArtifacts debug;
    TimingBreakdown timing;
};

}  // namespace grape
