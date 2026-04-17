#pragma once

#include "grape_pipeline_types.hpp"

#include <onnxruntime_cxx_api.h>
#include <opencv2/core.hpp>

#include <string>

namespace grape {

LetterboxResult Letterbox(
    const cv::Mat& image,
    int new_width,
    int new_height,
    const cv::Scalar& color = cv::Scalar(114, 114, 114));

cv::Mat ScaleMaskBackToOriginal(
    const cv::Mat& mask_letterboxed,
    float ratio,
    float dw,
    float dh,
    const cv::Size& original_size);

cv::Mat ComputeInstanceWiseDistanceTransform(
    const std::vector<cv::Mat>& instance_masks_letterboxed,
    const cv::Size& out_size);

SegmentationOutput RunSegmentationPipeline(
    Ort::Session& segmentation_session,
    const std::string& image_path,
    int img_size,
    float conf_threshold,
    float mask_threshold);

PipelineInputs BuildPipelineInputs(
    const std::string& image_path,
    const std::string& variety_name,
    int64_t variety_index,
    const RuntimeModelContract& contract,
    Ort::Session* segmentation_session,
    bool allow_synthetic_rgbdt);

}  // namespace grape
