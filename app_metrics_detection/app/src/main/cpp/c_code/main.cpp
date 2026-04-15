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

// ============================================================
// CONFIG
// ============================================================
static constexpr int IMGSZ = 512;
static constexpr float CONF_THRES = 0.25f;
static constexpr float MASK_THRES = 0.5f;

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

// LISTA OFICIAL DE VARIEDADES (ORDEN CRITICO 0-11)
static const std::vector<std::string> VARIETY_CLASSES = {
    "ALLISON",      // 0
    "AUTUMN CRISP", // 1
    "CRIMSON",      // 2
    "IVORY",        // 3
    "MAGENTA",      // 4
    "RED GLOBE",    // 5
    "SCARLOTTA",    // 6
    "SUPERIOR",     // 7
    "SWEET GLOBE",  // 8
    "THOMPSON",     // 9
    "TIMCO",        // 10
    "TIMPSON"       // 11
};

static std::map<std::string, int64_t> buildVarietyMap() {
    std::map<std::string, int64_t> m;
    for (size_t i = 0; i < VARIETY_CLASSES.size(); ++i) {
        m[VARIETY_CLASSES[i]] = static_cast<int64_t>(i);
    }
    return m;
}

static const std::map<std::string, int64_t> VARIETY_TO_IDX = buildVarietyMap();

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
    cv::Mat mask_coeffs;
    cv::Mat protos;
    int proto_c = 0, proto_h = 0, proto_w = 0;
};

struct SegOutput {
    cv::Mat orig_bgr;
    cv::Mat orig_rgb;
    std::vector<cv::Rect2f> boxes;
    std::vector<float> scores;
    std::vector<int> cls_ids;
    std::vector<cv::Mat> masks;
};

struct GlobalMasks {
    cv::Mat bunch;
    cv::Mat grapes;
    cv::Mat pingpong;
    int num_bunch_det = 0;
    int num_grape_det = 0;
    int num_pingpong_det = 0;
};

struct RegressorBuildOutput {
    std::vector<float> x;
    std::array<int64_t, 1> variety_idx{};
    float seg_count_base = 0.0f;

    cv::Mat rgb_lb;
    cv::Mat grapes_lb;
    cv::Mat grapes_dt;

    SegOutput seg_out;
    GlobalMasks global_masks;
    std::string variety;
};

struct RegOutput {
    float count_total = 0.0f;
    std::vector<float> hist_prob;
    std::vector<float> hist_counts;
    float mean = 0.0f;
    float mu1 = 0.0f, mu2 = 0.0f, sigma1 = 0.0f, sigma2 = 0.0f, w1 = 0.0f, w2 = 0.0f, temp = 0.0f;
    float delta_log_count = 0.0f;
    float pred_log_total = 0.0f;
    float mode = 0.0f;
    float std = 0.0f;
};

struct PipelineResult {
    RegressorBuildOutput pipe;
    RegOutput reg_out;
    std::vector<int> hist_counts_int;
};

enum class ProviderPreference { AndroidAuto, Nnapi, Cpu };

// ============================================================
// UTILS
// ============================================================
static inline float sigmoid_scalar(float x) { return 1.0f / (1.0f + std::exp(-x)); }

static std::string to_upper_ascii(std::string s) {
    for (char& c : s) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
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
        {"AUTUM CRISP", "AUTUMN CRISP"}, {"RED GLOVE", "RED GLOBE"}, {"TINCO", "TIMCO"}
    };
    auto it = alias_map.find(s);
    return (it != alias_map.end()) ? it->second : s;
}

static std::string infer_variety_from_filename(const std::string& image_path) {
    std::string name = to_upper_ascii(fs::path(image_path).filename().string());
    for (const auto& v : VARIETY_CLASSES) {
        if (name.find(v) != std::string::npos) return v;
    }
    return "ALLISON"; // Default fallback
}

static ProviderPreference parse_provider_preference(const std::string& p_raw) {
    std::string p = trim_spaces(p_raw);
    std::transform(p.begin(), p.end(), p.begin(), ::tolower);
    if (p == "nnapi") return ProviderPreference::Nnapi;
    if (p == "cpu") return ProviderPreference::Cpu;
    return ProviderPreference::AndroidAuto;
}

static std::vector<std::string> get_onnx_names(Ort::Session& session, bool input, Ort::AllocatorWithDefaultOptions& allocator) {
    std::vector<std::string> names;
    size_t n = input ? session.GetInputCount() : session.GetOutputCount();
    for (size_t i = 0; i < n; ++i) {
        auto p = input ? session.GetInputNameAllocated(i, allocator) : session.GetOutputNameAllocated(i, allocator);
        names.emplace_back(p.get());
    }
    return names;
}

static Ort::Session create_session(Ort::Env& env, const std::string& path, ProviderPreference pref, std::string& prov_used) {
    if (!fs::exists(path)) throw std::runtime_error("Modelo no encontrado: " + path);
    Ort::SessionOptions opts;
    opts.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_EXTENDED);
    opts.SetIntraOpNumThreads(1);
    prov_used = "CPUExecutionProvider";
#if defined(ORT_HAS_NNAPI_PROVIDER)
    if (pref != ProviderPreference::Cpu) {
        OrtSessionOptionsAppendExecutionProvider_Nnapi(opts, 0);
        prov_used = "NNAPIExecutionProvider";
    }
#endif
    return Ort::Session(env, path.c_str(), opts);
}

// ============================================================
// PRE/POST PROCESSING
// ============================================================
static LetterboxResult letterbox(const cv::Mat& image, int nw = 512, int nh = 512, const cv::Scalar& color = cv::Scalar(114, 114, 114)) {
    float r = std::min((float)nw / image.cols, (float)nh / image.rows);
    int unw = (int)std::round(image.cols * r), unh = (int)std::round(image.rows * r);
    cv::Mat resized;
    if (image.cols != unw || image.rows != unh) cv::resize(image, resized, cv::Size(unw, unh));
    else resized = image.clone();
    float dw = (nw - unw) / 2.0f, dh = (nh - unh) / 2.0f;
    cv::Mat out;
    cv::copyMakeBorder(resized, out, (int)std::round(dh - 0.1f), (int)std::round(dh + 0.1f), (int)std::round(dw - 0.1f), (int)std::round(dw + 0.1f), cv::BORDER_CONSTANT, color);
    return {out, r, dw, dh};
}

static cv::Mat compute_grapes_distance_transform(const cv::Mat& mask_u8) {
    cv::Mat bin;
    cv::threshold(mask_u8, bin, 0, 1, cv::THRESH_BINARY);
    if (cv::countNonZero(bin) == 0) return cv::Mat::zeros(mask_u8.size(), CV_8U);
    cv::Mat dt;
    cv::distanceTransform(bin, dt, cv::DIST_L2, 5);
    double mval;
    cv::minMaxLoc(dt, nullptr, &mval);
    if (mval > 0) dt /= (float)mval;
    cv::Mat out;
    dt.convertTo(out, CV_8U, 255.0);
    return out;
}

static cv::Mat scale_mask_back_to_original(const cv::Mat& mask_lb, float ratio, float dw, float dh, const cv::Size& orig_size) {
    const int x1 = std::clamp((int)std::round(dw), 0, mask_lb.cols);
    const int y1 = std::clamp((int)std::round(dh), 0, mask_lb.rows);
    const int x2 = std::clamp(mask_lb.cols - (int)std::round(dw), 0, mask_lb.cols);
    const int y2 = std::clamp(mask_lb.rows - (int)std::round(dh), 0, mask_lb.rows);
    if (x2 <= x1 || y2 <= y1) return cv::Mat::zeros(orig_size, CV_8U);

    cv::Mat cropped = mask_lb(cv::Rect(x1, y1, x2 - x1, y2 - y1));
    if (cropped.empty()) return cv::Mat::zeros(orig_size, CV_8U);

    cv::Mat restored;
    cv::resize(cropped, restored, orig_size, 0.0, 0.0, cv::INTER_NEAREST);
    return restored;
}

static cv::Mat letterbox_mask_to_runtime(const cv::Mat& mask_u8, const cv::Size& orig_size, float ratio, float dw, float dh) {
    const int new_unpad_w = (int)std::round(orig_size.width * ratio);
    const int new_unpad_h = (int)std::round(orig_size.height * ratio);
    const int top = (int)std::round(dh - 0.1f);
    const int bottom = (int)std::round(dh + 0.1f);
    const int left = (int)std::round(dw - 0.1f);
    const int right = (int)std::round(dw + 0.1f);

    cv::Mat resized;
    cv::resize(mask_u8, resized, cv::Size(new_unpad_w, new_unpad_h), 0.0, 0.0, cv::INTER_NEAREST);

    cv::Mat out;
    cv::copyMakeBorder(resized, out, top, bottom, left, right, cv::BORDER_CONSTANT, cv::Scalar(0));
    return out;
}

static float compute_hist_mode(const std::vector<float>& hist_counts) {
    if (hist_counts.empty()) return 0.0f;
    const auto it = std::max_element(hist_counts.begin(), hist_counts.end());
    const size_t idx = (size_t)std::distance(hist_counts.begin(), it);
    return idx < BINS.size() ? (float)BINS[idx] : 0.0f;
}

static float compute_hist_std(const std::vector<float>& hist_prob, float mean) {
    if (hist_prob.empty()) return 0.0f;
    double total = 0.0;
    double variance = 0.0;
    for (size_t i = 0; i < hist_prob.size() && i < BINS.size(); ++i) {
        const double w = std::max(0.0f, hist_prob[i]);
        total += w;
        const double delta = (double)BINS[i] - mean;
        variance += w * delta * delta;
    }
    if (total <= 0.0) return 0.0f;
    return (float)std::sqrt(variance / total);
}

static SegDecodeResult decode_segmentation(const std::vector<Ort::Value>& outs, float conf) {
    auto det_shape = outs[0].GetTensorTypeAndShapeInfo().GetShape();
    const float* det_ptr = outs[0].GetTensorData<float>();
    int64_t N = det_shape[1], D = det_shape[2];
    auto proto_shape = outs[1].GetTensorTypeAndShapeInfo().GetShape();
    const float* proto_ptr = outs[1].GetTensorData<float>();
    int pc = (int)proto_shape[1], ph = (int)proto_shape[2], pw = (int)proto_shape[3];

    SegDecodeResult res;
    res.proto_c = pc; res.proto_h = ph; res.proto_w = pw;
    std::vector<float> coeffs;
    for (int64_t i = 0; i < N; ++i) {
        const float* r = det_ptr + i * D;
        if (r[4] < conf) continue;
        res.boxes_lb.emplace_back(cv::Rect2f(r[0], r[1], r[2], r[3]));
        res.scores.push_back(r[4]);
        res.cls_ids.push_back((int)r[5]);
        for (int c = 0; c < pc; ++c) coeffs.push_back(r[6 + c]);
    }
    if (!coeffs.empty()) res.mask_coeffs = cv::Mat((int)res.boxes_lb.size(), pc, CV_32F, coeffs.data()).clone();
    res.protos = cv::Mat(pc, ph * pw, CV_32F, (void*)proto_ptr).clone();
    return res;
}

static std::vector<cv::Mat> reconstruct_masks(const SegDecodeResult& dec, int imgsz, float thres) {
    std::vector<cv::Mat> out;
    if (dec.mask_coeffs.rows == 0) return out;
    cv::Mat ms = dec.mask_coeffs * dec.protos;
    for (int i = 0; i < ms.rows; ++i) {
        cv::Mat m = ms.row(i).reshape(1, dec.proto_h).clone();
        for (int r = 0; r < m.rows; ++r) {
            float* p = m.ptr<float>(r);
            for (int c = 0; c < m.cols; ++c) p[c] = sigmoid_scalar(p[c]);
        }
        cv::Mat mup, mbin;
        cv::resize(m, mup, cv::Size(imgsz, imgsz));
        cv::threshold(mup, mbin, thres, 255, cv::THRESH_BINARY);
        mbin.convertTo(mbin, CV_8U);
        // Crop mask logic
        cv::Rect2f b = dec.boxes_lb[i];
        cv::Mat mcrop = cv::Mat::zeros(mbin.size(), CV_8U);
        int x1 = std::max(0, (int)b.x), y1 = std::max(0, (int)b.y);
        int x2 = std::min(mbin.cols, (int)b.width), y2 = std::min(mbin.rows, (int)b.height);
        if (x2 > x1 && y2 > y1) mbin(cv::Rect(x1, y1, x2 - x1, y2 - y1)).copyTo(mcrop(cv::Rect(x1, y1, x2 - x1, y2 - y1)));
        out.push_back(mcrop);
    }
    return out;
}

// ============================================================
// CORE PIPELINE
// ============================================================
static RegressorBuildOutput build_regressor_inputs(Ort::Session& seg_sess, const std::string& path, const std::string& variety) {
    cv::Mat bgr = cv::imread(path);
    if (bgr.empty()) throw std::runtime_error("Error lectura imagen");
    auto lb = letterbox(bgr);
    cv::Mat rgb; cv::cvtColor(lb.image, rgb, cv::COLOR_BGR2RGB);
    cv::Mat x_img; rgb.convertTo(x_img, CV_32F, 1.0 / 255.0);
    std::vector<float> x_data(1 * 3 * IMGSZ * IMGSZ);
    for (int c = 0; c < 3; ++c) {
        for (int y = 0; y < IMGSZ; ++y) {
            for (int x = 0; x < IMGSZ; ++x) x_data[c * IMGSZ * IMGSZ + y * IMGSZ + x] = x_img.at<cv::Vec3f>(y, x)[c];
        }
    }
    Ort::MemoryInfo mem = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    std::vector<int64_t> shape = {1, 3, IMGSZ, IMGSZ};
    Ort::Value in_tensor = Ort::Value::CreateTensor<float>(mem, x_data.data(), x_data.size(), shape.data(), shape.size());
    Ort::AllocatorWithDefaultOptions alloc;
    auto in_names = get_onnx_names(seg_sess, true, alloc);
    auto out_names = get_onnx_names(seg_sess, false, alloc);
    std::vector<const char*> cin_names = {in_names[0].c_str()}, cout_names;
    for (auto& n : out_names) cout_names.push_back(n.c_str());
    auto outs = seg_sess.Run(Ort::RunOptions{nullptr}, cin_names.data(), &in_tensor, 1, cout_names.data(), cout_names.size());
    auto dec = decode_segmentation(outs, CONF_THRES);
    auto masks_lb = reconstruct_masks(dec, IMGSZ, MASK_THRES);

    cv::Mat gmask_orig = cv::Mat::zeros(bgr.size(), CV_8U);
    cv::Mat pmask_orig = cv::Mat::zeros(bgr.size(), CV_8U);
    cv::Mat bmask_orig = cv::Mat::zeros(bgr.size(), CV_8U);
    int ng = 0, np = 0, nb = 0;
    for (size_t i = 0; i < dec.cls_ids.size(); ++i) {
        cv::Mat mask_orig = scale_mask_back_to_original(masks_lb[i], lb.ratio, lb.dw, lb.dh, bgr.size());
        std::string n = CLASS_NAMES.at(dec.cls_ids[i]);
        if (n.find("grape") != std::string::npos) { cv::max(gmask_orig, mask_orig, gmask_orig); ng++; }
        else if (n.find("ping") != std::string::npos) { cv::max(pmask_orig, mask_orig, pmask_orig); np++; }
        else if (n.find("bunch") != std::string::npos) { cv::max(bmask_orig, mask_orig, bmask_orig); nb++; }
    }

    cv::Mat gmask = letterbox_mask_to_runtime(gmask_orig, bgr.size(), lb.ratio, lb.dw, lb.dh);
    cv::Mat pmask = letterbox_mask_to_runtime(pmask_orig, bgr.size(), lb.ratio, lb.dw, lb.dh);
    cv::Mat bmask = letterbox_mask_to_runtime(bmask_orig, bgr.size(), lb.ratio, lb.dw, lb.dh);
    cv::Mat gdt = compute_grapes_distance_transform(gmask);
    std::vector<float> x_5ch(1 * 5 * IMGSZ * IMGSZ);
    cv::Mat gf, gdtf; gmask.convertTo(gf, CV_32F, 1.0 / 255.0); gdt.convertTo(gdtf, CV_32F, 1.0 / 255.0);
    size_t pl = IMGSZ * IMGSZ;
    for (int y = 0; y < IMGSZ; ++y) {
        for (int x = 0; x < IMGSZ; ++x) {
            cv::Vec3f p = x_img.at<cv::Vec3f>(y, x);
            x_5ch[0 * pl + y * IMGSZ + x] = p[0]; x_5ch[1 * pl + y * IMGSZ + x] = p[1]; x_5ch[2 * pl + y * IMGSZ + x] = p[2];
            x_5ch[3 * pl + y * IMGSZ + x] = gf.at<float>(y, x); x_5ch[4 * pl + y * IMGSZ + x] = gdtf.at<float>(y, x);
        }
    }
    std::string vnorm = normalize_variety(variety);
    RegressorBuildOutput out;
    out.x = std::move(x_5ch);
    out.variety_idx = { VARIETY_TO_IDX.count(vnorm) ? VARIETY_TO_IDX.at(vnorm) : 0 };
    out.seg_count_base = (float)ng;
    out.grapes_lb = gmask; out.grapes_dt = gdt; out.rgb_lb = lb.image;
    out.global_masks = { bmask, gmask, pmask, nb, ng, np };
    out.variety = vnorm;
    // SegOutput for visualization
    out.seg_out.orig_rgb = rgb; out.seg_out.cls_ids = dec.cls_ids; out.seg_out.masks = masks_lb;
    return out;
}

static RegOutput run_unified_regressor(Ort::Session& sess, RegressorBuildOutput& pipe) {
    Ort::MemoryInfo mem = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    std::vector<int64_t> x_sh = {1, 5, IMGSZ, IMGSZ}, v_sh = {1}, s_sh = {1};
    std::vector<Ort::Value> ins;
    ins.push_back(Ort::Value::CreateTensor<float>(mem, pipe.x.data(), pipe.x.size(), x_sh.data(), x_sh.size()));
    ins.push_back(Ort::Value::CreateTensor<int64_t>(mem, pipe.variety_idx.data(), 1, v_sh.data(), v_sh.size()));
    ins.push_back(Ort::Value::CreateTensor<float>(mem, &pipe.seg_count_base, 1, s_sh.data(), s_sh.size()));

    const char* in_names[] = {"x", "variety_idx", "seg_count_base"};
    const char* out_names[] = {"hist_prob", "count_total", "counts_pred", "mean_pred", "mu1", "mu2", "sigma1", "sigma2", "w1", "w2", "temp", "delta_log_count", "pred_log_total"};
    auto outs = sess.Run(Ort::RunOptions{nullptr}, in_names, ins.data(), 3, out_names, 13);

    auto get_vec = [](const Ort::Value& v) {
        const float* p = v.GetTensorData<float>();
        auto sh = v.GetTensorTypeAndShapeInfo().GetShape();
        size_t sz = 1; for (auto d : sh) sz *= d;
        return std::vector<float>(p, p + sz);
    };

    RegOutput res;
    res.hist_prob = get_vec(outs[0]);
    res.count_total = outs[1].GetTensorData<float>()[0];
    res.hist_counts = get_vec(outs[2]);
    res.mean = outs[3].GetTensorData<float>()[0];
    res.mu1 = outs[4].GetTensorData<float>()[0]; res.mu2 = outs[5].GetTensorData<float>()[0];
    res.sigma1 = outs[6].GetTensorData<float>()[0]; res.sigma2 = outs[7].GetTensorData<float>()[0];
    res.w1 = outs[8].GetTensorData<float>()[0]; res.w2 = outs[9].GetTensorData<float>()[0];
    res.temp = outs[10].GetTensorData<float>()[0];
    res.delta_log_count = outs[11].GetTensorData<float>()[0];
    res.pred_log_total = outs[12].GetTensorData<float>()[0];
    res.mode = compute_hist_mode(res.hist_counts);
    res.std = compute_hist_std(res.hist_prob, res.mean);
    return res;
}

static std::vector<int> hist_to_int(const std::vector<float>& h, float total) {
    int t = (int)std::round(total);
    if (t <= 0) return std::vector<int>(h.size(), 0);
    double s = 0; for (float v : h) s += std::max(0.0f, v);
    if (s <= 0) return std::vector<int>(h.size(), 0);
    std::vector<int> b(h.size()); std::vector<double> f(h.size());
    int bs = 0;
    for (size_t i = 0; i < h.size(); ++i) {
        double v = h[i] * t / s;
        b[i] = (int)std::floor(v); f[i] = v - b[i]; bs += b[i];
    }
    std::vector<size_t> ids(h.size()); std::iota(ids.begin(), ids.end(), 0);
    std::sort(ids.begin(), ids.end(), [&](size_t i, size_t j) { return f[i] > f[j]; });
    for (int i = 0; i < t - bs && i < (int)ids.size(); ++i) b[ids[i]]++;
    return b;
}

static PipelineResult run_pipeline(Ort::Session& seg, Ort::Session& reg, const std::string& path, const std::string& variety) {
    auto pipe = build_regressor_inputs(seg, path, variety);
    auto reg_out = run_unified_regressor(reg, pipe);
    auto h_int = hist_to_int(reg_out.hist_counts, reg_out.count_total);
    return { std::move(pipe), std::move(reg_out), std::move(h_int) };
}
