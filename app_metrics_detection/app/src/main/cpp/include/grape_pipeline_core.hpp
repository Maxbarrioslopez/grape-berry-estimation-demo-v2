#pragma once

#include "grape_pipeline_types.hpp"

#include <onnxruntime_cxx_api.h>

#include <string>
#include <vector>

namespace grape {

class GrapePipelineCore {
public:
    GrapePipelineCore(
        Ort::Session* segmentation_session,
        Ort::Session& qty_session,
        Ort::Session& hist_session,
        RuntimeModelContract contract,
        BundleResolution bundle,
        std::string seg_provider,
        std::string qty_provider,
        std::string hist_provider);

    PipelineResult Run(
        const std::string& image_path,
        int variety_id,
        bool save_debug_artifacts,
        bool allow_synthetic_rgbdt,
        const std::string& visual_overlay_path = "") const;

private:
    Ort::Session* segmentation_session_;
    Ort::Session& qty_session_;
    Ort::Session& hist_session_;
    RuntimeModelContract contract_;
    BundleResolution bundle_;
    std::string seg_provider_;
    std::string qty_provider_;
    std::string hist_provider_;
};

}  // namespace grape
