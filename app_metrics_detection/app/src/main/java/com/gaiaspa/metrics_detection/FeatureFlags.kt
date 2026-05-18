package com.gaiaspa.metrics_detection

/**
 * Centralised compile-time feature toggles for the metrics-detection application.
 *
 * All flags are `const val` and compiled into the APK — changing a value
 * requires a rebuild. Features gated behind `false` at compile time are
 * stripped by the Kotlin compiler's dead-code elimination when used in
 * `if` expressions with constant conditions.
 *
 * Currently active features:
 * - [multiViewFusionEnabled]: Enables multi-view fusion logic in the pipeline.
 *
 * Features on the roadmap (currently disabled):
 * - Language switching between ES/EN via [LANGUAGE_SELECTOR_ENABLED].
 * - Screen-orientation toggle request.
 */
object FeatureFlags {
    /** Enables multi-view fusion in the inference pipeline. Always on in production. */
    const val multiViewFusionEnabled: Boolean = true
    /** Work-in-progress: language switch between Spanish and English. */
    const val FEATURE_LANGUAGE_SWITCH: Boolean = false
    /** Work-in-progress: toggle between portrait and landscape orientation. */
    const val FEATURE_SCREEN_ROTATION_TOGGLE: Boolean = false

    /** Alias for [FEATURE_LANGUAGE_SWITCH]; used by the settings UI. */
    const val LANGUAGE_SELECTOR_ENABLED: Boolean = FEATURE_LANGUAGE_SWITCH
}
