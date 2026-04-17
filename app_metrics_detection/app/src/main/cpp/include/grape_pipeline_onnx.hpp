#pragma once

#include "grape_pipeline_types.hpp"

#include <onnxruntime_cxx_api.h>

#include <string>
#include <vector>

namespace grape {

// Contrato ONNX oficial del runtime nuevo:
// - QTY RGBDT: x[1,5,512,512] float + variety_idx[1] int64 + seg_count_base[1] float -> count_total[1] float
// - HIST RGBDT: x[1,5,512,512] float + variety_idx[1] int64 -> hist_probs[1,26] float
//
// El runtime Android nuevo reutiliza el segmentador legacy como proveedor de:
// - seg_count_base
// - DT de uvas
// - DT de pingpong
//
// y deja fuera el unified_runtime.onnx antiguo.

ProviderPreference ParseProviderPreference(const std::string& raw_value);

Ort::Session CreateSession(
    Ort::Env& env,
    const std::string& model_path,
    ProviderPreference provider_preference,
    std::string& provider_used);

std::vector<std::string> GetSessionInputNames(Ort::Session& session);
std::vector<std::string> GetSessionOutputNames(Ort::Session& session);

SessionMetadata InspectSession(Ort::Session& session);
RuntimeModelContract BuildRuntimeModelContract(
    const SessionMetadata& hist_metadata,
    const SessionMetadata& qty_metadata);

BundleResolution ResolveBundle(
    const std::string& segmentation_model_path,
    const std::string& model_spec_path);

}  // namespace grape
