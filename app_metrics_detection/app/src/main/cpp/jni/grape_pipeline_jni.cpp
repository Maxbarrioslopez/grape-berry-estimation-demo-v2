#include <android/log.h>
#include <jni.h>

#include <chrono>
#include <memory>
#include <mutex>
#include <string>

#include "grape_pipeline_core.hpp"
#include "grape_pipeline_onnx.hpp"
#include "grape_pipeline_postprocess.hpp"

namespace {

constexpr const char* kLogTag = "GrapePipeline";

#define GP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, kLogTag, __VA_ARGS__)
#define GP_LOGW(...) __android_log_print(ANDROID_LOG_WARN, kLogTag, __VA_ARGS__)
#define GP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kLogTag, __VA_ARGS__)

using Clock = std::chrono::high_resolution_clock;

struct NativeContext {
    Ort::Env env;
    std::unique_ptr<Ort::Session> seg_session;
    std::unique_ptr<Ort::Session> qty_session;
    std::unique_ptr<Ort::Session> hist_session;

    grape::SessionMetadata qty_metadata;
    grape::SessionMetadata hist_metadata;
    grape::RuntimeModelContract contract;
    grape::BundleResolution bundle;

    std::string seg_provider;
    std::string qty_provider;
    std::string hist_provider;
    std::string provider_key;

    std::mutex mutex;

    NativeContext() : env(ORT_LOGGING_LEVEL_WARNING, "grape_pipeline_android") {}
};

NativeContext& GetContext() {
    static NativeContext context;
    return context;
}

std::string JStringToStd(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return std::string();
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return std::string();
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

void ResetSessions(NativeContext& context) {
    context.seg_session.reset();
    context.qty_session.reset();
    context.hist_session.reset();
    context.qty_metadata = grape::SessionMetadata {};
    context.hist_metadata = grape::SessionMetadata {};
    context.contract = grape::RuntimeModelContract {};
    context.bundle = grape::BundleResolution {};
    context.seg_provider.clear();
    context.qty_provider.clear();
    context.hist_provider.clear();
    context.provider_key.clear();
}

void EnsureSessionsLoaded(
    NativeContext& context,
    const grape::BundleResolution& bundle,
    grape::ProviderPreference provider_preference,
    bool allow_synthetic_rgbdt) {
    const std::string provider_key = std::to_string(static_cast<int>(provider_preference));
    const bool reload_qty = !context.qty_session
        || context.bundle.qty_path != bundle.qty_path
        || context.provider_key != provider_key;
    const bool reload_hist = !context.hist_session
        || context.bundle.hist_path != bundle.hist_path
        || context.provider_key != provider_key;

    if (reload_qty) {
        context.qty_session = std::make_unique<Ort::Session>(
            grape::CreateSession(context.env, bundle.qty_path, provider_preference, context.qty_provider));
        context.qty_metadata = grape::InspectSession(*context.qty_session);
        GP_LOGI("QTY loaded: %s", bundle.qty_path.c_str());
    }

    if (reload_hist) {
        context.hist_session = std::make_unique<Ort::Session>(
            grape::CreateSession(context.env, bundle.hist_path, provider_preference, context.hist_provider));
        context.hist_metadata = grape::InspectSession(*context.hist_session);
        GP_LOGI("HIST loaded: %s", bundle.hist_path.c_str());
    }

    context.contract = grape::BuildRuntimeModelContract(context.hist_metadata, context.qty_metadata);

    if (!bundle.segmentation_path.empty()) {
        const bool reload_seg = !context.seg_session
            || context.bundle.segmentation_path != bundle.segmentation_path
            || context.provider_key != provider_key;
        if (reload_seg) {
            try {
                context.seg_session = std::make_unique<Ort::Session>(
                    grape::CreateSession(
                        context.env,
                        bundle.segmentation_path,
                        provider_preference,
                        context.seg_provider));
                GP_LOGI("SEG loaded: %s", bundle.segmentation_path.c_str());
            } catch (const std::exception& exc) {
                if (allow_synthetic_rgbdt) {
                    context.seg_session.reset();
                    context.seg_provider = "synthetic_fallback";
                    GP_LOGW("SEG unavailable, using synthetic fallback: %s", exc.what());
                } else {
                    throw;
                }
            }
        }
    } else {
        context.seg_session.reset();
        context.seg_provider = allow_synthetic_rgbdt ? "synthetic_fallback" : "not_configured";
    }

    context.bundle = bundle;
    context.provider_key = provider_key;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_gaiaspa_metrics_1detection_ml_CppPipelineBridge_nativeRunPipeline(
    JNIEnv* env,
    jobject,
    jstring imagePath,
    jstring segModelPath,
    jstring regModelPath,
    jint varietyId,
    jstring providerPreference,
    jboolean saveDebugArtifacts,
    jboolean allowSyntheticRgbdt) {
    auto& context = GetContext();
    std::lock_guard<std::mutex> lock(context.mutex);

    try {
        const std::string image_path = JStringToStd(env, imagePath);
        const std::string segmentation_model_path = JStringToStd(env, segModelPath);

        // JNI compatibility with the old bridge:
        // regModelPath is now treated as the official model bundle path.
        // It can point to the bundle directory or to one of the official ONNX files.
        const std::string model_spec_path = JStringToStd(env, regModelPath);
        const std::string provider_raw = JStringToStd(env, providerPreference);
        const grape::ProviderPreference provider = grape::ParseProviderPreference(provider_raw);
        const bool save_debug = saveDebugArtifacts == JNI_TRUE;
        const bool allow_synthetic = allowSyntheticRgbdt == JNI_TRUE;

        const grape::BundleResolution bundle = grape::ResolveBundle(
            segmentation_model_path,
            model_spec_path);
        GP_LOGI("Bundle note: %s", bundle.note.c_str());

        EnsureSessionsLoaded(context, bundle, provider, allow_synthetic);

        grape::GrapePipelineCore core(
            context.seg_session.get(),
            *context.qty_session,
            *context.hist_session,
            context.contract,
            context.bundle,
            context.seg_provider,
            context.qty_provider,
            context.hist_provider);

        const auto run_t0 = Clock::now();
        grape::PipelineResult result = core.Run(
            image_path,
            static_cast<int>(varietyId),
            save_debug,
            allow_synthetic);
        const auto run_t1 = Clock::now();

        if (result.timing.total_ms <= 0.0) {
            result.timing.total_ms =
                std::chrono::duration<double, std::milli>(run_t1 - run_t0).count();
        }

        const std::string json = grape::PipelineResultToJson(result);
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& exc) {
        GP_LOGE("nativeRunPipeline failed: %s", exc.what());
        const std::string error_json = grape::ErrorToJson(exc.what());
        return env->NewStringUTF(error_json.c_str());
    } catch (...) {
        GP_LOGE("nativeRunPipeline failed with unknown exception");
        const std::string error_json = grape::ErrorToJson("Unknown native exception.");
        return env->NewStringUTF(error_json.c_str());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_gaiaspa_metrics_1detection_ml_CppPipelineBridge_nativeRelease(
    JNIEnv*,
    jobject) {
    auto& context = GetContext();
    std::lock_guard<std::mutex> lock(context.mutex);
    ResetSessions(context);
    GP_LOGI("Native sessions released.");
}
