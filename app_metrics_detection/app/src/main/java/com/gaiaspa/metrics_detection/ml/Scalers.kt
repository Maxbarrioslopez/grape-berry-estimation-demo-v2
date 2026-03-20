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

    // Para robust/power
    private var centerX: FloatArray = floatArrayOf()
    private var scaleX: FloatArray = floatArrayOf()

    // Para power (Yeo-Johnson) + standard scaler interno
    private var lambdas: FloatArray = floatArrayOf()
    private var mean: FloatArray = floatArrayOf()
    private var std: FloatArray = floatArrayOf()

    // Para quantile
    private var quantileX: Array<FloatArray> = emptyArray() // por feature: valores X
    private var quantileQ: Array<FloatArray> = emptyArray() // por feature: refs Q (mismo largo)

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
                // quantile_x: lista de arrays por feature
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

    /** Aplica scaler X según kind */
    fun scaleX(x: FloatArray): FloatArray {
        return when (kind) {
            "robust" -> robustScale(x, centerX, scaleX)
            "power"  -> powerScale(x, lambdas, mean, std)
            "quantile" -> quantileTransform(x, quantileX, quantileQ)
            else -> x
        }
    }

    /**
     * Si tu modelo devuelve y ya en escala final, esto puede ser identidad.
     * Si tu bundle tiene scaler para Y, aquí aplicas el inverso correspondiente.
     *
     * Por tu código: scaler.inverseScaleY(yPred).
     * Si tu "regresor.tflite" ya devuelve en bins/espacio final -> devuelve igual.
     *
     * Si NO, ajusta acá con el inverso real que uses en Python.
     */
    fun inverseScaleY(y: FloatArray): FloatArray {
        // <- Deja identity si tu TFLite ya está “desescalado”.
        return y
    }

    // -------------------------
    // Implementaciones
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
        // Asume Yeo-Johnson por feature y luego standardize: (t - mean)/std
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
        // Mapea por feature interpolando entre quantiles_x (valores) y quantiles_q (refs 0..1)
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
