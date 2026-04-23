#pragma once

#include "grape_pipeline_types.hpp"

#include <string>
#include <vector>

namespace grape {

float ComputeHistogramMean(const std::vector<float>& hist_prob, const std::vector<int>& bins);
float ComputeHistogramMode(const std::vector<float>& count_pred_by_bin, const std::vector<int>& bins);
float ComputeHistogramStd(const std::vector<float>& hist_prob, float mean, const std::vector<int>& bins);

std::vector<float> NormalizeHistogram(const std::vector<float>& hist_prob);
std::vector<int> HistogramToIntegers(const std::vector<float>& count_pred_by_bin, float total_count);

DebugArtifacts BuildDefaultDebugArtifacts(const std::string& image_path, bool enabled);
void SaveDebugArtifacts(const PipelineInputs& inputs, PipelineResult& result);

/**
 * Genera el overlay visual final del usuario sobre una imagen base.
 * @param path Ruta del archivo base (se sobreescribirá con el overlay).
 * @param inputs Entradas del pipeline (contiene máscaras y originales).
 * @param result Resultados del pipeline (contiene detecciones).
 */
void SaveVisualOverlay(const std::string& path, const PipelineInputs& inputs, const PipelineResult& result);

std::string PipelineResultToJson(const PipelineResult& result);
std::string ErrorToJson(const std::string& error_message);

}  // namespace grape
