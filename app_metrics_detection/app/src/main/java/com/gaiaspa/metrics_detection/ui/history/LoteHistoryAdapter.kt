package com.gaiaspa.metrics_detection.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.data.model.Lote
import com.gaiaspa.metrics_detection.databinding.ItemLoteHistoryBinding
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
            // Número de Lote con prefijo
            if(lote.synced) {
                b.tvLoteId.text = "ID ${lote.cloudId}"
            }else{
                b.tvLoteId.text = "ID ${lote.id}"
            }
            // Detalles principales
            b.tvCompany.text = lote.company
            b.tvVessel.text = lote.vessel
            b.tvBlock.text = lote.block

            // Hora / Fecha
            val date = Date(lote.predictedAt)
            b.tvTime.text = timeFormat.format(date)
            b.tvDate.text = dateFormat.format(date)

            // Estado de nube (tint del icono)
            val tintColor = if (lote.synced)
                ContextCompat.getColor(b.btnOptions.context, R.color.dark_green)
            else
                ContextCompat.getColor(b.btnOptions.context, R.color.dark_red)
            b.btnOptions.setColorFilter(tintColor)

            // Checkbox de selección
            b.cbSelect.setOnCheckedChangeListener(null)
            b.cbSelect.isChecked = viewModel.isLoteSelected(lote.id)
            b.cbSelect.setOnCheckedChangeListener { _, checked ->
                viewModel.selectLoteId(lote.id, checked)
            }

            // Botón “Ver” y click en el card completo
            b.btnView.setOnClickListener { onViewClick(lote) }
            b.root.setOnClickListener { onViewClick(lote) }
        }
    }
}
