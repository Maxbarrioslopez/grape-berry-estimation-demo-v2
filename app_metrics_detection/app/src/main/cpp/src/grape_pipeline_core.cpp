#include "grape_pipeline_core.hpp"

#include "grape_pipeline_config.hpp"
#include "grape_pipeline_postprocess.hpp"
#include "grape_pipeline_preprocess.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <stdexcept>
#include <vector>

namespace grape {
namespace {

using Clock = std::chrono::high_resolution_clock;

std::vector<const char*> ToCStringVector(const std::vector<std::string>& names) {
    std::vector<const char*> raw;
    raw.reserve(names.size());
    for (const std::string& name : names) {
        raw.push_back(name.c_str());
    }
    return raw;
}

std::vector<float> RunFloatVectorModel(
    Ort::Session& session,
    const std::vector<std::string>& input_names,
    std::vector<Ort::Value>& input_values,
    const std::string& output_name) {
    const std::vector<const char*> input_names_raw = ToCStringVector(input_names);
    const std::vector<const char*> output_names_raw = {output_name.c_str()};

    auto outputs = session.Run(
        Ort::RunOptions {nullptr},
        input_names_raw.data(),
        input_values.data(),
        input_values.size(),
        output_names_raw.data(),
        output_names_raw.size());

    const float* output_ptr = outputs.front().GetTensorData<float>();
    const auto output_shape = outputs.front().GetTensorTypeAndShapeInfo().GetShape();
    size_t output_size = 1;
    for (int64_t dim : output_shape) {
        if (dim > 0) {
            output_size *= static_cast<size_t>(dim);
        }
    }
    if (output_shape.empty()) {
        output_size = 1;
    }
    return std::vector<float>(output_ptr, output_ptr + output_size);
}

}  // namespace

GrapePipelineCore::GrapePipelineCore(
    Ort::Session* segmentation_session,
    Ort::Session& qty_session,
    Ort::Session& hist_session,
    RuntimeModelContract contract,
    BundleResolution bundle,
    std::string seg_provider,
    std::string qty_provider,
    std::string hist_provider)
    : segmentation_session_(segmentation_session),
      qty_session_(qty_session),
      hist_session_(hist_session),
      contract_(std::move(contract)),
      bundle_(std::move(bundle)),
      seg_provider_(std::move(seg_provider)),
      qty_provider_(std::move(qty_provider)),
      hist_provider_(std::move(hist_provider)) {}

PipelineResult GrapePipelineCore::Run(
    const std::string& image_path,
    int variety_id,
    bool save_debug_artifacts,
    bool allow_synthetic_rgbdt,
    const std::string& visual_overlay_path) const {
    PipelineResult result;
    result.status = false;
    result.bins = DefaultBinsVector();
    result.input_mode = contract_.input_mode;
    result.seg_provider = seg_provider_;
    result.qty_provider = qty_provider_;
    result.hist_provider = hist_provider_;
    result.segmentation_model_path = bundle_.segmentation_path;
    result.qty_model_path = bundle_.qty_path;
    result.hist_model_path = bundle_.hist_path;
    result.debug = BuildDefaultDebugArtifacts(image_path, save_debug_artifacts);

    const auto total_t0 = Clock::now();
    try {
        const std::vector<std::string>& variety_classes = bundle_.variety_classes.empty()
            ? kDefaultVarietyClasses
            : bundle_.variety_classes;
        const std::string variety_name = ResolveVarietyName(variety_id, image_path, variety_classes);
        const int64_t variety_index = ResolveVarietyIndex(variety_name, variety_classes);
        result.variety = variety_name;
        result.variety_idx = static_cast<int>(variety_index);

        const auto prep_t0 = Clock::now();
        PipelineInputs inputs = BuildPipelineInputs(
            image_path,
            variety_name,
            variety_index,
            contract_,
            segmentation_session_,
            allow_synthetic_rgbdt);
        const auto prep_t1 = Clock::now();
        result.timing.preprocess_ms =
            std::chrono::duration<double, std::milli>(prep_t1 - prep_t0).count();
        // No hay instrumentacion separada entre segmentacion y armado de tensor.
        result.timing.segmentation_ms =
            segmentation_session_ != nullptr ? result.timing.preprocess_ms : 0.0;

        result.detections = inputs.seg.detections;
        result.seg_count_base = inputs.seg_count_base[0];
        result.used_synthetic_fallback = inputs.used_synthetic_fallback;

        // 🚀 GATE DE NEGOCIO: Mínimo 2 uvas para continuar con el análisis pesado
        if (result.seg_count_base < 2.0f) {
            result.error = "No fue posible identificar suficientes uvas para el análisis.";
            // Saltamos cálculos de QTY y HIST para evitar métricas falsas
        } else {
            // --- INICIO CÁLCULOS PESADOS ---
            Ort::MemoryInfo memory_info = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
            std::vector<int64_t> x_shape = {1, contract_.input_channels, contract_.img_size, contract_.img_size};
            std::vector<int64_t> scalar_shape = {1};

            std::vector<Ort::Value> qty_inputs;
            qty_inputs.reserve(contract_.uses_seg_count_base ? 3U : 2U);
            qty_inputs.push_back(Ort::Value::CreateTensor<float>(
                memory_info, inputs.x.data(), inputs.x.size(), x_shape.data(), x_shape.size()));
            qty_inputs.push_back(Ort::Value::CreateTensor<int64_t>(
                memory_info, inputs.variety_idx.data(), inputs.variety_idx.size(), scalar_shape.data(), scalar_shape.size()));
            if (contract_.uses_seg_count_base) {
                qty_inputs.push_back(Ort::Value::CreateTensor<float>(
                    memory_info, inputs.seg_count_base.data(), inputs.seg_count_base.size(), scalar_shape.data(), scalar_shape.size()));
            }

            std::vector<std::string> qty_input_names = { contract_.qty_x_input_name, contract_.qty_variety_input_name };
            if (contract_.uses_seg_count_base) { qty_input_names.push_back(contract_.qty_seg_count_input_name); }

            const auto qty_t0 = Clock::now();
            const std::vector<float> qty_output = RunFloatVectorModel(qty_session_, qty_input_names, qty_inputs, contract_.qty_output_name);
            const auto qty_t1 = Clock::now();
            result.timing.qty_ms = std::chrono::duration<double, std::milli>(qty_t1 - qty_t0).count();

            if (!qty_output.empty()) {
                const float raw_count_total = qty_output.front();
                result.count_total = std::isfinite(raw_count_total) ? std::max(0.0f, raw_count_total) : 0.0f;
            }

            std::vector<Ort::Value> hist_inputs;
            hist_inputs.reserve(2);
            hist_inputs.push_back(Ort::Value::CreateTensor<float>(
                memory_info, inputs.x.data(), inputs.x.size(), x_shape.data(), x_shape.size()));
            hist_inputs.push_back(Ort::Value::CreateTensor<int64_t>(
                memory_info, inputs.variety_idx.data(), inputs.variety_idx.size(), scalar_shape.data(), scalar_shape.size()));

            const std::vector<std::string> hist_input_names = { contract_.hist_x_input_name, contract_.hist_variety_input_name };

            const auto hist_t0 = Clock::now();
            result.hist_prob = NormalizeHistogram(RunFloatVectorModel(hist_session_, hist_input_names, hist_inputs, contract_.hist_output_name));
            const auto hist_t1 = Clock::now();
            result.timing.hist_ms = std::chrono::duration<double, std::milli>(hist_t1 - hist_t0).count();

            if (!result.hist_prob.empty()) {
                result.count_pred_by_bin.resize(result.hist_prob.size(), 0.0f);
                for (size_t i = 0; i < result.hist_prob.size(); ++i) {
                    result.count_pred_by_bin[i] = result.hist_prob[i] * result.count_total;
                }
                result.count_pred_by_bin_int = HistogramToIntegers(result.count_pred_by_bin, result.count_total);
                result.mean = ComputeHistogramMean(result.hist_prob, result.bins);
                result.mode = ComputeHistogramMode(result.count_pred_by_bin, result.bins);
                result.std = ComputeHistogramStd(result.hist_prob, result.mean, result.bins);

                const auto peak_it = std::max_element(result.hist_prob.begin(), result.hist_prob.end());
                if (peak_it != result.hist_prob.end()) {
                    const size_t peak_index = static_cast<size_t>(std::distance(result.hist_prob.begin(), peak_it));
                    if (peak_index < result.bins.size()) result.peak_bin_mm = result.bins[peak_index];
                }
            }
            // --- FIN CÁLCULOS PESADOS ---
        }

        const auto post_t0 = Clock::now();
        SaveDebugArtifacts(inputs, result);

        // ✅ DIBUJO VISUAL FINAL NATIVO (Siempre se ejecuta para feedback visual)
        if (!visual_overlay_path.empty()) {
            SaveVisualOverlay(visual_overlay_path, inputs, result);
        }

        const auto post_t1 = Clock::now();
        result.timing.post_ms = std::chrono::duration<double, std::milli>(post_t1 - post_t0).count();

        result.status = true;
    } catch (const std::exception& exc) {
        result.error = exc.what();
        result.status = false;
    }

    const auto total_t1 = Clock::now();
    result.timing.total_ms = std::chrono::duration<double, std::milli>(total_t1 - total_t0).count();
    return result;
}

}  // namespace grape
