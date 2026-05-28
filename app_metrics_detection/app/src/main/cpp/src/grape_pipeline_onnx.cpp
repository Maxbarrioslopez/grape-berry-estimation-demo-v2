#include "grape_pipeline_onnx.hpp"

#include "grape_pipeline_config.hpp"

#include <cctype>
#include <filesystem>
#include <stdexcept>

#if defined(__has_include)
#if __has_include(<onnxruntime/core/providers/nnapi/nnapi_provider_factory.h>)
#include <onnxruntime/core/providers/nnapi/nnapi_provider_factory.h>
#define GRAPE_PIPELINE_HAS_NNAPI 1
#endif
#endif

namespace grape {
namespace {

namespace fs = std::filesystem;

std::string ToLowerAscii(std::string value) {
    for (char& ch : value) {
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    }
    return value;
}

bool ExistsAndIsFile(const std::string& path) {
    return !path.empty() && fs::exists(path) && fs::is_regular_file(path);
}

bool ExistsAndIsDirectory(const std::string& path) {
    return !path.empty() && fs::exists(path) && fs::is_directory(path);
}

fs::path ResolveModelRoot(const fs::path& bundle_dir) {
    const fs::path nested_root = bundle_dir / "modelos";
    if (ExistsAndIsDirectory(nested_root.string())) {
        return nested_root;
    }
    return bundle_dir;
}

std::string ResolveDefaultSegmentationPath(
    const std::string& explicit_segmentation_path,
    const fs::path& anchor_dir) {
    if (!explicit_segmentation_path.empty()) {
        return explicit_segmentation_path;
    }

    const fs::path model_root = ResolveModelRoot(anchor_dir);
    const fs::path default_seg = model_root / "legacy" / "seg_best.onnx";
    return default_seg.string();
}

fs::path ResolveOfficialBundleRoot(const fs::path& spec_path) {
    if (ExistsAndIsDirectory(spec_path.string())) {
        return ResolveModelRoot(spec_path);
    }

    if (!ExistsAndIsFile(spec_path.string())) {
        throw std::runtime_error("The official bundle path does not exist: " + spec_path.string());
    }

    const std::string file_name = spec_path.filename().string();
    if (file_name != kOfficialHistModel && file_name != kOfficialQtyModel) {
        throw std::runtime_error(
            "The official runtime only accepts the model directory or the official ONNX files: " +
            spec_path.string());
    }

    return ResolveModelRoot(spec_path.parent_path());
}

BundleResolution ResolveFromDirectory(const std::string& segmentation_model_path, const fs::path& bundle_dir) {
    const fs::path model_root = ResolveOfficialBundleRoot(bundle_dir);
    BundleResolution bundle;
    bundle.bundle_dir = model_root.string();
    bundle.variety_classes = kDefaultVarietyClasses;
    bundle.hist_path = (model_root / kOfficialHistModel).string();
    bundle.qty_path = (model_root / kOfficialQtyModel).string();

    if (!ExistsAndIsFile(bundle.hist_path)) {
        throw std::runtime_error("Official HIST not found: " + bundle.hist_path);
    }
    if (!ExistsAndIsFile(bundle.qty_path)) {
        throw std::runtime_error("Official QTY not found: " + bundle.qty_path);
    }

    bundle.segmentation_path = ResolveDefaultSegmentationPath(segmentation_model_path, bundle_dir);
    bundle.note = "Fixed official bundle: qty_model_rgbdt.onnx + hist_rgbdt_bimodal.onnx.";
    return bundle;
}

BundleResolution ResolveBundle(const std::string& segmentation_model_path, const std::string& model_spec_path) {
    if (model_spec_path.empty()) {
        throw std::runtime_error("A path to the official model bundle is required.");
    }

    return ResolveFromDirectory(segmentation_model_path, fs::path(model_spec_path));
}

}  // namespace

ProviderPreference ParseProviderPreference(const std::string& raw_value) {
    const std::string normalized = ToLowerAscii(CollapseSpaces(raw_value));
    if (normalized == "nnapi") {
        return ProviderPreference::Nnapi;
    }
    if (normalized == "cpu") {
        return ProviderPreference::Cpu;
    }
    return ProviderPreference::AndroidAuto;
}

Ort::Session CreateSession(
    Ort::Env& env,
    const std::string& model_path,
    ProviderPreference provider_preference,
    std::string& provider_used) {
    if (!ExistsAndIsFile(model_path)) {
        throw std::runtime_error("ONNX model not found: " + model_path);
    }

    auto make_session = [&](bool try_nnapi) -> Ort::Session {
        Ort::SessionOptions session_options;
        session_options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_EXTENDED);
        session_options.SetIntraOpNumThreads(1);
#if defined(GRAPE_PIPELINE_HAS_NNAPI)
        if (try_nnapi) {
            OrtSessionOptionsAppendExecutionProvider_Nnapi(session_options, 0);
        }
#else
        (void)try_nnapi;
#endif
        return Ort::Session(env, model_path.c_str(), session_options);
    };

#if defined(GRAPE_PIPELINE_HAS_NNAPI)
    if (provider_preference != ProviderPreference::Cpu) {
        try {
            provider_used = "NNAPIExecutionProvider";
            return make_session(true);
        } catch (const std::exception&) {
            provider_used = "CPUExecutionProvider";
            return make_session(false);
        }
    }
#endif

    provider_used = "CPUExecutionProvider";
    return make_session(false);
}

std::vector<std::string> GetSessionInputNames(Ort::Session& session) {
    Ort::AllocatorWithDefaultOptions allocator;
    std::vector<std::string> names;
    const size_t count = session.GetInputCount();
    names.reserve(count);
    for (size_t i = 0; i < count; ++i) {
        auto name = session.GetInputNameAllocated(i, allocator);
        names.emplace_back(name.get());
    }
    return names;
}

std::vector<std::string> GetSessionOutputNames(Ort::Session& session) {
    Ort::AllocatorWithDefaultOptions allocator;
    std::vector<std::string> names;
    const size_t count = session.GetOutputCount();
    names.reserve(count);
    for (size_t i = 0; i < count; ++i) {
        auto name = session.GetOutputNameAllocated(i, allocator);
        names.emplace_back(name.get());
    }
    return names;
}

SessionMetadata InspectSession(Ort::Session& session) {
    SessionMetadata metadata;
    metadata.input_names = GetSessionInputNames(session);
    metadata.output_names = GetSessionOutputNames(session);
    if (!metadata.output_names.empty()) {
        metadata.main_output_name = metadata.output_names.front();
    }

    for (size_t i = 0; i < metadata.input_names.size(); ++i) {
        const Ort::TypeInfo type_info = session.GetInputTypeInfo(i);
        const auto tensor_info = type_info.GetTensorTypeAndShapeInfo();
        const auto shape = tensor_info.GetShape();
        const ONNXTensorElementDataType element_type = tensor_info.GetElementType();
        const std::string& input_name = metadata.input_names[i];

        if (shape.size() == 4 && metadata.x_input_name.empty()) {
            metadata.x_input_name = input_name;
            metadata.x_channels = shape.size() > 1 && shape[1] > 0 ? static_cast<int>(shape[1]) : 0;
            metadata.img_size = shape.size() > 2 && shape[2] > 0 ? static_cast<int>(shape[2]) : 0;
        } else if (element_type == ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64 && metadata.variety_input_name.empty()) {
            metadata.variety_input_name = input_name;
        } else if (element_type == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT && shape.size() == 1) {
            metadata.seg_count_input_name = input_name;
            metadata.has_seg_count_base = true;
        }
    }

    if (metadata.output_names.size() == 1) {
        const Ort::TypeInfo output_type_info = session.GetOutputTypeInfo(0);
        const auto output_tensor_info = output_type_info.GetTensorTypeAndShapeInfo();
        const auto output_shape = output_tensor_info.GetShape();
        if (!output_shape.empty() && output_shape.back() > 1) {
            metadata.output_bins = static_cast<int>(output_shape.back());
        }
    }

    if (metadata.x_input_name.empty()) {
        throw std::runtime_error("Could not identify the 'x' input tensor of the ONNX model.");
    }
    if (metadata.variety_input_name.empty()) {
        throw std::runtime_error("Could not identify the 'variety_idx' input of the ONNX model.");
    }
    if (metadata.main_output_name.empty()) {
        throw std::runtime_error("Could not identify the main output of the ONNX model.");
    }

    return metadata;
}

RuntimeModelContract BuildRuntimeModelContract(
    const SessionMetadata& hist_metadata,
    const SessionMetadata& qty_metadata) {
    if (hist_metadata.x_channels <= 0 || qty_metadata.x_channels <= 0) {
        throw std::runtime_error("Could not infer the number of channels of the model.");
    }
    if (hist_metadata.x_channels != qty_metadata.x_channels) {
        throw std::runtime_error(
            "HIST and QTY do not use the same number of channels. HIST=" +
            std::to_string(hist_metadata.x_channels) +
            " QTY=" + std::to_string(qty_metadata.x_channels));
    }
    if (hist_metadata.img_size > 0 && qty_metadata.img_size > 0 && hist_metadata.img_size != qty_metadata.img_size) {
        throw std::runtime_error(
            "HIST and QTY do not use the same image size. HIST=" +
            std::to_string(hist_metadata.img_size) +
            " QTY=" + std::to_string(qty_metadata.img_size));
    }

    RuntimeModelContract contract;
    contract.img_size = hist_metadata.img_size > 0 ? hist_metadata.img_size : qty_metadata.img_size;
    contract.input_channels = hist_metadata.x_channels;
    contract.output_bins = hist_metadata.output_bins > 0 ? hist_metadata.output_bins : static_cast<int>(kBins.size());
    contract.uses_seg_count_base = qty_metadata.has_seg_count_base;
    if (contract.input_channels != kOfficialInputChannels) {
        throw std::runtime_error(
            "The official runtime only accepts 5-channel RGBDT models. Channels detected=" +
            std::to_string(contract.input_channels));
    }
    if (contract.output_bins != kOfficialOutputBins) {
        throw std::runtime_error(
            "The official HIST must expose 26 bins. Bins detected=" +
            std::to_string(contract.output_bins));
    }
    contract.input_mode = "rgbdt";

    contract.hist_x_input_name = hist_metadata.x_input_name;
    contract.hist_variety_input_name = hist_metadata.variety_input_name;
    contract.hist_output_name = hist_metadata.main_output_name;

    contract.qty_x_input_name = qty_metadata.x_input_name;
    contract.qty_variety_input_name = qty_metadata.variety_input_name;
    contract.qty_seg_count_input_name = qty_metadata.seg_count_input_name;
    contract.qty_output_name = qty_metadata.main_output_name;

    return contract;
}

BundleResolution ResolveBundle(
    const std::string& segmentation_model_path,
    const std::string& model_spec_path) {
    if (model_spec_path.empty()) {
        throw std::runtime_error("The official model bundle path is required.");
    }

    return ResolveFromDirectory(segmentation_model_path, fs::path(model_spec_path));
}

}  // namespace grape
