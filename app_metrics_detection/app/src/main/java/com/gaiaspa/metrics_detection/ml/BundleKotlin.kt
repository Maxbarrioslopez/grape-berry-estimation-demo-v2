package com.gaiaspa.metrics_detection.ml

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset

data class BundleKotlin(
    val scalerKind: String,
    val scaler: JSONObject,
    val model: JSONObject
)

object BundleLoader {

    fun loadFromAssets(context: Context, assetPath: String): BundleKotlin {
        val jsonStr = context.assets.open(assetPath).use { input ->
            input.readBytes().toString(Charset.forName("UTF-8"))
        }
        val root = JSONObject(jsonStr)
        return BundleKotlin(
            scalerKind = root.getString("scaler_kind"),
            scaler = root.getJSONObject("scaler"),
            model = root.getJSONObject("model")
        )
    }

    /** Reads a JSONArray that can be [1,2,3] or [[1],[2],[3]] or [[...],[...]] and flattens it. */
    fun parse1DFloatArray(any: Any?): FloatArray {
        if (any == null) return FloatArray(0)

        return when (any) {
            is JSONArray -> {
                if (any.length() == 0) return FloatArray(0)

                // If the first element is JSONArray => 2D or more: flatten
                val first = any.opt(0)
                if (first is JSONArray) {
                    val out = ArrayList<Float>(any.length() * (first.length().coerceAtLeast(1)))
                    for (i in 0 until any.length()) {
                        val row = any.get(i)
                        if (row is JSONArray) {
                            for (j in 0 until row.length()) out.add(row.getDouble(j).toFloat())
                        } else {
                            out.add((row as Number).toFloat())
                        }
                    }
                    out.toFloatArray()
                } else {
                    // 1D normal
                    FloatArray(any.length()) { i -> any.getDouble(i).toFloat() }
                }
            }
            else -> FloatArray(0)
        }
    }

    fun parse2DFloatArray(any: Any?): Array<FloatArray> {
        if (any !is JSONArray) return emptyArray()
        if (any.length() == 0) return emptyArray()

        val first = any.opt(0)
        if (first !is JSONArray) {
            // If it comes as 1D, treat it as 1 row
            return arrayOf(parse1DFloatArray(any))
        }

        return Array(any.length()) { r ->
            val row = any.getJSONArray(r)
            FloatArray(row.length()) { c -> row.getDouble(c).toFloat() }
        }
    }
}
