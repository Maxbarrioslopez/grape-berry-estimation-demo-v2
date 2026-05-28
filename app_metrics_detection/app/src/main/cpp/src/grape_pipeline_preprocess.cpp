#include "grape_pipeline_preprocess.hpp"

#include "grape_pipeline_config.hpp"
#include "grape_pipeline_onnx.hpp"

#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

#include <algorithm>
#include <array>
#include <cmath>
#include <stdexcept>
#include <string>
#include <vector>

namespace grape {
namespace {

struct LegacySegDecodeResult {
    std::vector<std::array<float, 4>> boxes_xyxy_lb;
    std::vector<float> scores;
    std::vector<int> class_ids;
    cv::Mat mask_coeffs;
    cv::Mat protos;
    int proto_c = 0;
    int proto_h = 0;
    int proto_w = 0;
};

float Sigmoid(float value) {
    return 1.0f / (1.0f + std::exp(-value));
}

std::vector<const char*> ToCStringVector(const std::vector<std::string>& names) {
    std::vector<const char*> raw;
    raw.reserve(names.size());
    for (const std::string& name : names) {
        raw.push_back(name.c_str());
    }
    return raw;
}

std::vector<float> ImageToRgbFloatChw(const cv::Mat& rgb_u8) {
    cv::Mat rgb_f32;
    rgb_u8.convertTo(rgb_f32, CV_32F, 1.0 / 255.0);

    std::vector<float> chw(static_cast<size_t>(rgb_u8.rows * rgb_u8.cols * 3));
    const size_t plane = static_cast<size_t>(rgb_u8.rows * rgb_u8.cols);
    for (int y = 0; y < rgb_u8.rows; ++y) {
        for (int x = 0; x < rgb_u8.cols; ++x) {
            const cv::Vec3f pixel = rgb_f32.at<cv::Vec3f>(y, x);
            const size_t offset = static_cast<size_t>(y * rgb_u8.cols + x);
            chw[offset] = pixel[0];
            chw[plane + offset] = pixel[1];
            chw[(2 * plane) + offset] = pixel[2];
        }
    }
    return chw;
}

LegacySegDecodeResult DecodeLegacySegmentation(
    const std::vector<Ort::Value>& outputs,
    float conf_threshold) {
    if (outputs.size() < 2) {
        throw std::runtime_error(
            "The legacy segmenter requires 2 ONNX outputs: detections and protos.");
    }

    const auto det_shape = outputs[0].GetTensorTypeAndShapeInfo().GetShape();
    const auto proto_shape = outputs[1].GetTensorTypeAndShapeInfo().GetShape();
    if (det_shape.size() < 2 || proto_shape.size() != 4) {
        throw std::runtime_error(
            "The segmenter signature does not match the expected legacy contract.");
    }

    int64_t num_rows = 0;
    int64_t row_size = 0;
    if (det_shape.size() == 3) {
        num_rows = det_shape[1];
        row_size = det_shape[2];
    } else {
        num_rows = det_shape[0];
        row_size = det_shape[1];
    }
    if (row_size < 7) {
        throw std::runtime_error("The segmenter detection output is too short.");
    }

    const float* det_ptr = outputs[0].GetTensorData<float>();
    const float* proto_ptr = outputs[1].GetTensorData<float>();

    const int proto_c = static_cast<int>(proto_shape[1]);
    const int proto_h = static_cast<int>(proto_shape[2]);
    const int proto_w = static_cast<int>(proto_shape[3]);
    if (row_size < 6 + proto_c) {
        throw std::runtime_error(
            "The detection output does not bring enough mask coefficients.");
    }

    LegacySegDecodeResult decoded;
    decoded.proto_c = proto_c;
    decoded.proto_h = proto_h;
    decoded.proto_w = proto_w;

    std::vector<float> coeffs;
    coeffs.reserve(static_cast<size_t>(num_rows * proto_c));

    for (int64_t i = 0; i < num_rows; ++i) {
        const float* row = det_ptr + static_cast<size_t>(i * row_size);
        const float score = row[4];
        if (score < conf_threshold) {
            continue;
        }

        decoded.boxes_xyxy_lb.push_back({row[0], row[1], row[2], row[3]});
        decoded.scores.push_back(score);
        decoded.class_ids.push_back(static_cast<int>(row[5]));
        for (int c = 0; c < proto_c; ++c) {
            coeffs.push_back(row[6 + c]);
        }
    }

    if (!coeffs.empty()) {
        decoded.mask_coeffs = cv::Mat(
            static_cast<int>(decoded.boxes_xyxy_lb.size()),
            proto_c,
            CV_32F,
            coeffs.data()).clone();
    }
    decoded.protos = cv::Mat(
        proto_c,
        proto_h * proto_w,
        CV_32F,
        const_cast<float*>(proto_ptr)).clone();
    return decoded;
}

std::vector<cv::Mat> ReconstructLegacyMasks(
    const LegacySegDecodeResult& decoded,
    int img_size,
    float mask_threshold) {
    std::vector<cv::Mat> masks;
    if (decoded.mask_coeffs.empty() || decoded.protos.empty()) {
        return masks;
    }

    cv::Mat mask_logits = decoded.mask_coeffs * decoded.protos;
    for (int row_idx = 0; row_idx < mask_logits.rows; ++row_idx) {
        cv::Mat mask = mask_logits.row(row_idx).reshape(1, decoded.proto_h).clone();
        for (int y = 0; y < mask.rows; ++y) {
            float* ptr = mask.ptr<float>(y);
            for (int x = 0; x < mask.cols; ++x) {
                ptr[x] = Sigmoid(ptr[x]);
            }
        }

        cv::Mat mask_up;
        cv::resize(mask, mask_up, cv::Size(img_size, img_size), 0.0, 0.0, cv::INTER_LINEAR);

        cv::Mat mask_binary;
        cv::threshold(mask_up, mask_binary, mask_threshold, 255.0, cv::THRESH_BINARY);
        mask_binary.convertTo(mask_binary, CV_8U);

        const std::array<float, 4>& box = decoded.boxes_xyxy_lb[static_cast<size_t>(row_idx)];
        const int x1 = std::clamp(static_cast<int>(std::round(box[0])), 0, mask_binary.cols);
        const int y1 = std::clamp(static_cast<int>(std::round(box[1])), 0, mask_binary.rows);
        const int x2 = std::clamp(static_cast<int>(std::round(box[2])), 0, mask_binary.cols);
        const int y2 = std::clamp(static_cast<int>(std::round(box[3])), 0, mask_binary.rows);

        cv::Mat cropped = cv::Mat::zeros(mask_binary.size(), CV_8U);
        if (x2 > x1 && y2 > y1) {
            const cv::Rect roi(x1, y1, x2 - x1, y2 - y1);
            mask_binary(roi).copyTo(cropped(roi));
        }
        masks.push_back(cropped);
    }

    return masks;
}

DetectionSummary BuildDetectionSummary(
    int class_id,
    float score,
    const std::array<float, 4>& box_xyxy_lb,
    float ratio,
    float dw,
    float dh,
    const cv::Size& original_size) {
    const float x1 = std::clamp(
        (box_xyxy_lb[0] - dw) / ratio, 0.0f, static_cast<float>(original_size.width));
    const float y1 = std::clamp(
        (box_xyxy_lb[1] - dh) / ratio, 0.0f, static_cast<float>(original_size.height));
    const float x2 = std::clamp(
        (box_xyxy_lb[2] - dw) / ratio, 0.0f, static_cast<float>(original_size.width));
    const float y2 = std::clamp(
        (box_xyxy_lb[3] - dh) / ratio, 0.0f, static_cast<float>(original_size.height));

    DetectionSummary summary;
    summary.class_id = class_id;
    auto it = kLegacySegmentationClassNames.find(class_id);
    summary.class_name = it != kLegacySegmentationClassNames.end() ? it->second : std::string("unknown");
    summary.score = score;
    summary.x = x1;
    summary.y = y1;
    summary.w = std::max(0.0f, x2 - x1);
    summary.h = std::max(0.0f, y2 - y1);
    return summary;
}

bool IsGrapeClass(const std::string& class_name) {
    return class_name.find("grape") != std::string::npos;
}

bool IsPingPongClass(const std::string& class_name) {
    return class_name.find("pingpong") != std::string::npos;
}

bool IsBunchClass(const std::string& class_name) {
    return class_name.find("bunch") != std::string::npos;
}

}  // namespace

LetterboxResult Letterbox(
    const cv::Mat& image,
    int new_width,
    int new_height,
    const cv::Scalar& color) {
    const float ratio = std::min(
        static_cast<float>(new_width) / static_cast<float>(image.cols),
        static_cast<float>(new_height) / static_cast<float>(image.rows));
    const int resized_width = static_cast<int>(std::round(image.cols * ratio));
    const int resized_height = static_cast<int>(std::round(image.rows * ratio));

    cv::Mat resized;
    if (image.cols != resized_width || image.rows != resized_height) {
        cv::resize(
            image,
            resized,
            cv::Size(resized_width, resized_height),
            0.0,
            0.0,
            cv::INTER_LINEAR);
    } else {
        resized = image.clone();
    }

    const float dw = (static_cast<float>(new_width) - resized_width) / 2.0f;
    const float dh = (static_cast<float>(new_height) - resized_height) / 2.0f;

    cv::Mat output;
    cv::copyMakeBorder(
        resized,
        output,
        static_cast<int>(std::round(dh - 0.1f)),
        static_cast<int>(std::round(dh + 0.1f)),
        static_cast<int>(std::round(dw - 0.1f)),
        static_cast<int>(std::round(dw + 0.1f)),
        cv::BORDER_CONSTANT,
        color);

    return {output, ratio, dw, dh};
}

cv::Mat ScaleMaskBackToOriginal(
    const cv::Mat& mask_letterboxed,
    float ratio,
    float dw,
    float dh,
    const cv::Size& original_size) {
    const int x1 = std::clamp(static_cast<int>(std::round(dw)), 0, mask_letterboxed.cols);
    const int y1 = std::clamp(static_cast<int>(std::round(dh)), 0, mask_letterboxed.rows);
    const int x2 = std::clamp(
        mask_letterboxed.cols - static_cast<int>(std::round(dw)),
        0,
        mask_letterboxed.cols);
    const int y2 = std::clamp(
        mask_letterboxed.rows - static_cast<int>(std::round(dh)),
        0,
        mask_letterboxed.rows);
    if (x2 <= x1 || y2 <= y1 || ratio <= 0.0f) {
        return cv::Mat::zeros(original_size, CV_8U);
    }

    const cv::Rect roi(x1, y1, x2 - x1, y2 - y1);
    const cv::Mat cropped = mask_letterboxed(roi);
    if (cropped.empty()) {
        return cv::Mat::zeros(original_size, CV_8U);
    }

    cv::Mat restored;
    cv::resize(cropped, restored, original_size, 0.0, 0.0, cv::INTER_NEAREST);
    return restored;
}

cv::Mat ComputeInstanceWiseDistanceTransform(
    const std::vector<cv::Mat>& instance_masks_letterboxed,
    const cv::Size& out_size) {
    cv::Mat merged = cv::Mat::zeros(out_size, CV_32F);
    for (const cv::Mat& mask_input : instance_masks_letterboxed) {
        if (mask_input.empty()) {
            continue;
        }

        cv::Mat mask_u8;
        if (mask_input.size() != out_size) {
            cv::resize(mask_input, mask_u8, out_size, 0.0, 0.0, cv::INTER_NEAREST);
        } else {
            mask_u8 = mask_input;
        }

        cv::Mat binary;
        cv::threshold(mask_u8, binary, 0.0, 1.0, cv::THRESH_BINARY);
        binary.convertTo(binary, CV_8U);
        if (cv::countNonZero(binary) == 0) {
            continue;
        }

        cv::Mat distance;
        cv::distanceTransform(binary, distance, cv::DIST_L2, 5);
        double max_value = 0.0;
        cv::minMaxLoc(distance, nullptr, &max_value);
        if (max_value > 0.0) {
            distance /= static_cast<float>(max_value);
        }

        cv::max(merged, distance, merged);
    }
    return merged;
}

SegmentationOutput RunSegmentationPipeline(
    Ort::Session& segmentation_session,
    const std::string& image_path,
    int img_size,
    float conf_threshold,
    float mask_threshold) {
    cv::Mat orig_bgr = cv::imread(image_path, cv::IMREAD_COLOR);
    if (orig_bgr.empty()) {
        throw std::runtime_error("Could not read the image: " + image_path);
    }

    const LetterboxResult lb = Letterbox(orig_bgr, img_size, img_size);
    cv::Mat rgb_lb;
    cv::cvtColor(lb.image, rgb_lb, cv::COLOR_BGR2RGB);

    std::vector<float> x_chw = ImageToRgbFloatChw(rgb_lb);
    std::vector<int64_t> x_shape = {1, 3, img_size, img_size};
    Ort::MemoryInfo memory_info = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    Ort::Value input_tensor = Ort::Value::CreateTensor<float>(
        memory_info,
        x_chw.data(),
        x_chw.size(),
        x_shape.data(),
        x_shape.size());

    const std::vector<std::string> seg_input_names = GetSessionInputNames(segmentation_session);
    if (seg_input_names.empty()) {
        throw std::runtime_error("The segmenter does not expose ONNX inputs.");
    }
    const std::vector<std::string> output_names = GetSessionOutputNames(segmentation_session);
    if (output_names.empty()) {
        throw std::runtime_error("The segmenter does not expose ONNX outputs.");
    }

    const std::vector<std::string> input_names = {seg_input_names.front()};
    const std::vector<const char*> input_names_raw = ToCStringVector(input_names);
    const std::vector<const char*> output_names_raw = ToCStringVector(output_names);

    auto outputs = segmentation_session.Run(
        Ort::RunOptions {nullptr},
        input_names_raw.data(),
        &input_tensor,
        1,
        output_names_raw.data(),
        output_names_raw.size());

    const LegacySegDecodeResult decoded = DecodeLegacySegmentation(outputs, conf_threshold);
    const std::vector<cv::Mat> masks_lb = ReconstructLegacyMasks(decoded, img_size, mask_threshold);

    SegmentationOutput segmentation;
    segmentation.orig_bgr = orig_bgr.clone();
    cv::cvtColor(orig_bgr, segmentation.orig_rgb, cv::COLOR_BGR2RGB);
    segmentation.rgb_lb = rgb_lb;
    segmentation.bunch_global_orig = cv::Mat::zeros(orig_bgr.size(), CV_8U);
    segmentation.grapes_global_orig = cv::Mat::zeros(orig_bgr.size(), CV_8U);
    segmentation.pingpong_global_orig = cv::Mat::zeros(orig_bgr.size(), CV_8U);
    segmentation.bunch_global_lb = cv::Mat::zeros(cv::Size(img_size, img_size), CV_8U);
    segmentation.grapes_global_lb = cv::Mat::zeros(cv::Size(img_size, img_size), CV_8U);
    segmentation.pingpong_global_lb = cv::Mat::zeros(cv::Size(img_size, img_size), CV_8U);

    for (size_t i = 0; i < decoded.class_ids.size() && i < masks_lb.size(); ++i) {
        const int class_id = decoded.class_ids[i];
        const auto class_name_it = kLegacySegmentationClassNames.find(class_id);
        const std::string class_name = class_name_it != kLegacySegmentationClassNames.end()
            ? class_name_it->second
            : std::string("unknown");

        const cv::Mat mask_orig = ScaleMaskBackToOriginal(
            masks_lb[i], lb.ratio, lb.dw, lb.dh, orig_bgr.size());
        const DetectionSummary summary = BuildDetectionSummary(
            class_id,
            decoded.scores[i],
            decoded.boxes_xyxy_lb[i],
            lb.ratio,
            lb.dw,
            lb.dh,
            orig_bgr.size());
        segmentation.detections.push_back(summary);

        if (IsGrapeClass(class_name)) {
            cv::max(segmentation.grapes_global_orig, mask_orig, segmentation.grapes_global_orig);
            cv::max(segmentation.grapes_global_lb, masks_lb[i], segmentation.grapes_global_lb);
            segmentation.grape_instance_masks_lb.push_back(masks_lb[i]);
            ++segmentation.num_grape_det;
        } else if (IsPingPongClass(class_name)) {
            cv::max(segmentation.pingpong_global_orig, mask_orig, segmentation.pingpong_global_orig);
            cv::max(segmentation.pingpong_global_lb, masks_lb[i], segmentation.pingpong_global_lb);
            segmentation.pingpong_instance_masks_lb.push_back(masks_lb[i]);
            ++segmentation.num_pingpong_det;
        } else if (IsBunchClass(class_name)) {
            cv::max(segmentation.bunch_global_orig, mask_orig, segmentation.bunch_global_orig);
            cv::max(segmentation.bunch_global_lb, masks_lb[i], segmentation.bunch_global_lb);
            ++segmentation.num_bunch_det;
        }
    }

    segmentation.grapes_dt_instance_lb = ComputeInstanceWiseDistanceTransform(
        segmentation.grape_instance_masks_lb,
        cv::Size(img_size, img_size));
    segmentation.pingpong_dt_instance_lb = ComputeInstanceWiseDistanceTransform(
        segmentation.pingpong_instance_masks_lb,
        cv::Size(img_size, img_size));

    return segmentation;
}

PipelineInputs BuildPipelineInputs(
    const std::string& image_path,
    const std::string& variety_name,
    int64_t variety_index,
    const RuntimeModelContract& contract,
    Ort::Session* segmentation_session,
    bool allow_synthetic_rgbdt) {
    if (contract.input_channels != kOfficialInputChannels) {
        throw std::runtime_error(
            "The official runtime only supports 5-channel RGBDT. Channels detected=" +
            std::to_string(contract.input_channels));
    }

    PipelineInputs inputs;
    inputs.variety_name = NormalizeVariety(variety_name);
    inputs.variety_idx = {variety_index};
    inputs.input_channels = contract.input_channels;

    if (segmentation_session != nullptr) {
        inputs.seg = RunSegmentationPipeline(
            *segmentation_session,
            image_path,
            contract.img_size,
            kDefaultSegConfThreshold,
            kDefaultSegMaskThreshold);
        inputs.rgb_lb = inputs.seg.rgb_lb;
        inputs.grapes_dt_instance_lb = inputs.seg.grapes_dt_instance_lb;
        inputs.pingpong_dt_instance_lb = inputs.seg.pingpong_dt_instance_lb;
        inputs.seg_count_base = {static_cast<float>(inputs.seg.num_grape_det)};
    } else {
        if (!allow_synthetic_rgbdt) {
            throw std::runtime_error(
                "The model requires segmentation to build seg_count_base"
                " and, in RGBDT mode, also the DT maps. Enable allowSyntheticRgbdt"
                " only if you accept a degraded fallback.");
        }

        cv::Mat orig_bgr = cv::imread(image_path, cv::IMREAD_COLOR);
        if (orig_bgr.empty()) {
            throw std::runtime_error("Could not read the image: " + image_path);
        }
        const LetterboxResult lb = Letterbox(orig_bgr, contract.img_size, contract.img_size);
        cv::cvtColor(lb.image, inputs.rgb_lb, cv::COLOR_BGR2RGB);
        inputs.grapes_dt_instance_lb =
            cv::Mat::zeros(cv::Size(contract.img_size, contract.img_size), CV_32F);
        inputs.pingpong_dt_instance_lb =
            cv::Mat::zeros(cv::Size(contract.img_size, contract.img_size), CV_32F);
        inputs.seg.orig_bgr = orig_bgr.clone();
        cv::cvtColor(orig_bgr, inputs.seg.orig_rgb, cv::COLOR_BGR2RGB);
        inputs.seg.rgb_lb = inputs.rgb_lb;
        inputs.seg.bunch_global_orig = cv::Mat::zeros(orig_bgr.size(), CV_8U);
        inputs.seg.grapes_global_orig = cv::Mat::zeros(orig_bgr.size(), CV_8U);
        inputs.seg.pingpong_global_orig = cv::Mat::zeros(orig_bgr.size(), CV_8U);
        inputs.seg.bunch_global_lb =
            cv::Mat::zeros(cv::Size(contract.img_size, contract.img_size), CV_8U);
        inputs.seg.grapes_global_lb =
            cv::Mat::zeros(cv::Size(contract.img_size, contract.img_size), CV_8U);
        inputs.seg.pingpong_global_lb =
            cv::Mat::zeros(cv::Size(contract.img_size, contract.img_size), CV_8U);
        inputs.seg.grapes_dt_instance_lb = inputs.grapes_dt_instance_lb.clone();
        inputs.seg.pingpong_dt_instance_lb = inputs.pingpong_dt_instance_lb.clone();
        inputs.seg.used_synthetic_fallback = true;
        inputs.seg_count_base = {0.0f};
        inputs.used_synthetic_fallback = true;
    }

    std::vector<float> rgb_chw = ImageToRgbFloatChw(inputs.rgb_lb);
    const size_t plane = static_cast<size_t>(contract.img_size * contract.img_size);
    inputs.x.assign(static_cast<size_t>(5 * plane), 0.0f);
    std::copy(rgb_chw.begin(), rgb_chw.end(), inputs.x.begin());

    cv::Mat grapes_dt_f32;
    cv::Mat pingpong_dt_f32;
    if (inputs.grapes_dt_instance_lb.type() == CV_32F) {
        grapes_dt_f32 = inputs.grapes_dt_instance_lb;
    } else {
        inputs.grapes_dt_instance_lb.convertTo(grapes_dt_f32, CV_32F, 1.0 / 255.0);
    }
    if (inputs.pingpong_dt_instance_lb.type() == CV_32F) {
        pingpong_dt_f32 = inputs.pingpong_dt_instance_lb;
    } else {
        inputs.pingpong_dt_instance_lb.convertTo(pingpong_dt_f32, CV_32F, 1.0 / 255.0);
    }

    for (int y = 0; y < contract.img_size; ++y) {
        for (int x = 0; x < contract.img_size; ++x) {
            const size_t offset = static_cast<size_t>(y * contract.img_size + x);
            inputs.x[(3 * plane) + offset] = grapes_dt_f32.at<float>(y, x);
            inputs.x[(4 * plane) + offset] = pingpong_dt_f32.at<float>(y, x);
        }
    }

    return inputs;
}

}  // namespace grape
