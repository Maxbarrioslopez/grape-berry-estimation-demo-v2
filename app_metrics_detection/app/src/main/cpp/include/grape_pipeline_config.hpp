#pragma once

#include "grape_pipeline_types.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <filesystem>
#include <map>
#include <sstream>
#include <string>
#include <vector>

namespace grape {

inline constexpr int kDefaultImgSize = 512;
inline constexpr float kDefaultSegConfThreshold = 0.25f;
inline constexpr float kDefaultSegMaskThreshold = 0.50f;
inline constexpr int kCaliberMinMm = 7;
inline constexpr int kCaliberMaxMm = 32;

inline const std::array<int, 26> kBins = {
    7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
    20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
};

inline const std::map<int, std::string> kLegacySegmentationClassNames = {
    {0, "bunch_black"},
    {1, "bunch_green"},
    {2, "bunch_red"},
    {3, "grape_black"},
    {4, "grape_green"},
    {5, "grape_red"},
    {6, "pingpong"},
};

inline const std::vector<std::string> kDefaultVarietyClasses = {
    "ALLISON", "AUTUMN CRISP", "CRIMSON", "IVORY", "MAGENTA", "RED GLOBE",
    "SCARLOTTA", "SUPERIOR", "SWEET GLOBE", "THOMPSON", "TIMCO", "TIMPSON",
};

// ✅ SINCRONIZADO: Nombres exactos encontrados en assets/weights/modelos
inline constexpr const char* kOfficialHistModel = "hist_rgbdt_bimodal.onnx";
inline constexpr const char* kOfficialQtyModel = "qty_model_rgbdt.onnx";

inline constexpr int kOfficialInputChannels = 5;
inline constexpr int kOfficialOutputBins = 26;
inline constexpr const char* kDebugDirSuffix = "_cpp_pipeline_v2";

inline std::vector<int> DefaultBinsVector() {
    return std::vector<int>(kBins.begin(), kBins.end());
}

inline std::string ToUpperAscii(std::string value) {
    for (char& ch : value) {
        ch = static_cast<char>(std::toupper(static_cast<unsigned char>(ch)));
    }
    return value;
}

inline std::string CollapseSpaces(const std::string& value) {
    std::ostringstream oss;
    bool last_was_space = false;
    for (char ch : value) {
        const bool is_space = std::isspace(static_cast<unsigned char>(ch)) != 0;
        if (is_space) {
            if (!last_was_space) oss << ' ';
        } else {
            oss << ch;
        }
        last_was_space = is_space;
    }
    std::string out = oss.str();
    if (!out.empty() && out.front() == ' ') out.erase(out.begin());
    if (!out.empty() && out.back() == ' ') out.pop_back();
    return out;
}

inline std::string NormalizeVariety(std::string value) {
    value = CollapseSpaces(ToUpperAscii(std::move(value)));
    if (value == "AUTUM CRISP") return "AUTUMN CRISP";
    if (value == "RED GLOVE") return "RED GLOBE";
    if (value == "TINCO") return "TIMCO";
    if (value == "SWEETGLOBE") return "SWEET GLOBE";
    return value;
}

inline std::string InferVarietyFromFilename(
    const std::string& image_path,
    const std::vector<std::string>& variety_classes = kDefaultVarietyClasses) {
    const std::string file_name = ToUpperAscii(std::filesystem::path(image_path).filename().string());
    for (const std::string& variety : variety_classes) {
        if (!variety.empty() && file_name.find(variety) != std::string::npos) return variety;
    }
    return variety_classes.empty() ? std::string("ALLISON") : variety_classes.front();
}

inline int64_t ResolveVarietyIndex(
    const std::string& variety_name,
    const std::vector<std::string>& variety_classes = kDefaultVarietyClasses) {
    const std::string normalized = NormalizeVariety(variety_name);
    for (size_t i = 0; i < variety_classes.size(); ++i) {
        if (NormalizeVariety(variety_classes[i]) == normalized) return static_cast<int64_t>(i);
    }
    return 0;
}

inline std::string ResolveVarietyName(
    int variety_id,
    const std::string& image_path,
    const std::vector<std::string>& variety_classes = kDefaultVarietyClasses) {
    if (variety_id >= 0 && variety_id < static_cast<int>(variety_classes.size())) {
        return NormalizeVariety(variety_classes[static_cast<size_t>(variety_id)]);
    }
    return NormalizeVariety(InferVarietyFromFilename(image_path, variety_classes));
}

// ===========================================================================
// VISUAL-ONLY OVERLAY CONSTANTS (no afectan el pipeline productivo)
// ===========================================================================
namespace overlay_visual {

// --- Espesores de contorno (dinamicos, basados en ancho de imagen) ---
inline constexpr float kContourWidthFactorBunchGrape = 300.0f;
inline constexpr float kContourWidthFactorPingpong    = 420.0f;
inline constexpr int   kContourThickMinBunchGrape = 3;
inline constexpr int   kContourThickMaxBunchGrape = 5;
inline constexpr int   kContourThickMinPingpong    = 2;
inline constexpr int   kContourThickMaxPingpong    = 4;

// --- Alpha de relleno ---
inline constexpr double kFillAlphaBunchGrape = 0.10;
inline constexpr double kFillAlphaPingpong    = 0.25;

// --- Colores (BGR) ---
inline const cv::Scalar kColorFillBunchGrape(255, 255, 0);
inline const cv::Scalar kColorContourBunchGrape(255, 255, 0);
inline const cv::Scalar kColorFillPingpong(0, 220, 255);
inline const cv::Scalar kColorContourPingpong(0, 255, 255);
inline const cv::Scalar kColorCentroidBunchGrape(0, 80, 255);
inline const cv::Scalar kColorCentroidPingpong(255, 80, 0);
inline const cv::Scalar kColorGrapeDot(0, 80, 255);
inline const cv::Scalar kColorAuxiliaryDot(255, 0, 180);

// --- Radios de centroide (dinamicos) ---
inline constexpr float kCentroidRadiusFactorGrape    = 350.0f;
inline constexpr float kCentroidRadiusFactorPingpong = 320.0f;
inline constexpr float kCentroidRadiusFactorAux      = 380.0f;
inline constexpr int   kCentroidRadiusMinGrape    = 2;
inline constexpr int   kCentroidRadiusMaxGrape    = 4;
inline constexpr int   kCentroidRadiusMinPingpong = 3;
inline constexpr int   kCentroidRadiusMaxPingpong = 5;

// --- Puntos auxiliares: distance transform + maximos locales + NMS ---
inline constexpr float kAuxDtMinRadius = 3.0f;
inline constexpr float kAuxDtMaxRadius = 30.0f;

// Ventana de maximo local (dilate kernel)
inline constexpr int   kAuxWindowMin    = 9;
inline constexpr int   kAuxWindowMax    = 23;
inline constexpr float kAuxWindowFactor = 130.0f;

// Umbral de peak (descarta maximos debiles)
inline constexpr float kAuxPeakMinAbs    = 2.5f;
inline constexpr float kAuxPeakMinFactor = 520.0f;

// Refinamiento de centro: ventana local para buscar max DT
inline constexpr int kAuxRefineHalfWin = 2;

// NMS: distancia minima entre puntos auxiliares
inline constexpr int   kAuxNmsDistMin    = 9;
inline constexpr float kAuxNmsDistFactor = 145.0f;

// Distancia minima a puntos oficiales
inline constexpr int   kAuxOffDistMin    = 8;
inline constexpr float kAuxOffDistFactor = 150.0f;

// Limite maximo de puntos auxiliares
inline constexpr int   kAuxMaxPointsMin    = 20;
inline constexpr int   kAuxMaxPointsMax    = 70;
inline constexpr float kAuxMaxPointsFactor = 45.0f;

// --- Post-procesado de mascara ---
inline constexpr int    kMorphKernelSize         = 3;
inline constexpr int    kBlurKernelSize          = 3;
inline constexpr double kBinaryThreshold         = 127.0;
inline constexpr double kSoftThreshold           = 96.0;
inline constexpr double kBinaryMaxVal            = 255.0;

// --- Simplificacion de contorno ---
inline constexpr double kApproxPolyEpsilonFactor = 0.001;

// --- Separacion diagnostica ---
inline constexpr int kDiagLogInterval = 0;  // >0  para log cada N imagenes

}  // namespace overlay_visual

}  // namespace grape
