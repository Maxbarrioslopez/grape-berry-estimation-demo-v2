package com.gaiaspa.metrics_detection.ui.history

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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

class LoteDetailAdapter(
    private val lote: Lote,
    private val onImageClick: (Bitmap) -> Unit
) : RecyclerView.Adapter<LoteDetailAdapter.LoteDetailViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LoteDetailViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image_prediction_detail, parent, false)
        return LoteDetailViewHolder(view, onImageClick)
    }

    override fun onBindViewHolder(holder: LoteDetailViewHolder, position: Int) {
        val imagePath = lote.normalizedImages.getOrNull(position) ?: ""
        val prediction = lote.calPredicts.getOrNull(position)
        holder.bind(imagePath, prediction)
    }

    override fun getItemCount(): Int = lote.normalizedImages.size

    class LoteDetailViewHolder(
        itemView: View,
        private val onImageClick: (Bitmap) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val ivPhoto: ImageView = itemView.findViewById(R.id.ivPhoto)
        private val tvPredictionInfo: TextView = itemView.findViewById(R.id.tvPredictionInfo)
        private val barChart: BarChart = itemView.findViewById(R.id.barChart)

        fun bind(imagePath: String, prediction: CalPredict?) {
            // Intentar cargar el bitmap
            val bitmap = if (imagePath.isNotEmpty()) BitmapFactory.decodeFile(imagePath) else null
            
            if (bitmap != null) {
                ivPhoto.setImageBitmap(bitmap)
                ivPhoto.setOnClickListener {
                    onImageClick(bitmap)
                }
            } else {
                // Si no hay imagen (por ejemplo, después de descargar lotes de la nube sin fotos)
                ivPhoto.setImageResource(R.drawable.ic_gallery) // O cualquier icono de placeholder
                ivPhoto.setOnClickListener {
                    Toast.makeText(itemView.context, "Imagen no disponible (Solo datos)", Toast.LENGTH_SHORT).show()
                }
            }

            // Mostrar datos de predicción
            if(prediction?.status == false) {
                tvPredictionInfo.text = prediction.error
                barChart.clear()
            } else if (prediction?.status == true) {
                val predInfo = """
                    Color: ${prediction.bunchColor}
                    QTY: ${prediction.qty}
                    Mean: ${prediction.mean}
                    Mode: ${prediction.mode}
                    STD: ${prediction.std}
                """.trimIndent()
                tvPredictionInfo.text = predInfo
                setupChart(barChart, prediction.bins, prediction.pred)
            } else {
                tvPredictionInfo.text = "Sin predicción"
                barChart.clear()
            }
        }

        private fun setupChart(barChart: BarChart, bins: List<Float>, values: List<Int>) {
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
    }
}
