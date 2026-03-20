package com.gaiaspa.metrics_detection.ui.home

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.gaiaspa.metrics_detection.R
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.gaiaspa.metrics_detection.databinding.ItemImagePredictionBinding
import com.github.mikephil.charting.charts.BarChart

class ImagePredictionAdapter(
    private val items: MutableList<HomeViewModel.ImagePrediction>,
    private val onDelete: (position: Int) -> Unit,
    private val onImageClick: (bitmap: Bitmap) -> Unit
) : RecyclerView.Adapter<ImagePredictionAdapter.PredictionViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PredictionViewHolder {
        val binding = ItemImagePredictionBinding
            .inflate(LayoutInflater.from(parent.context), parent, false)
        return PredictionViewHolder(binding, onDelete, onImageClick)
    }

    override fun onBindViewHolder(holder: PredictionViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newList: List<HomeViewModel.ImagePrediction>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    class PredictionViewHolder(
        private val b: ItemImagePredictionBinding,
        private val onDelete: (Int) -> Unit,
        private val onImageClick: (Bitmap) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(item: HomeViewModel.ImagePrediction, position: Int) {
            // Índice
            b.tvIndex.text = "#${position + 1}"

            // Imagen principal
            b.ivPhoto.setImageBitmap(item.image)
            b.ivPhoto.setOnClickListener {
                onImageClick(item.image)
            }

            // Información de la predicción
            when (item.prediction?.status) {
                false -> {
                    b.tvPredictionInfo.text = item.prediction.error
                    b.barChart.clear()
                }
                true -> {
                    val p = item.prediction
                    b.tvPredictionInfo.text = """
                        Color: ${p.bunchColor}
                        QTY: ${p.qty}
                        Mean: ${p.mean}
                        Mode: ${p.mode}
                        STD: ${p.std}
                    """.trimIndent()
                    setupChart(b.barChart, p.bins, p.pred)
                }
                else -> {
                    b.tvPredictionInfo.text = "Sin predicción"
                    b.barChart.clear()
                }
            }

            // ProgressBar sobre el gráfico
            b.progressItem.visibility =
                if (item.isProcessing) android.view.View.VISIBLE
                else android.view.View.GONE

            // Botón Eliminar
            b.btnDeleteItem.setOnClickListener {
                AlertDialog.Builder(b.root.context)
                    .setTitle("Confirmación")
                    .setMessage("¿Eliminar esta predicción?")
                    .setPositiveButton("Sí") { _, _ -> onDelete(adapterPosition) }
                    .setNegativeButton("No", null)
                    .show()
            }
        }

        private fun setupChart(barChart: BarChart, bins: List<Float>, values: List<Int>) {
            // 1) Datos
            val entries = values.mapIndexed { i, v -> BarEntry(i.toFloat(), v.toFloat()) }
            val barDataSet = BarDataSet(entries, "") // Sin leyenda textual
            // Usar tu color primario (o un array de tonos si quieres degradado)
            val primaryColor = ContextCompat.getColor(barChart.context, R.color.colorPrimary)
            barDataSet.color = primaryColor
            barDataSet.valueTextSize = 10f
            barDataSet.valueTextColor = primaryColor
            barDataSet.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = value.toInt().toString()
            }

            // 2) Configurar el BarData
            val barData = BarData(barDataSet).apply {
                barWidth = 0.7f  // barras más delgadas

            }

            // 3) Ejes X
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

            // 4) Ejes Y
            barChart.axisLeft.apply {
                setDrawAxisLine(false)
                setDrawGridLines(true)
                gridColor = ContextCompat.getColor(barChart.context, R.color.outlineGray)
                textColor = ContextCompat.getColor(barChart.context, R.color.textSecondary)
                textSize = 10f
                axisMinimum = 0f
            }
            barChart.axisRight.isEnabled = false

            // 5) Desactivar  tipo de zoom/interacción de escala
            barChart.apply {
                setPinchZoom(false)                  // pinch-zoom
                isDoubleTapToZoomEnabled = false     // doble-tap
                setScaleEnabled(false)               // scaling en cualquiera de los ejes
                isDragEnabled = false                // opcional: impide drag/panning
            }


            // 5) Estilo general
            barChart.apply {
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
