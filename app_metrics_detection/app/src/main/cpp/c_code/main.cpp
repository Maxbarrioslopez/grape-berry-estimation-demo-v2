#include <onnxruntime_cxx_api.h>
#include <opencv2/opencv.hpp>

#include <algorithm>
#include <array>
#include <cctype>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <cstdint>
#include <filesystem>
#include <iostream>
#include <map>
#include <numeric>
#include <regex>
#include <stdexcept>
#include <string>
#include <vector>

namespace fs = std::filesystem;

#if defined(__has_include)
#if __has_include(<onnxruntime/core/providers/nnapi/nnapi_provider_factory.h>)
#include <onnxruntime/core/providers/nnapi/nnapi_provider_factory.h>
#define ORT_HAS_NNAPI_PROVIDER 1
#endif
#endif

static std::string get_env_or_default(const char* var_name, const std::string& fallback) {
    const char* v = std::getenv(var_name);
    if (v == nullptr || *v == '\0') {
        return fallback;
    }
    return std::string(v);
}

// ============================================================
// CONFIG
// ============================================================
static const std::string DEFAULT_SEG_ONNX_PATH = "../weights/seg_best.onnx";
static const std::string DEFAULT_REG_ONNX_PATH = "../weights/best_model_5ch_residual.onnx";

static constexpr int IMGSZ = 512;
static constexpr float CONF_THRES = 0.25f;
static constexpr float MASK_THRES = 0.25f;

static constexpr int CAL_MIN = 7;
static constexpr int CAL_MAX = 32;

static const std::vector<int> BINS = []() {
    std::vector<int> v;
    for (int k = CAL_MIN; k <= CAL_MAX; ++k) v.push_back(k);
    return v;
}();

static const std::map<int, std::string> CLASS_NAMES = {
    {0, "bunch_black"},
    {1, "bunch_green"},
    {2, "bunch_red"},
    {3, "grape_black"},
    {4, "grape_green"},
    {5, "grape_red"},
    {6, "pingpong"},
};

// Mantener este orden alineado con los IDs de variedad que recibe el modelo.
static const std::vector<std::string> VARIETY_CLASSES = {
    "ALLISON",
    "AUTUMN CRISP",
    "CRIMSON",
    "IVORY",
    "MAGENTA",
    "RED GLOBE",
    "SCARLOTTA",
    "SUPERIOR",
    "SWEET GLOBE",
    "THOMPSON",
    "TIMCO",
    "TIMPSON",
};

static std::map<std::string, int64_t> buildVarietyMap() {
    std::map<std::string, int64_t> m;
    for (size_t i = 0; i < VARIETY_CLASSES.size(); ++i) {
        m[VARIETY_CLASSES[i]] = static_cast<int64_t>(i);
    }
    return m;
}

static const std::map<std::string, int64_t> VARIETY_TO_IDX = buildVarietyMap();

static const std::string VARIETY_OVERRIDE = "";  // ej. "MAGENTA"
static const bool SAVE_DEBUG_IMAGES = false;


// ============================================================
// STRUCTS
// ============================================================
struct LetterboxResult {
    cv::Mat image;
    float ratio;
    float dw;
    float dh;
};

struct SegDecodeResult {
    std::vector<cv::Rect2f> boxes_lb;
    std::vector<float> scores;
    std::vector<int> cls_ids;
    cv::Mat mask_coeffs;   // [N, C] float32
    cv::Mat protos;        // [C, MH*MW] float32 flattened
    int proto_c = 0;
    int proto_h = 0;
    int proto_w = 0;
};

struct SegOutput {
    cv::Mat orig_bgr;
    cv::Mat orig_rgb;
    std::vector<cv::Rect2f> boxes;
    std::vector<float> scores;
    std::vector<int> cls_ids;
    std::vector<cv::Mat> masks;   // uint8 0/1
};

struct GlobalMasks {
    cv::Mat bunch;      // uint8 0/255
    cv::Mat grapes;     // uint8 0/255
    cv::Mat pingpong;   // uint8 0/255
    int num_bunch_det = 0;
    int num_grape_det = 0;
    int num_pingpong_det = 0;
};

struct RegressorBuildOutput {
    std::vector<float> x;      // [1,5,H,W]
    std::array<int64_t, 1> variety_idx{};
    std::array<float, 1> visible_count{};

    cv::Mat rgb_lb;
    cv::Mat grapes_lb;
    cv::Mat grapes_dt;

    SegOutput seg_out;
    GlobalMasks global_masks;
    std::string variety;
};

struct RegOutput {
    float residual_count = 0.0f;
    float count_total = 0.0f;
    std::vector<float> hist_logits;
    std::vector<float> hist_prob;
    std::vector<float> hist_counts;
    float mean = 0.0f;
};

struct PipelineResult {
    RegressorBuildOutput pipe;
    RegOutput reg_out;
    std::vector<int> hist_counts_int;
};

enum class ProviderPreference {
    AndroidAuto,
    Nnapi,
    Cpu,
};


// ============================================================
// UTILS
// ============================================================
static inline float sigmoid_scalar(float x) {
    return 1.0f / (1.0f + std::exp(-x));
}

static std::string to_upper_ascii(std::string s) {
    for (char& c : s) {
        c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
    }
    return s;
}

static std::string trim_spaces(const std::string& s) {
    std::string out = std::regex_replace(s, std::regex("\\s+"), " ");
    if (!out.empty() && out.front() == ' ') out.erase(out.begin());
    if (!out.empty() && out.back() == ' ') out.pop_back();
    return out;
}

static std::string normalize_variety(const std::string& x) {
    if (x.empty()) return "";

    std::string s = trim_spaces(to_upper_ascii(x));

    static const std::map<std::string, std::string> alias_map = {
        {"AUTUM CRISP", "AUTUMN CRISP"},
        {"AUTUMN CRISP", "AUTUMN CRISP"},
        {"RED GLOVE", "RED GLOBE"},
        {"TINCO", "TIMCO"},
    };

    auto it = alias_map.find(s);
    if (it != alias_map.end()) return it->second;
    return s;
}

static std::string to_lower_ascii(std::string s) {
    for (char& c : s) {
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    return s;
}

static ProviderPreference parse_provider_preference(const std::string& provider_raw) {
    std::string p = to_lower_ascii(trim_spaces(provider_raw));
    if (p.empty() || p == "auto" || p == "android_auto") {
        return ProviderPreference::AndroidAuto;
    }
    if (p == "nnapi") {
        return ProviderPreference::Nnapi;
    }
    if (p == "cpu") {
        return ProviderPreference::Cpu;
    }
    throw std::runtime_error("Provider invalido: " + provider_raw + ". Usa: auto, nnapi o cpu.");
}

static std::string infer_variety_from_filename(const std::string& image_path) {
    std::string name = to_upper_ascii(fs::path(image_path).filename().string());
    for (const auto& v : VARIETY_CLASSES) {
        if (name.find(v) != std::string::npos) {
            return v;
        }
    }
    throw std::runtime_error(
        "No pude inferir variety desde el nombre del archivo: " + name +
        ". Usa VARIETY_OVERRIDE."
    );
}

static std::vector<std::string> get_input_names(Ort::Session& session, Ort::AllocatorWithDefaultOptions& allocator) {
    std::vector<std::string> names;
    size_t n = session.GetInputCount();
    names.reserve(n);
    for (size_t i = 0; i < n; ++i) {
        auto p = session.GetInputNameAllocated(i, allocator);
        names.emplace_back(p.get());
    }
    return names;
}

static std::vector<std::string> get_output_names(Ort::Session& session, Ort::AllocatorWithDefaultOptions& allocator) {
    std::vector<std::string> names;
    size_t n = session.GetOutputCount();
    names.reserve(n);
    for (size_t i = 0; i < n; ++i) {
        auto p = session.GetOutputNameAllocated(i, allocator);
        names.emplace_back(p.get());
    }
    return names;
}

static std::string configure_provider(Ort::SessionOptions& opts, ProviderPreference preference) {
    switch (preference) {
        case ProviderPreference::Cpu:
            return "CPUExecutionProvider";
        case ProviderPreference::Nnapi:
#if defined(ORT_HAS_NNAPI_PROVIDER)
            OrtSessionOptionsAppendExecutionProvider_Nnapi(opts, 0);
            return "NNAPIExecutionProvider";
#else
            throw std::runtime_error("NNAPI no disponible en este build de ONNX Runtime.");
#endif
        case ProviderPreference::AndroidAuto:
        default:
#if defined(ORT_HAS_NNAPI_PROVIDER)
            OrtSessionOptionsAppendExecutionProvider_Nnapi(opts, 0);
            return "NNAPIExecutionProvider (auto)";
#else
            return "CPUExecutionProvider (auto)";
#endif
    }
}

static Ort::Session create_session(Ort::Env& env, const std::string& onnx_path, ProviderPreference provider_preference, std::string& provider_used) {
    if (!fs::exists(onnx_path)) {
        throw std::runtime_error("No existe el modelo ONNX: " + onnx_path);
    }

    Ort::SessionOptions opts;
    opts.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_EXTENDED);
    opts.SetIntraOpNumThreads(1);

    provider_used = configure_provider(opts, provider_preference);
    return Ort::Session(env, onnx_path.c_str(), opts);
}


// ============================================================
// LETTERBOX + PREPROCESS
// ============================================================
static LetterboxResult letterbox(const cv::Mat& image, int new_w = 512, int new_h = 512, const cv::Scalar& color = cv::Scalar(114, 114, 114)) {
    int h = image.rows;
    int w = image.cols;

    float r = std::min(static_cast<float>(new_h) / h, static_cast<float>(new_w) / w);

    int new_unpad_w = static_cast<int>(std::round(w * r));
    int new_unpad_h = static_cast<int>(std::round(h * r));

    float dw = static_cast<float>(new_w - new_unpad_w) / 2.0f;
    float dh = static_cast<float>(new_h - new_unpad_h) / 2.0f;

    cv::Mat resized;
    if (w != new_unpad_w || h != new_unpad_h) {
        cv::resize(image, resized, cv::Size(new_unpad_w, new_unpad_h), 0, 0, cv::INTER_LINEAR);
    } else {
        resized = image.clone();
    }

    int top = static_cast<int>(std::round(dh - 0.1f));
    int bottom = static_cast<int>(std::round(dh + 0.1f));
    int left = static_cast<int>(std::round(dw - 0.1f));
    int right = static_cast<int>(std::round(dw + 0.1f));

    cv::Mat out;
    cv::copyMakeBorder(resized, out, top, bottom, left, right, cv::BORDER_CONSTANT, color);

    return {out, r, dw, dh};
}

static std::vector<float> preprocess_yolo(const cv::Mat& image_bgr, float& ratio, float& dw, float& dh, cv::Mat& img_lb_out) {
    auto lb = letterbox(image_bgr, IMGSZ, IMGSZ);
    ratio = lb.ratio;
    dw = lb.dw;
    dh = lb.dh;
    img_lb_out = lb.image;

    cv::Mat img_rgb;
    cv::cvtColor(img_lb_out, img_rgb, cv::COLOR_BGR2RGB);
    img_rgb.convertTo(img_rgb, CV_32F, 1.0 / 255.0);

    std::vector<float> x(1 * 3 * IMGSZ * IMGSZ);
    size_t plane = IMGSZ * IMGSZ;

    for (int y = 0; y < IMGSZ; ++y) {
        const cv::Vec3f* row = img_rgb.ptr<cv::Vec3f>(y);
        for (int xidx = 0; xidx < IMGSZ; ++xidx) {
            const auto& px = row[xidx];
            x[0 * plane + y * IMGSZ + xidx] = px[0];
            x[1 * plane + y * IMGSZ + xidx] = px[1];
            x[2 * plane + y * IMGSZ + xidx] = px[2];
        }
    }
    return x;
}

static std::vector<cv::Rect2f> scale_boxes_back_xyxy(
    const std::vector<cv::Rect2f>& boxes,
    float ratio,
    float dw,
    float dh,
    const cv::Size& orig_size
) {
    std::vector<cv::Rect2f> out;
    out.reserve(boxes.size());

    for (const auto& b : boxes) {
        float x1 = (b.x - dw) / ratio;
        float y1 = (b.y - dh) / ratio;
        float x2 = (b.width - dw) / ratio;
        float y2 = (b.height - dh) / ratio;

        x1 = std::clamp(x1, 0.0f, static_cast<float>(orig_size.width - 1));
        x2 = std::clamp(x2, 0.0f, static_cast<float>(orig_size.width - 1));
        y1 = std::clamp(y1, 0.0f, static_cast<float>(orig_size.height - 1));
        y2 = std::clamp(y2, 0.0f, static_cast<float>(orig_size.height - 1));

        out.emplace_back(cv::Rect2f(x1, y1, x2, y2));
    }
    return out;
}

static cv::Mat crop_mask(const cv::Mat& mask, const cv::Rect2f& box_xyxy) {
    int x1 = std::max(0, static_cast<int>(box_xyxy.x));
    int y1 = std::max(0, static_cast<int>(box_xyxy.y));
    int x2 = std::min(mask.cols, static_cast<int>(box_xyxy.width));
    int y2 = std::min(mask.rows, static_cast<int>(box_xyxy.height));

    cv::Mat out = cv::Mat::zeros(mask.size(), CV_8U);
    if (x2 > x1 && y2 > y1) {
        mask(cv::Rect(x1, y1, x2 - x1, y2 - y1)).copyTo(out(cv::Rect(x1, y1, x2 - x1, y2 - y1)));
    }
    return out;
}


// ============================================================
// ONNX HELPERS
// ============================================================
static Ort::Value make_tensor_float(std::vector<float>& data, const std::vector<int64_t>& shape, Ort::MemoryInfo& mem_info) {
    return Ort::Value::CreateTensor<float>(
        mem_info,
        data.data(),
        data.size(),
        shape.data(),
        shape.size()
    );
}

static Ort::Value make_tensor_int64(int64_t* data, size_t count, const std::vector<int64_t>& shape, Ort::MemoryInfo& mem_info) {
    return Ort::Value::CreateTensor<int64_t>(
        mem_info,
        data,
        count,
        shape.data(),
        shape.size()
    );
}


// ============================================================
// SEGMENTATION POSTPROCESS
// ============================================================
// Asume export YOLO-seg post-NMS con:
// outputs[0]: [1, N, 6+Cmask] -> x1,y1,x2,y2,score,cls,coeffs...
// outputs[1]: [1, C, MH, MW]
//
// Si tu ONNX exporta en otro formato, solo debes ajustar ESTA función.
static SegDecodeResult decode_segmentation_postnms(
    const std::vector<Ort::Value>& outputs,
    float conf_thres
) {
    if (outputs.size() < 2) {
        throw std::runtime_error("El modelo de segmentación debe devolver al menos 2 outputs.");
    }

    auto det_info = outputs[0].GetTensorTypeAndShapeInfo();
    auto det_shape = det_info.GetShape();
    const float* det_ptr = outputs[0].GetTensorData<float>();

    if (det_shape.size() != 3 || det_shape[0] != 1 || det_shape[2] < 7) {
        throw std::runtime_error("Output det inesperado. Espero [1, N, 6+Cmask].");
    }

    int64_t N = det_shape[1];
    int64_t D = det_shape[2];

    auto proto_info = outputs[1].GetTensorTypeAndShapeInfo();
    auto proto_shape = proto_info.GetShape();
    const float* proto_ptr = outputs[1].GetTensorData<float>();

    if (proto_shape.size() != 4 || proto_shape[0] != 1) {
        throw std::runtime_error("Output protos inesperado. Espero [1, C, MH, MW].");
    }

    int proto_c = static_cast<int>(proto_shape[1]);
    int proto_h = static_cast<int>(proto_shape[2]);
    int proto_w = static_cast<int>(proto_shape[3]);

    std::vector<cv::Rect2f> boxes_lb;
    std::vector<float> scores;
    std::vector<int> cls_ids;
    std::vector<float> coeff_data;

    for (int64_t i = 0; i < N; ++i) {
        const float* row = det_ptr + i * D;
        float score = row[4];
        if (score < conf_thres) continue;

        float x1 = row[0];
        float y1 = row[1];
        float x2 = row[2];
        float y2 = row[3];
        int cls_id = static_cast<int>(row[5]);

        boxes_lb.emplace_back(cv::Rect2f(x1, y1, x2, y2));
        scores.push_back(score);
        cls_ids.push_back(cls_id);

        for (int c = 0; c < proto_c; ++c) {
            coeff_data.push_back(row[6 + c]);
        }
    }

    cv::Mat mask_coeffs;
    if (!coeff_data.empty()) {
        mask_coeffs = cv::Mat(static_cast<int>(boxes_lb.size()), proto_c, CV_32F, coeff_data.data()).clone();
    } else {
        mask_coeffs = cv::Mat(0, proto_c, CV_32F);
    }

    cv::Mat protos(proto_c, proto_h * proto_w, CV_32F);
    std::memcpy(protos.data, proto_ptr, sizeof(float) * proto_c * proto_h * proto_w);

    return {boxes_lb, scores, cls_ids, mask_coeffs, protos, proto_c, proto_h, proto_w};
}

static std::vector<cv::Mat> reconstruct_masks(
    const cv::Mat& mask_coeffs,
    const cv::Mat& protos_flat,
    int proto_c,
    int proto_h,
    int proto_w,
    const std::vector<cv::Rect2f>& boxes_lb,
    int img_size = 512,
    float mask_thres = 0.5f
) {
    std::vector<cv::Mat> out_masks;

    if (mask_coeffs.rows == 0) {
        return out_masks;
    }

    cv::Mat masks = mask_coeffs * protos_flat;  // [N, MH*MW], float32

    for (int i = 0; i < masks.rows; ++i) {
        cv::Mat m = masks.row(i).reshape(1, proto_h).clone();  // [MH, MW]

        for (int r = 0; r < m.rows; ++r) {
            float* p = m.ptr<float>(r);
            for (int c = 0; c < m.cols; ++c) {
                p[c] = sigmoid_scalar(p[c]);
            }
        }

        cv::Mat m_up;
        cv::resize(m, m_up, cv::Size(img_size, img_size), 0, 0, cv::INTER_LINEAR);

        cv::Mat m_bin;
        cv::threshold(m_up, m_bin, mask_thres, 1.0, cv::THRESH_BINARY);
        m_bin.convertTo(m_bin, CV_8U);

        cv::Mat m_crop = crop_mask(m_bin, boxes_lb[i]);
        out_masks.push_back(m_crop);
    }

    return out_masks;
}

static cv::Mat scale_mask_back_to_original(
    const cv::Mat& mask_lb,
    float ratio,
    float dw,
    float dh,
    const cv::Size& orig_size
) {
    int x1 = static_cast<int>(std::round(dw));
    int y1 = static_cast<int>(std::round(dh));
    int x2 = static_cast<int>(mask_lb.cols - std::round(dw));
    int y2 = static_cast<int>(mask_lb.rows - std::round(dh));

    x1 = std::clamp(x1, 0, mask_lb.cols);
    x2 = std::clamp(x2, 0, mask_lb.cols);
    y1 = std::clamp(y1, 0, mask_lb.rows);
    y2 = std::clamp(y2, 0, mask_lb.rows);

    if (x2 <= x1 || y2 <= y1) {
        return cv::Mat::zeros(orig_size, CV_8U);
    }

    cv::Mat cropped = mask_lb(cv::Rect(x1, y1, x2 - x1, y2 - y1)).clone();
    cv::Mat mask_orig;
    cv::resize(cropped, mask_orig, orig_size, 0, 0, cv::INTER_NEAREST);
    return mask_orig;
}

static SegOutput run_segmentation_onnx(Ort::Session& session, const std::string& image_path) {
    cv::Mat image_bgr = cv::imread(image_path, cv::IMREAD_COLOR);
    if (image_bgr.empty()) {
        throw std::runtime_error("No se pudo leer imagen: " + image_path);
    }

    cv::Mat orig_rgb;
    cv::cvtColor(image_bgr, orig_rgb, cv::COLOR_BGR2RGB);

    float ratio, dw, dh;
    cv::Mat img_lb;
    std::vector<float> x = preprocess_yolo(image_bgr, ratio, dw, dh, img_lb);

    Ort::AllocatorWithDefaultOptions allocator;
    auto input_names_str = get_input_names(session, allocator);
    std::vector<const char*> input_names;
    for (auto& s : input_names_str) input_names.push_back(s.c_str());

    auto output_names_str = get_output_names(session, allocator);
    std::vector<const char*> output_names;
    for (auto& s : output_names_str) output_names.push_back(s.c_str());

    Ort::MemoryInfo mem_info = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    std::vector<int64_t> shape = {1, 3, IMGSZ, IMGSZ};
    Ort::Value input_tensor = make_tensor_float(x, shape, mem_info);

    std::vector<Ort::Value> ort_inputs;
    ort_inputs.emplace_back(std::move(input_tensor));

    auto outputs = session.Run(
        Ort::RunOptions{nullptr},
        input_names.data(),
        ort_inputs.data(),
        ort_inputs.size(),
        output_names.data(),
        output_names.size()
    );

    SegDecodeResult dec = decode_segmentation_postnms(outputs, CONF_THRES);

    std::vector<cv::Mat> masks_lb = reconstruct_masks(
        dec.mask_coeffs,
        dec.protos,
        dec.proto_c,
        dec.proto_h,
        dec.proto_w,
        dec.boxes_lb,
        IMGSZ,
        MASK_THRES
    );

    std::vector<cv::Rect2f> boxes_orig = scale_boxes_back_xyxy(dec.boxes_lb, ratio, dw, dh, image_bgr.size());

    std::vector<cv::Mat> masks_orig;
    masks_orig.reserve(masks_lb.size());
    for (const auto& m : masks_lb) {
        masks_orig.push_back(scale_mask_back_to_original(m, ratio, dw, dh, image_bgr.size()));
    }

    return {image_bgr, orig_rgb, boxes_orig, dec.scores, dec.cls_ids, masks_orig};
}

static GlobalMasks build_global_masks_from_seg_output(const SegOutput& seg_out) {
    int h = seg_out.orig_bgr.rows;
    int w = seg_out.orig_bgr.cols;

    cv::Mat bunch = cv::Mat::zeros(h, w, CV_8U);
    cv::Mat grapes = cv::Mat::zeros(h, w, CV_8U);
    cv::Mat pingpong = cv::Mat::zeros(h, w, CV_8U);

    int num_bunch = 0;
    int num_grapes = 0;
    int num_pingpong = 0;

    for (size_t i = 0; i < seg_out.cls_ids.size(); ++i) {
        int cls_id = seg_out.cls_ids[i];
        const cv::Mat& mask = seg_out.masks[i];

        auto it = CLASS_NAMES.find(cls_id);
        std::string cls_name = (it != CLASS_NAMES.end()) ? it->second : std::to_string(cls_id);
        std::string cls_name_l = cls_name;
        std::transform(cls_name_l.begin(), cls_name_l.end(), cls_name_l.begin(), ::tolower);

        cv::Mat mask_u8;
        mask.convertTo(mask_u8, CV_8U, 255.0);

        if (cls_name_l.find("bunch") != std::string::npos) {
            cv::max(bunch, mask_u8, bunch);
            num_bunch++;
        } else if (cls_name_l.find("grape") != std::string::npos) {
            cv::max(grapes, mask_u8, grapes);
            num_grapes++;
        } else if (cls_name_l.find("ping") != std::string::npos) {
            cv::max(pingpong, mask_u8, pingpong);
            num_pingpong++;
        }
    }

    return {bunch, grapes, pingpong, num_bunch, num_grapes, num_pingpong};
}


// ============================================================
// REGRESSOR INPUT BUILD
// ============================================================
static void letterbox_image_and_masks(
    const cv::Mat& image_bgr,
    const std::map<std::string, cv::Mat>& masks_dict,
    cv::Mat& rgb_lb,
    std::map<std::string, cv::Mat>& masks_lb
) {
    int h0 = image_bgr.rows;
    int w0 = image_bgr.cols;

    auto lb = letterbox(image_bgr, IMGSZ, IMGSZ);
    cv::Mat image_lb_bgr = lb.image;
    float ratio = lb.ratio;
    float dw = lb.dw;
    float dh = lb.dh;

    cv::cvtColor(image_lb_bgr, rgb_lb, cv::COLOR_BGR2RGB);

    int new_unpad_w = static_cast<int>(std::round(w0 * ratio));
    int new_unpad_h = static_cast<int>(std::round(h0 * ratio));

    int top = static_cast<int>(std::round(dh - 0.1f));
    int bottom = static_cast<int>(std::round(dh + 0.1f));
    int left = static_cast<int>(std::round(dw - 0.1f));
    int right = static_cast<int>(std::round(dw + 0.1f));

    masks_lb.clear();

    for (const auto& kv : masks_dict) {
        cv::Mat m;
        cv::threshold(kv.second, m, 0, 255, cv::THRESH_BINARY);

        cv::Mat m_rs;
        cv::resize(m, m_rs, cv::Size(new_unpad_w, new_unpad_h), 0, 0, cv::INTER_NEAREST);

        cv::Mat m_lb;
        cv::copyMakeBorder(m_rs, m_lb, top, bottom, left, right, cv::BORDER_CONSTANT, cv::Scalar(0));
        masks_lb[kv.first] = m_lb;
    }
}

static cv::Mat compute_grapes_distance_transform(const cv::Mat& grapes_mask_u8) {
    cv::Mat mask_bin;
    cv::threshold(grapes_mask_u8, mask_bin, 0, 1, cv::THRESH_BINARY);

    if (cv::countNonZero(mask_bin) == 0) {
        return cv::Mat::zeros(grapes_mask_u8.size(), CV_8U);
    }

    cv::Mat dt;
    cv::distanceTransform(mask_bin, dt, cv::DIST_L2, 5);

    double dt_max = 0.0;
    cv::minMaxLoc(dt, nullptr, &dt_max);
    if (dt_max > 0.0) {
        dt /= static_cast<float>(dt_max);
    }

    cv::Mat dt_scaled;
    dt *= 255.0f;
    cv::min(dt, 255.0f, dt_scaled);

    cv::Mat out(dt_scaled.size(), CV_8U);
    for (int y = 0; y < dt_scaled.rows; ++y) {
        const float* src = dt_scaled.ptr<float>(y);
        uint8_t* dst = out.ptr<uint8_t>(y);
        for (int x = 0; x < dt_scaled.cols; ++x) {
            dst[x] = static_cast<uint8_t>(src[x]);
        }
    }
    return out;
}

static RegressorBuildOutput build_regressor_inputs_from_photo(
        Ort::Session& seg_session,
        const std::string& image_path,
        const std::string& variety
) {
    SegOutput seg_out = run_segmentation_onnx(seg_session, image_path);
    GlobalMasks global_masks = build_global_masks_from_seg_output(seg_out);

    // ------------------------------------------------------------
    // VALIDACIONES OBLIGATORIAS DE SEGMENTACION
    // ------------------------------------------------------------
    std::vector<std::string> missing;

    if (global_masks.num_bunch_det <= 0 || cv::countNonZero(global_masks.bunch) <= 0) {
        missing.push_back("bunch");
    }
    if (global_masks.num_grape_det <= 0 || cv::countNonZero(global_masks.grapes) <= 0) {
        missing.push_back("grapes");
    }
    if (global_masks.num_pingpong_det <= 0 || cv::countNonZero(global_masks.pingpong) <= 0) {
        missing.push_back("pingpong");
    }

    if (!missing.empty()) {
        std::string msg = "Segmentacion invalida. Faltan detecciones/mascaras requeridas: ";
        for (size_t i = 0; i < missing.size(); ++i) {
            if (i > 0) msg += ", ";
            msg += missing[i];
        }

        msg += " | counts -> bunch=" + std::to_string(global_masks.num_bunch_det);
        msg += ", grapes=" + std::to_string(global_masks.num_grape_det);
        msg += ", pingpong=" + std::to_string(global_masks.num_pingpong_det);

        throw std::runtime_error(msg);
    }

    cv::Mat rgb_lb;
    std::map<std::string, cv::Mat> masks_lb;
    letterbox_image_and_masks(
            seg_out.orig_bgr,
            {
                    {"bunch", global_masks.bunch},
                    {"grapes", global_masks.grapes},
                    {"pingpong", global_masks.pingpong}
            },
            rgb_lb,
            masks_lb
    );

    cv::Mat grapes_dt = compute_grapes_distance_transform(masks_lb["grapes"]);

    cv::Mat rgb_f, grapes_f, grapes_dt_f;
    rgb_lb.convertTo(rgb_f, CV_32F, 1.0 / 255.0);
    masks_lb["grapes"].convertTo(grapes_f, CV_32F, 1.0 / 255.0);
    grapes_dt.convertTo(grapes_dt_f, CV_32F, 1.0 / 255.0);

    std::vector<float> x(1 * 5 * IMGSZ * IMGSZ, 0.0f);
    size_t plane = IMGSZ * IMGSZ;

    for (int y = 0; y < IMGSZ; ++y) {
        const cv::Vec3f* row_rgb = rgb_f.ptr<cv::Vec3f>(y);
        const float* row_g = grapes_f.ptr<float>(y);
        const float* row_dt = grapes_dt_f.ptr<float>(y);

        for (int xx = 0; xx < IMGSZ; ++xx) {
            x[0 * plane + y * IMGSZ + xx] = row_rgb[xx][0];
            x[1 * plane + y * IMGSZ + xx] = row_rgb[xx][1];
            x[2 * plane + y * IMGSZ + xx] = row_rgb[xx][2];
            x[3 * plane + y * IMGSZ + xx] = row_g[xx];
            x[4 * plane + y * IMGSZ + xx] = row_dt[xx];
        }
    }

    std::string variety_norm = normalize_variety(variety);
    auto it = VARIETY_TO_IDX.find(variety_norm);
    if (it == VARIETY_TO_IDX.end()) {
        throw std::runtime_error("Variety no encontrada en vocab: " + variety_norm);
    }

    RegressorBuildOutput out;
    out.x = std::move(x);
    out.variety_idx = {it->second};
    out.visible_count = {static_cast<float>(global_masks.num_grape_det)};
    out.rgb_lb = rgb_lb;
    out.grapes_lb = masks_lb["grapes"];
    out.grapes_dt = grapes_dt;
    out.seg_out = std::move(seg_out);
    out.global_masks = std::move(global_masks);
    out.variety = variety_norm;

    return out;
}


// ============================================================
// REGRESSOR ONNX INFERENCE
// ============================================================
static std::vector<float> extract_tensor_float(const Ort::Value& v) {
    auto info = v.GetTensorTypeAndShapeInfo();
    auto shape = info.GetShape();
    size_t total = 1;
    for (auto d : shape) total *= static_cast<size_t>(d);
    const float* p = v.GetTensorData<float>();
    return std::vector<float>(p, p + total);
}

static float extract_scalar_float(const Ort::Value& v) {
    const float* p = v.GetTensorData<float>();
    return p[0];
}

static RegOutput run_regressor_onnx(
    Ort::Session& reg_session,
    std::vector<float>& x,
    std::array<int64_t, 1>& variety_idx,
    std::array<float, 1>& visible_count
) {
    Ort::AllocatorWithDefaultOptions allocator;
    auto input_names_str = get_input_names(reg_session, allocator);
    auto output_names_str = get_output_names(reg_session, allocator);

    std::map<std::string, size_t> input_name_to_idx;
    for (size_t i = 0; i < input_names_str.size(); ++i) {
        input_name_to_idx[input_names_str[i]] = i;
    }

    Ort::MemoryInfo mem_info = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);

    std::vector<int64_t> x_shape = {1, 5, IMGSZ, IMGSZ};
    std::vector<int64_t> vi_shape = {1};
    std::vector<int64_t> vc_shape = {1};

    Ort::Value x_tensor = make_tensor_float(x, x_shape, mem_info);
    Ort::Value vi_tensor = make_tensor_int64(variety_idx.data(), 1, vi_shape, mem_info);

    Ort::Value vc_tensor = Ort::Value::CreateTensor<float>(
        mem_info,
        visible_count.data(),
        visible_count.size(),
        vc_shape.data(),
        vc_shape.size()
    );

    std::vector<const char*> input_names;
    std::vector<Ort::Value> input_tensors;

    // Intenta mapear por nombre exacto. Si no están, usa orden.
    if (input_name_to_idx.count("x") && input_name_to_idx.count("variety_idx") && input_name_to_idx.count("visible_count")) {
        std::vector<std::pair<std::string, Ort::Value>> ordered;
        ordered.emplace_back("x", std::move(x_tensor));
        ordered.emplace_back("variety_idx", std::move(vi_tensor));
        ordered.emplace_back("visible_count", std::move(vc_tensor));

        for (auto& kv : ordered) {
            input_names.push_back(kv.first.c_str());
            input_tensors.emplace_back(std::move(kv.second));
        }
    } else {
        if (input_names_str.size() != 3) {
            throw std::runtime_error("No pude mapear inputs del regresor. Revisa nombres del ONNX.");
        }
        input_names.push_back(input_names_str[0].c_str());
        input_tensors.emplace_back(std::move(x_tensor));

        input_names.push_back(input_names_str[1].c_str());
        input_tensors.emplace_back(std::move(vi_tensor));

        input_names.push_back(input_names_str[2].c_str());
        input_tensors.emplace_back(std::move(vc_tensor));
    }

    std::vector<const char*> output_names;
    for (auto& s : output_names_str) output_names.push_back(s.c_str());

    auto outputs = reg_session.Run(
        Ort::RunOptions{nullptr},
        input_names.data(),
        input_tensors.data(),
        input_tensors.size(),
        output_names.data(),
        output_names.size()
    );

    std::map<std::string, Ort::Value*> out_map;
    for (size_t i = 0; i < output_names_str.size(); ++i) {
        out_map[output_names_str[i]] = &outputs[i];
    }

    RegOutput out;
    if (!out_map.count("residual_count") || !out_map.count("count_total") ||
        !out_map.count("hist_logits") || !out_map.count("hist_prob") ||
        !out_map.count("hist_counts") || !out_map.count("mean")) {
        throw std::runtime_error("Outputs del regresor no coinciden con los esperados.");
    }

    out.residual_count = extract_scalar_float(*out_map["residual_count"]);
    out.count_total = extract_scalar_float(*out_map["count_total"]);
    out.hist_logits = extract_tensor_float(*out_map["hist_logits"]);
    out.hist_prob = extract_tensor_float(*out_map["hist_prob"]);
    out.hist_counts = extract_tensor_float(*out_map["hist_counts"]);
    out.mean = extract_scalar_float(*out_map["mean"]);

    return out;
}


// ============================================================
// HISTOGRAM TO INTEGER COUNTS
// ============================================================
static std::vector<int> histogram_to_integer_counts(const std::vector<float>& hist_counts, float total_count) {
    std::vector<double> h(hist_counts.begin(), hist_counts.end());
    for (double& v : h) v = std::max(0.0, v);

    int total_int = static_cast<int>(std::llround(static_cast<double>(total_count)));

    if (total_int <= 0) {
        return std::vector<int>(h.size(), 0);
    }

    double sum_h = std::accumulate(h.begin(), h.end(), 0.0);
    if (sum_h <= 0.0) {
        return std::vector<int>(h.size(), 0);
    }

    double scale = static_cast<double>(total_int) / std::max(sum_h, 1e-12);
    for (double& v : h) v *= scale;

    std::vector<int> base(h.size(), 0);
    std::vector<double> frac(h.size(), 0.0);

    int base_sum = 0;
    for (size_t i = 0; i < h.size(); ++i) {
        base[i] = static_cast<int>(std::floor(h[i]));
        frac[i] = h[i] - base[i];
        base_sum += base[i];
    }

    int remainder = total_int - base_sum;

    if (remainder > 0) {
        std::vector<size_t> idx(h.size());
        std::iota(idx.begin(), idx.end(), 0);
        std::sort(idx.begin(), idx.end(), [&](size_t a, size_t b) {
            return frac[a] > frac[b];
        });
        for (int k = 0; k < remainder && k < static_cast<int>(idx.size()); ++k) {
            base[idx[k]] += 1;
        }
    } else if (remainder < 0) {
        std::vector<size_t> idx(h.size());
        std::iota(idx.begin(), idx.end(), 0);
        std::sort(idx.begin(), idx.end(), [&](size_t a, size_t b) {
            return frac[a] < frac[b];
        });
        int need = -remainder;
        for (int k = 0; k < need && k < static_cast<int>(idx.size()); ++k) {
            if (base[idx[k]] > 0) base[idx[k]] -= 1;
        }
    }

    return base;
}


// ============================================================
// VIS
// ============================================================
static cv::Mat draw_segmentation_overlay(const cv::Mat& orig_rgb, const SegOutput& seg_out) {
    cv::Mat img = orig_rgb.clone();
    cv::Mat overlay = orig_rgb.clone();

    std::vector<cv::Scalar> color_table = {
        cv::Scalar(255, 0, 0),
        cv::Scalar(0, 180, 255),
        cv::Scalar(0, 255, 0),
        cv::Scalar(255, 255, 0),
        cv::Scalar(255, 0, 255),
        cv::Scalar(0, 255, 255),
    };

    for (size_t i = 0; i < seg_out.boxes.size(); ++i) {
        int cls_id = seg_out.cls_ids[i];
        cv::Scalar color = color_table[cls_id % color_table.size()];

        int x1 = static_cast<int>(seg_out.boxes[i].x);
        int y1 = static_cast<int>(seg_out.boxes[i].y);
        int x2 = static_cast<int>(seg_out.boxes[i].width);
        int y2 = static_cast<int>(seg_out.boxes[i].height);

        const cv::Mat& mask = seg_out.masks[i];
        for (int y = 0; y < overlay.rows; ++y) {
            const uint8_t* mp = mask.ptr<uint8_t>(y);
            cv::Vec3b* op = overlay.ptr<cv::Vec3b>(y);
            for (int x = 0; x < overlay.cols; ++x) {
                if (mp[x]) {
                    op[x][0] = static_cast<uint8_t>(0.6f * op[x][0] + 0.4f * color[0]);
                    op[x][1] = static_cast<uint8_t>(0.6f * op[x][1] + 0.4f * color[1]);
                    op[x][2] = static_cast<uint8_t>(0.6f * op[x][2] + 0.4f * color[2]);
                }
            }
        }

        cv::rectangle(img, cv::Point(x1, y1), cv::Point(x2, y2), color, 2);

        auto it = CLASS_NAMES.find(cls_id);
        std::string cls_name = (it != CLASS_NAMES.end()) ? it->second : std::to_string(cls_id);
        std::string label = cls_name + " " + cv::format("%.2f", seg_out.scores[i]);

        cv::putText(
            img,
            label,
            cv::Point(x1, std::max(18, y1 - 5)),
            cv::FONT_HERSHEY_SIMPLEX,
            0.55,
            color,
            2,
            cv::LINE_AA
        );
    }

    cv::Mat out;
    cv::addWeighted(overlay, 0.45, img, 0.55, 0.0, out);
    return out;
}


// ============================================================
// PIPELINE
// ============================================================
static PipelineResult run_pipeline(
    Ort::Session& seg_session,
    Ort::Session& reg_session,
    const std::string& image_path,
    const std::string& variety
) {
    RegressorBuildOutput pipe = build_regressor_inputs_from_photo(seg_session, image_path, variety);

    RegOutput reg_out = run_regressor_onnx(
        reg_session,
        pipe.x,
        pipe.variety_idx,
        pipe.visible_count
    );

    std::vector<int> pred_hist_int = histogram_to_integer_counts(
        reg_out.hist_counts,
        reg_out.count_total
    );

    return {std::move(pipe), std::move(reg_out), std::move(pred_hist_int)};
}


// ============================================================
// MAIN
// ============================================================
#ifndef GRAPE_PIPELINE_AS_LIBRARY
int main(int argc, char** argv) {
    try {
        std::string image_path;
        std::string variety_cli;
        std::string provider_cli;
        std::string seg_onnx_path = get_env_or_default("SEG_ONNX_PATH", DEFAULT_SEG_ONNX_PATH);
        std::string reg_onnx_path = get_env_or_default("REG_ONNX_PATH", DEFAULT_REG_ONNX_PATH);
        bool save_debug_images = SAVE_DEBUG_IMAGES;

        for (int i = 1; i < argc; ++i) {
            std::string arg = argv[i];

            if ((arg == "--image" || arg == "-i") && i + 1 < argc) {
                image_path = argv[++i];
            } else if ((arg == "--variety" || arg == "-v") && i + 1 < argc) {
                variety_cli = argv[++i];
            } else if (arg == "--seg" && i + 1 < argc) {
                seg_onnx_path = argv[++i];
            } else if (arg == "--reg" && i + 1 < argc) {
                reg_onnx_path = argv[++i];
            } else if (arg == "--provider" && i + 1 < argc) {
                provider_cli = argv[++i];
            } else if (arg == "--save-debug") {
                save_debug_images = true;
            } else if (arg == "--help" || arg == "-h") {
                std::cout << "Uso:\n";
                std::cout << "  grape_pipeline --image <ruta_imagen> [--variety <nombre>] [--seg <ruta_seg.onnx>] [--reg <ruta_reg.onnx>] [--provider <auto|nnapi|cpu>] [--save-debug]\n";
                std::cout << "\nTambien puedes usar variables de entorno SEG_ONNX_PATH, REG_ONNX_PATH, IMAGE_PATH, ORT_PROVIDER.\n";
                return 0;
            } else if (!arg.empty() && arg[0] != '-' && image_path.empty()) {
                image_path = arg;
            } else {
                throw std::runtime_error("Argumento invalido: " + arg + ". Usa --help para ver opciones.");
            }
        }

        if (image_path.empty()) {
            image_path = get_env_or_default("IMAGE_PATH", "");
        }

        if (image_path.empty()) {
            throw std::runtime_error("Debes indicar una imagen con --image <ruta> o variable IMAGE_PATH.");
        }

        if (provider_cli.empty()) {
            provider_cli = get_env_or_default("ORT_PROVIDER", "auto");
        }
        ProviderPreference provider_preference = parse_provider_preference(provider_cli);

        Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "grape_pipeline");
        std::string seg_provider_used;
        std::string reg_provider_used;
        Ort::Session seg_session = create_session(env, seg_onnx_path, provider_preference, seg_provider_used);
        Ort::Session reg_session = create_session(env, reg_onnx_path, provider_preference, reg_provider_used);

        std::string variety;
        if (!variety_cli.empty()) {
            variety = normalize_variety(variety_cli);
        } else if (!VARIETY_OVERRIDE.empty()) {
            variety = normalize_variety(VARIETY_OVERRIDE);
        } else {
            variety = infer_variety_from_filename(image_path);
        }

        PipelineResult result = run_pipeline(
            seg_session,
            reg_session,
            image_path,
            variety
        );

        std::cout << std::string(90, '=') << "\n";
        std::cout << "PIPELINE RESULT\n";
        std::cout << std::string(90, '=') << "\n";
        std::cout << "image_path     : " << image_path << "\n";
        std::cout << "variety        : " << result.pipe.variety << "\n";
        std::cout << "variety_idx    : " << result.pipe.variety_idx[0] << "\n";
        std::cout << "visible_count  : " << result.pipe.visible_count[0] << "\n";
        std::cout << "residual_count : " << result.reg_out.residual_count << "\n";
        std::cout << "count_total    : " << result.reg_out.count_total << "\n";
        std::cout << "mean_mm        : " << result.reg_out.mean << "\n";
        std::cout << "provider_seg   : " << seg_provider_used << "\n";
        std::cout << "provider_reg   : " << reg_provider_used << "\n";

        std::cout << "\nTop bins pred:\n";
        std::vector<size_t> idx(result.hist_counts_int.size());
        std::iota(idx.begin(), idx.end(), 0);
        std::sort(idx.begin(), idx.end(), [&](size_t a, size_t b) {
            return result.hist_counts_int[a] > result.hist_counts_int[b];
        });

        for (int k = 0; k < std::min<int>(8, static_cast<int>(idx.size())); ++k) {
            size_t i = idx[k];
            std::cout << "  Cal " << BINS[i] << " -> " << result.hist_counts_int[i] << "\n";
        }

        if (save_debug_images) {
            cv::Mat overlay = draw_segmentation_overlay(result.pipe.seg_out.orig_rgb, result.pipe.seg_out);

            cv::Mat overlay_bgr, rgb_lb_bgr, grapes_vis, dt_vis;
            cv::cvtColor(overlay, overlay_bgr, cv::COLOR_RGB2BGR);
            cv::cvtColor(result.pipe.rgb_lb, rgb_lb_bgr, cv::COLOR_RGB2BGR);

            grapes_vis = result.pipe.grapes_lb.clone();
            dt_vis = result.pipe.grapes_dt.clone();

            cv::imwrite("seg_overlay.jpg", overlay_bgr);
            cv::imwrite("rgb_letterbox.jpg", rgb_lb_bgr);
            cv::imwrite("grapes_mask.jpg", grapes_vis);
            cv::imwrite("grapes_dt.jpg", dt_vis);

            std::cout << "\nGuardado:\n";
            std::cout << "  seg_overlay.jpg\n";
            std::cout << "  rgb_letterbox.jpg\n";
            std::cout << "  grapes_mask.jpg\n";
            std::cout << "  grapes_dt.jpg\n";
        }

        return 0;
    }
    catch (const Ort::Exception& e) {
        std::cerr << "[ORT ERROR] " << e.what() << "\n";
        return 2;
    }
    catch (const std::exception& e) {
        std::cerr << "[ERROR] " << e.what() << "\n";
        return 1;
    }
}
#endif
