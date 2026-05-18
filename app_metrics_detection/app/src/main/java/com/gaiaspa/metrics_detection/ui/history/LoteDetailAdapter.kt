package com.gaiaspa.metrics_detection.ui.history

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.data.model.CalPredict
import com.gaiaspa.metrics_detection.data.model.Lote
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import java.io.File

/**
 * LoteDetailAdapter - v9.0 ARCHITECTURAL FIX
 * 1. Usa upload_512 como fuente persistente y liviana.
 * 2. Manejo defensivo de errores para evitar crashes.
 * 3. Paso de rutas (String) en lugar de Bitmaps pesados.
 */
class LoteDetailAdapter(
    private val lote: Lote,
    private val onImageClick: (String) -> Unit // ✅ Cambiado a String para evitar pasar Bitmaps pesados
) : RecyclerView.Adapter<LoteDetailAdapter.LoteDetailViewHolder>() {

    private val isFusedMultiView: Boolean =
        lote.calPredicts.isNotEmpty() && lote.images.size == lote.calPredicts.size * 2

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LoteDetailViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image_prediction_detail, parent, false)
        return LoteDetailViewHolder(view, onImageClick)
    }

    override fun onBindViewHolder(holder: LoteDetailViewHolder, position: Int) {
        val imagePath = if (isFusedMultiView) {
            lote.representativeImagePathForPrediction(position).orEmpty()
        } else {
            lote.images.getOrNull(position).orEmpty()
        }
        val prediction = lote.calPredicts.getOrNull(position)
        holder.bind(position, imagePath, prediction)
    }

    override fun getItemCount(): Int =
        if (isFusedMultiView) lote.calPredicts.size else lote.images.size

    class LoteDetailViewHolder(
        itemView: View,
        private val onImageClick: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val ivPhoto: ImageView = itemView.findViewById(R.id.ivPhoto)
        private val tvPredictionInfo: TextView = itemView.findViewById(R.id.tvPredictionInfo)
        private val barChart: BarChart = itemView.findViewById(R.id.barChart)

        fun bind(position: Int, imagePath: String, prediction: CalPredict?) {
            itemView.findViewById<TextView>(R.id.tvIndex)?.text = "#${position + 1}"
            Log.d("HISTORY_IMG_BIND", "Detail bind pos=$position imagePath='$imagePath'")

            val bitmap = decodeBitmap(imagePath)
            if (bitmap != null && !bitmap.isRecycled) {
                Log.d("HISTORY_IMG_BIND", "Detail bind pos=$position: OK")
                ivPhoto.clearColorFilter()
                ivPhoto.setImageBitmap(bitmap)
                ivPhoto.setOnClickListener { onImageClick(imagePath) }
            } else {
                Log.w("HISTORY_IMG_BIND", "Detail bind pos=$position: FAILED")
                ivPhoto.setImageResource(R.drawable.ic_gallery)
                ivPhoto.setOnClickListener(null)
            }

            // Mostrar datos de predicción
            if(prediction?.status == false) {
                tvPredictionInfo.text = prediction.error
                barChart.clear()
            } else if (prediction?.status == true) {
                val predInfo = """
                    ${itemView.context.getString(R.string.color)}: ${prediction.bunchColor}
                    ${itemView.context.getString(R.string.qty)}: ${prediction.qty}
                    ${itemView.context.getString(R.string.mean)}: ${prediction.mean} mm
                    ${itemView.context.getString(R.string.mode)}: ${prediction.mode} mm
                    ${itemView.context.getString(R.string.std)}: ${prediction.std}
                """.trimIndent()
                tvPredictionInfo.text = predInfo
                setupChart(barChart, prediction.bins, prediction.pred)
            } else {
                tvPredictionInfo.text = itemView.context.getString(R.string.no_prediction)
                barChart.clear()
            }
        }

        private fun setupChart(barChart: BarChart, bins: List<Float>, values: List<Int>) {
            if (bins.isEmpty() || values.isEmpty()) { barChart.clear(); return }
            val entries = values.mapIndexed { i, v -> BarEntry(i.toFloat(), v.toFloat()) }
            val barDataSet = BarDataSet(entries, "")
            val primaryColor = ContextCompat.getColor(barChart.context, R.color.colorPrimary)
            barDataSet.color = primaryColor
            barDataSet.valueTextSize = 10f
            barDataSet.valueTextColor = primaryColor
            barDataSet.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = value.toInt().toString()
            }

            val barData = BarData(barDataSet).apply {
                barWidth = 0.7f
            }

            with(barChart.xAxis) {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawAxisLine(true)
                setDrawGridLines(false)
                textColor = ContextCompat.getColor(barChart.context, R.color.textSecondary)
                textSize = 10f
                granularity = 1f
                labelRotationAngle = -45f
                valueFormatter = IndexAxisValueFormatter(bins.map { String.format("%.1f", it) })
            }

            barChart.axisLeft.apply {
                setDrawAxisLine(false)
                setDrawGridLines(true)
                gridColor = ContextCompat.getColor(barChart.context, R.color.outlineGray)
                textColor = ContextCompat.getColor(barChart.context, R.color.textSecondary)
                textSize = 10f
                axisMinimum = 0f
            }
            barChart.axisRight.isEnabled = false

            barChart.apply {
                setPinchZoom(false)
                isDoubleTapToZoomEnabled = false
                setScaleEnabled(false)
                isDragEnabled = false
                description.isEnabled = false
                legend.isEnabled = false
                setDrawBorders(false)
                setDrawGridBackground(false)
                setExtraOffsets(4f, 4f, 4f, 4f)
                data = barData
                setFitBars(true)
                animateY(500)
                invalidate()
            }
        }

        private fun decodeBitmap(path: String): Bitmap? = runCatching {
            if (path.isBlank()) {
                Log.v("HISTORY_IMG_BIND", "decodeBitmap: path is blank")
                return@runCatching null
            }
            Log.d("HISTORY_IMG_BIND", "decodeBitmap: trying path='$path'")

            return@runCatching when {
                path.startsWith("content://") -> {
                    val uri = Uri.parse(path)
                    itemView.context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
                else -> {
                    val cleanPath = path.replace("file://", "")
                    val file = File(cleanPath)
                    if (!file.exists() || file.length() <= 0) {
                        Log.w("HISTORY_IMG_BIND", "decodeBitmap: file NOT FOUND at '$cleanPath'")
                        return@runCatching null
                    }
                    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(cleanPath, probe)
                    if (probe.outWidth <= 0 || probe.outHeight <= 0) {
                        Log.w("HISTORY_IMG_BIND", "decodeBitmap: bounds invalid for '$cleanPath'")
                        return@runCatching null
                    }
                    var sample = 1
                    while (probe.outWidth / sample > 512 || probe.outHeight / sample > 512) {
                        sample *= 2
                    }
                    BitmapFactory.decodeFile(cleanPath, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        }.onFailure { e ->
            Log.e("HISTORY_IMG_BIND", "decodeBitmap: exception: ${e.message}", e)
        }.getOrNull()
    }
}
