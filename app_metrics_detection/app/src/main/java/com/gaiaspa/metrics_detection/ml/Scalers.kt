package com.gaiaspa.metrics_detection.ml

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign

class Scalers {

    private var kind: String = ""
    private var sp: JSONObject? = null

    // For robust/power
    private var centerX: FloatArray = floatArrayOf()
    private var scaleX: FloatArray = floatArrayOf()

    // For power (Yeo-Johnson) + internal standard scaler
    private var lambdas: FloatArray = floatArrayOf()
    private var mean: FloatArray = floatArrayOf()
    private var std: FloatArray = floatArrayOf()

    // For quantile
    private var quantileX: Array<FloatArray> = emptyArray() // per feature: X values
    private var quantileQ: Array<FloatArray> = emptyArray() // per feature: Q refs (same length)

    fun loadFromBundle(context: Context, assetBundleJson: String) {
        val bundle = BundleLoader.loadFromAssets(context, assetBundleJson)
        kind = bundle.scalerKind
        sp = bundle.scaler

        when (kind) {
            "robust" -> {
                centerX = BundleLoader.parse1DFloatArray(sp!!.getJSONArray("robust_center"))
                scaleX  = BundleLoader.parse1DFloatArray(sp!!.getJSONArray("robust_scale"))
            }

            "power" -> {
                lambdas = BundleLoader.parse1DFloatArray(sp!!.getJSONArray("power_lambdas"))
                mean    = BundleLoader.parse1DFloatArray(sp!!.getJSONArray("power_mean"))
                std     = BundleLoader.parse1DFloatArray(sp!!.getJSONArray("power_scale"))
            }

            "quantile" -> {
                // quantile_x: list of arrays per feature
                val qx = sp!!.getJSONArray("quantile_x")
                val qq = sp!!.getJSONArray("quantile_q")

                quantileX = Array(qx.length()) { i ->
                    BundleLoader.parse1DFloatArray(qx.getJSONArray(i))
                }
                quantileQ = Array(qq.length()) { i ->
                    BundleLoader.parse1DFloatArray(qq.getJSONArray(i))
                }
            }

            else -> throw IllegalArgumentException("Unknown scaler kind: $kind")
        }
    }

    /** Applies scaler X according to kind */
    fun scaleX(x: FloatArray): FloatArray {
        return when (kind) {
            "robust" -> robustScale(x, centerX, scaleX)
            "power"  -> powerScale(x, lambdas, mean, std)
            "quantile" -> quantileTransform(x, quantileX, quantileQ)
            else -> x
        }
    }

    /**
     * If your model returns y already in final scale, this can be identity.
     * If your bundle has a scaler for Y, apply the corresponding inverse here.
     *
     * From your code: scaler.inverseScaleY(yPred).
     * If your "regresor.tflite" already returns in bins/final space -> return as-is.
     *
     * If NOT, adjust here with the actual inverse used in Python.
     */
    fun inverseScaleY(y: FloatArray): FloatArray {
        // <- Leave identity if your TFLite is already "descaled".
        return y
    }

    // -------------------------
    // Implementations
    // -------------------------
    private fun robustScale(x: FloatArray, center: FloatArray, scale: FloatArray): FloatArray {
        val out = FloatArray(x.size)
        for (i in x.indices) {
            val c = center.getOrNull(i) ?: 0f
            val s = scale.getOrNull(i) ?: 1f
            out[i] = (x[i] - c) / (if (s == 0f) 1f else s)
        }
        return out
    }

    private fun powerScale(x: FloatArray, lambdas: FloatArray, mean: FloatArray, std: FloatArray): FloatArray {
        // Assumes Yeo-Johnson per feature and then standardize: (t - mean)/std
        val out = FloatArray(x.size)
        for (i in x.indices) {
            val lam = lambdas.getOrNull(i) ?: 1f
            val m = mean.getOrNull(i) ?: 0f
            val s = std.getOrNull(i) ?: 1f

            val t = yeoJohnson(x[i], lam)
            out[i] = (t - m) / (if (s == 0f) 1f else s)
        }
        return out
    }

    private fun yeoJohnson(x: Float, lambda: Float): Float {
        // Yeo-Johnson (sklearn PowerTransformer)
        return if (x >= 0f) {
            if (lambda == 0f) ln(x + 1f) else ((x + 1f).pow(lambda) - 1f) / lambda
        } else {
            // x < 0
            if (lambda == 2f) -ln(1f - x) else -(((1f - x).pow(2f - lambda) - 1f) / (2f - lambda))
        }
    }

    private fun quantileTransform(
        x: FloatArray,
        qx: Array<FloatArray>,
        qq: Array<FloatArray>
    ): FloatArray {
        // Maps per feature by interpolating between quantiles_x (values) and quantiles_q (refs 0..1)
        val out = FloatArray(x.size)
        for (i in x.indices) {
            val xs = qx.getOrNull(i) ?: floatArrayOf()
            val qs = qq.getOrNull(i) ?: floatArrayOf()
            if (xs.isEmpty() || qs.isEmpty()) {
                out[i] = x[i]
            } else {
                out[i] = interp1d(xs, qs, x[i])
            }
        }
        return out
    }

    private fun interp1d(xs: FloatArray, ys: FloatArray, x: Float): Float {
        // clamp
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()

        // find interval
        var lo = 0
        var hi = xs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (x >= xs[mid]) lo = mid else hi = mid
        }
        val x0 = xs[lo]
        val x1 = xs[hi]
        val y0 = ys[lo]
        val y1 = ys[hi]
        val t = (x - x0) / max(1e-12f, (x1 - x0))
        return y0 + t * (y1 - y0)
    }
}
