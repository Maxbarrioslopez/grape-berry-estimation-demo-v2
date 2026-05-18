package com.gaiaspa.metrics_detection.ui.history

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.data.model.Lote
import com.gaiaspa.metrics_detection.databinding.ItemLoteHistoryBinding
import com.gaiaspa.metrics_detection.ml.ImageUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LoteHistoryAdapter(
    private val viewModel: HistoryViewModel,
    private val onViewClick: (Lote) -> Unit
) : RecyclerView.Adapter<LoteHistoryAdapter.LoteViewHolder>() {

    private val items = mutableListOf<Lote>()
    var startIndex: Int = 0
        private set

    /**
     * Sustituye al antiguo submitList(newList, startIndex).
     * Actualiza la lista y el índice de inicio de página.
     */
    fun updateData(newList: List<Lote>, startIndex: Int) {
        val sorted = newList
            .sortedByDescending { it.predictedAt }

        items.clear()
        items.addAll(sorted)
        this.startIndex = startIndex
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LoteViewHolder {
        val binding = ItemLoteHistoryBinding
            .inflate(LayoutInflater.from(parent.context), parent, false)
        return LoteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LoteViewHolder, position: Int) {
        val lote = items[position]
        val absoluteIndex = startIndex + position
        holder.bind(lote, absoluteIndex)
    }

    override fun getItemCount(): Int = items.size

    inner class LoteViewHolder(
        private val b: ItemLoteHistoryBinding
    ) : RecyclerView.ViewHolder(b.root) {

        private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("es", "ES"))

        fun bind(lote: Lote, absoluteIndex: Int) {
            if(lote.synced) {
                b.tvLoteId.text = "ID ${lote.cloudId}"
            }else{
                b.tvLoteId.text = "ID ${lote.id}"
            }
            b.tvCompany.text = lote.company

            val qty = lote.calPredicts.firstOrNull()?.qty
            val bunchCount = lote.calPredicts.size
            if (qty != null && qty > 0) {
                b.tvQty.text = when {
                    bunchCount > 1 -> "${qty} uvas · ${bunchCount} racimos"
                    else -> "${qty} uvas"
                }
                b.tvQty.visibility = android.view.View.VISIBLE
            } else {
                b.tvQty.visibility = android.view.View.GONE
            }

            bindRepresentativeImage(lote)

            val date = Date(lote.predictedAt)
            b.tvDate.text = "${dateFormat.format(date)} · ${timeFormat.format(date)}"

            val tintColor = if (lote.synced)
                ContextCompat.getColor(b.btnOptions.context, R.color.dark_green)
            else
                ContextCompat.getColor(b.btnOptions.context, R.color.dark_red)
            b.btnOptions.setColorFilter(tintColor)

            b.cbSelect.setOnCheckedChangeListener(null)
            b.cbSelect.isChecked = viewModel.isLoteSelected(lote.id)
            b.cbSelect.setOnCheckedChangeListener { _, checked ->
                viewModel.selectLoteId(lote.id, checked)
            }

            b.btnView.setOnClickListener { onViewClick(lote) }
            b.root.setOnClickListener { onViewClick(lote) }
        }

        private fun bindRepresentativeImage(lote: Lote) {
            b.ivRepresentative.imageTintList = null
            b.ivRepresentative.clearColorFilter()
            b.ivRepresentative.alpha = 1f
            val rawPath = lote.representativeImagePath().orEmpty()
            Log.d("HISTORY_IMG_BIND", "bindRepresentativeImage: rawPath='$rawPath' overlayImages=${lote.overlayImages.size} uploadImages=${lote.uploadImages.size}")

            val bitmap = decodeBitmap(rawPath)
            if (bitmap != null && !bitmap.isRecycled) {
                Log.d("HISTORY_IMG_BIND", "bindRepresentativeImage: OK")
                b.ivRepresentative.setImageBitmap(bitmap)
            } else {
                Log.w("HISTORY_IMG_BIND", "bindRepresentativeImage: FAILED, showing placeholder")
                b.ivRepresentative.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(b.root.context, R.color.textHint))
                b.ivRepresentative.setImageResource(R.drawable.ic_image)
            }
            Log.d("HISTORY_IMG_BIND", "bindRepresentativeImage: id=${b.ivRepresentative.id} w=${b.ivRepresentative.width}h=${b.ivRepresentative.height} vis=${b.ivRepresentative.visibility} alpha=${b.ivRepresentative.alpha} drawable=${b.ivRepresentative.drawable?.javaClass?.simpleName} scale=${b.ivRepresentative.scaleType} tint=${b.ivRepresentative.imageTintList} bg=${b.ivRepresentative.background?.javaClass?.simpleName}")
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
                    b.root.context.contentResolver.openInputStream(uri)?.use { stream ->
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
                    while (probe.outWidth / sample > 128 || probe.outHeight / sample > 128) {
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
