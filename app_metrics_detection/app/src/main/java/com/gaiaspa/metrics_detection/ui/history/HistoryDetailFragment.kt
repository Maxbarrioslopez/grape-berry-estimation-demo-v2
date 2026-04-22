package com.gaiaspa.metrics_detection.ui.history

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.gaiaspa.metrics_detection.pdf_utils.createLotesReportPdf
import com.gaiaspa.metrics_detection.utils.NetworkUtils
import com.gaiaspa.metrics_detection.worker.SyncManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HistoryDetailFragment - v9.1 FIXED
 * Corregido para manejar rutas de imagen (String) en lugar de Bitmaps pesados.
 */
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

        binding.toolbarDetail.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.toolbarDetail.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_share -> {
                    val shareView = binding.toolbarDetail.findViewById<View>(R.id.action_share)
                    shareView?.animate()
                        ?.scaleX(1.2f)?.scaleY(1.2f)
                        ?.setDuration(150)
                        ?.withEndAction {
                            shareView.animate()
                                .scaleX(1f)?.scaleY(1f)
                                ?.setDuration(150)
                                ?.start()
                            shareLote()
                        }
                        ?.start()
                    true
                }
                else -> false
            }
        }

        parentFragmentManager.setFragmentResultListener(
            "deleteCacheRequest", viewLifecycleOwner
        ) { _, bundle ->
            bundle.getString("imagePath")?.let { path ->
                val file = File(path.replace("file://", ""))
                if (file.exists() && file.parentFile?.name == "cache") {
                    file.delete()
                }
            }
        }

        viewModel.selectedLote.value?.let { setupLoteDetails(it) }

        binding.btnDeleteLote.setOnClickListener {
            viewModel.selectedLote.value?.let { lote ->
                confirmDeleteLote(lote)
            }
        }
    }

    private fun setupLoteDetails(lote: Lote) {
        if(lote.synced) {
            binding.tvLoteId.text = "ID ${lote.cloudId}"
        }else{
            binding.tvLoteId.text = "ID ${lote.id}"
        }
        binding.tvCompany.text   = lote.company
        binding.tvVessel.text    = lote.vessel
        binding.tvBlock.text     = lote.block
        binding.tvImagesLen.text = "${lote.normalizedImages.size} imágenes"

        val dt = Date(lote.predictedAt)
        val fmtTime = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val fmtDate = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
        binding.tvTime.text = fmtTime.format(dt)
        binding.tvDate.text = fmtDate.format(dt)

        val tintColor = if (lote.synced)
            R.color.dark_green else R.color.dark_red
        binding.ivCloudStatus.setColorFilter(
            ContextCompat.getColor(requireContext(), tintColor)
        )

        adapter = LoteDetailAdapter(
            lote = lote,
            onImageClick = { path -> showFullscreenImage(path) } // ✅ FIXED: bmp -> path (String)
        )
        binding.recyclerDetail.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HistoryDetailFragment.adapter
            isNestedScrollingEnabled = false
        }
    }

    private fun showFullscreenImage(imagePath: String) {
        // ✅ FIXED: Ya no comprimimos un bitmap, pasamos la ruta directa
        Log.d("Fullscreen_SAFE", "Navegando a fullscreen con ruta: $imagePath")
        val uri = if (imagePath.startsWith("file://")) imagePath else "file://$imagePath"
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
            Log.e("History_DETAIL", "Error shareLote: ${e.message}")
            Toast.makeText(requireContext(), "Error al compartir detalle", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
