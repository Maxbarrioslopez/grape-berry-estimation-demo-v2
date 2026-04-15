package com.gaiaspa.metrics_detection.ui.history

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.data.model.Lote
import com.gaiaspa.metrics_detection.databinding.FragmentLoteDetailBinding
import com.gaiaspa.metrics_detection.formatTimestampToDateTime
import com.gaiaspa.metrics_detection.pdf_utils.createLotesReportPdf
import com.gaiaspa.metrics_detection.pdf_utils.drawModernHistogram
import com.gaiaspa.metrics_detection.utils.NetworkUtils
import com.gaiaspa.metrics_detection.worker.SyncManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class HistoryDetailFragment : Fragment() {

    private var _binding: FragmentLoteDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HistoryViewModel by activityViewModels()
    private lateinit var adapter: LoteDetailAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoteDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ─── Toolbar ───────────────────────────────────────────────
        // Flecha “back”
        binding.toolbarDetail.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.toolbarDetail.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_share -> {
                    // 1) Obtengo la view del botón compartir
                    val shareView = binding.toolbarDetail.findViewById<View>(R.id.action_share)
                    // 2) Le hago un pequeño «pop» escalandolo
                    shareView?.animate()
                        ?.scaleX(1.2f)?.scaleY(1.2f)
                        ?.setDuration(150)
                        ?.withEndAction {
                            shareView.animate()
                                .scaleX(1f)?.scaleY(1f)
                                ?.setDuration(150)
                                ?.start()
                            // 3) Llamo a tu función de compartir
                            shareLote()
                        }
                        ?.start()
                    true
                }
                else -> false
            }
        }


        // ─── Borrado de imagenes en caché desde fullscreen ──────────
        parentFragmentManager.setFragmentResultListener(
            "deleteCacheRequest", viewLifecycleOwner
        ) { _, bundle ->
            bundle.getString("imagePath")?.let { File(it).delete() }
        }

        // ─── Mostrar datos del lote ─────────────────────────────────
        viewModel.selectedLote.value?.let { setupLoteDetails(it) }

        // ─── Eliminar lote ──────────────────────────────────────────
        binding.btnDeleteLote.setOnClickListener {
            viewModel.selectedLote.value?.let { lote ->
                confirmDeleteLote(lote)
            }
        }
    }

    private fun setupLoteDetails(lote: Lote) {
        // ID y datos
        if(lote.synced) {
            binding.tvLoteId.text = "ID ${lote.cloudId}"
        }else{
            binding.tvLoteId.text = "ID ${lote.id}"
        }
        binding.tvCompany.text   = lote.company
        binding.tvVessel.text    = lote.vessel
        binding.tvBlock.text     = lote.block
        binding.tvImagesLen.text = "${lote.normalizedImages.size} imágenes"

        // Fecha y hora por separado
        val dt = Date(lote.predictedAt)
        val fmtTime = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val fmtDate = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
        binding.tvTime.text = fmtTime.format(dt)
        binding.tvDate.text = fmtDate.format(dt)

        // Estado nube (tint)
        val tintColor = if (lote.synced)
            R.color.dark_green else R.color.dark_red
        binding.ivCloudStatus.setColorFilter(
            ContextCompat.getColor(requireContext(), tintColor)
        )

        // RecyclerView de imágenes/predicciones
        adapter = LoteDetailAdapter(
            lote = lote,
            onImageClick = { bmp -> showFullscreenImage(bmp) }
        )
        binding.recyclerDetail.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HistoryDetailFragment.adapter
            // como está dentro de ScrollView:
            isNestedScrollingEnabled = false
        }
    }

    private fun showFullscreenImage(bitmap: Bitmap) {
        // Guarda y navega
        val file = File(requireContext().cacheDir, "temp_${System.currentTimeMillis()}.png")
        file.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.flush()
        }
        val uri = "file://${file.absolutePath}"
        val action = HistoryDetailFragmentDirections
            .actionLoteDetailFragmentToFullscreenImageFragment(uri)
        findNavController().navigate(action)
    }

    private fun confirmDeleteLote(lote: Lote) {
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar Lote")
            .setMessage("¿Estás seguro de que deseas eliminar este lote?")
            .setPositiveButton("Eliminar") { _, _ -> deleteLote(lote) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteLote(lote: Lote) {
        viewModel.toDeleteLote(lote)
        if (NetworkUtils.isNetworkAvailable(requireContext())) {
            SyncManager.enqueueManualSync(requireContext())
            Toast.makeText(requireContext(), "Sincronizando...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Sin conexión. Sincronización pendiente.", Toast.LENGTH_SHORT).show()
        }
        findNavController().navigateUp()
    }

    private fun shareLote() {
        val lote = viewModel.selectedLote.value ?: return
        try {
            val pdfFile = createLotesReportPdf(listOf(lote), requireContext())
            if (pdfFile == null) {
                Toast.makeText(requireContext(), "Error al generar el PDF", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.provider",
                pdfFile
            )
            startActivity(Intent().apply {
                action = Intent.ACTION_SEND
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "Error al compartir detalle", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
