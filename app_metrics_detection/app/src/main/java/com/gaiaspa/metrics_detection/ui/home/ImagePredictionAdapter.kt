package com.gaiaspa.metrics_detection.ui.home

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.databinding.ItemImagePredictionBinding
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter

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
            b.tvIndex.text = "#${position + 1}"

            val preview = item.previewBitmap
            if (preview != null && !preview.isRecycled) {
                b.ivPhoto.setImageBitmap(preview)
                b.ivPhoto.setOnClickListener { onImageClick(preview) }
            } else {
                b.ivPhoto.setImageResource(R.drawable.ic_gallery)
                b.ivPhoto.setOnClickListener(null)
            }

            when (item.status) {
                HomeViewModel.Status.PENDING -> {
                    b.tvPredictionInfo.text = "Pendiente..."
                    b.tvPredictionInfo.setTextColor(Color.GRAY)
                    b.progressItem.visibility = View.GONE
                    b.barChart.clear()
                }
                HomeViewModel.Status.NORMALIZING -> {
                    b.tvPredictionInfo.text = "Normalizando imagen..."
                    b.tvPredictionInfo.setTextColor(Color.BLUE)
                    b.progressItem.visibility = View.VISIBLE
                    b.barChart.clear()
                }
                HomeViewModel.Status.PROCESSING -> {
                    b.tvPredictionInfo.text = "Procesando..."
                    b.tvPredictionInfo.setTextColor(ContextCompat.getColor(b.root.context, R.color.colorPrimary))
                    b.progressItem.visibility = View.VISIBLE
                    b.barChart.clear()
                }
                HomeViewModel.Status.DONE -> {
                    b.progressItem.visibility = View.GONE
                    b.tvPredictionInfo.setTextColor(Color.BLACK)
                    val p = item.prediction
                    if (p != null && p.status) {
                        // ✅ RESTAURADO: Se muestran todos los datos solicitados
                        b.tvPredictionInfo.text = """
                            Variedad: ${p.bunchColor}
                            QTY: ${p.qty}
                            Mean: ${p.mean} mm
                            Mode: ${p.mode} mm
                            STD: ${p.std}
                        """.trimIndent()
                        setupChart(b.barChart, p.bins, p.pred)
                    } else {
                        b.tvPredictionInfo.text = p?.error ?: "No se obtuvo predicción"
                        b.barChart.clear()
                    }
                }
                HomeViewModel.Status.ERROR -> {
                    b.progressItem.visibility = View.GONE
                    b.tvPredictionInfo.text = "ERROR: ${item.errorMessage}"
                    b.tvPredictionInfo.setTextColor(Color.RED)
                    b.barChart.clear()
                }
            }

            b.btnDeleteItem.setOnClickListener {
                AlertDialog.Builder(b.root.context)
                    .setTitle("Confirmación")
                    .setMessage("¿Eliminar esta imagen?")
                    .setPositiveButton("Sí") { _, _ -> onDelete(adapterPosition) }
                    .setNegativeButton("No", null)
                    .show()
            }
        }

        private fun setupChart(barChart: BarChart, bins: List<Float>, values: List<Int>) {
            if (bins.isEmpty() || values.isEmpty()) { barChart.clear(); return }
            val entries = values.mapIndexed { i, v -> BarEntry(i.toFloat(), v.toFloat()) }
            val barDataSet = BarDataSet(entries, "").apply {
                color = ContextCompat.getColor(barChart.context, R.color.colorPrimary)
                valueTextSize = 10f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = value.toInt().toString()
                }
            }
            barChart.data = BarData(barDataSet).apply { barWidth = 0.7f }
            with(barChart.xAxis) {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                labelRotationAngle = -45f
                valueFormatter = IndexAxisValueFormatter(bins.map { String.format("%.1f", it) })
                setDrawGridLines(false)
            }
            barChart.axisLeft.axisMinimum = 0f
            barChart.axisRight.isEnabled = false
            barChart.description.isEnabled = false
            barChart.legend.isEnabled = false
            barChart.invalidate()
        }
    }
}
