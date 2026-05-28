package com.gaiaspa.metrics_detection.ui.history

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detail view for a single Lote (batch) in the history tab.
 *
 * Displays batch metadata, cloud sync status, and the list of CalPredict
 * results with their overlay images. Supports fullscreen preview (with
 * missing-file guard), PDF generation + sharing, local/cloud deletion,
 * and temporary cache file cleanup on back navigation.
 */
class HistoryDetailFragment : Fragment() {

    private var _binding: FragmentLoteDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HistoryViewModel by activityViewModels()
    private lateinit var adapter: LoteDetailAdapter
    private enum class MessageTone { SUCCESS, WARNING, ERROR, INFO }

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

        binding.btnDeleteLocalOnly.setOnClickListener {
            viewModel.selectedLote.value?.let { lote ->
                confirmDeleteLocalOnly(lote)
            }
        }

        binding.btnDeleteLote.setOnClickListener {
            viewModel.selectedLote.value?.let { lote ->
                confirmDeleteLocalAndCloud(lote)
            }
        }
    }

    private fun setupLoteDetails(lote: Lote) {
        // Display cloud ID if synced, otherwise local Room ID
        if(lote.synced) {
            binding.tvLoteId.text = "ID ${lote.cloudId}"
        }else{
            binding.tvLoteId.text = "ID ${lote.id}"
        }
        binding.tvCompany.text   = lote.company
        
        // Hide Vessel and Block if empty (no value added to UI)
        if (lote.vessel.isNotBlank()) {
            binding.tvVessel.text = lote.vessel
            binding.tvVessel.visibility = View.VISIBLE
        } else {
            binding.tvVessel.visibility = View.GONE
        }
        
        if (lote.block.isNotBlank()) {
            binding.tvBlock.text = lote.block
            binding.tvBlock.visibility = View.VISIBLE
        } else {
            binding.tvBlock.visibility = View.GONE
        }
        
        binding.tvImagesLen.text = getString(R.string.images_count, lote.images.size)

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
        val cleanPath = imagePath.replace("file://", "")
        if (cleanPath.isBlank() || !File(cleanPath).exists()) {
            showDetailMessage(getString(R.string.file_not_found), MessageTone.WARNING)
            return
        }
        runCatching {
            val uri = if (imagePath.startsWith("file://")) imagePath else "file://$imagePath"
            val action = HistoryDetailFragmentDirections
                .actionLoteDetailFragmentToFullscreenImageFragment(uri)
            findNavController().navigate(action)
        }.onFailure {
            Log.e("Fullscreen_SAFE", "Could not open fullscreen", it)
            showDetailMessage(getString(R.string.preview_generation_error), MessageTone.ERROR)
        }
    }

    private fun confirmDeleteLocalOnly(lote: Lote) {
        val message = buildString {
            append(getString(R.string.delete_lote_local_only_message))
            if (!lote.synced) {
                append("\n\n")
                append(getString(R.string.delete_lote_unsynced_warning))
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_lote_local_only_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.confirm_delete)) { _, _ -> deleteLocalOnly(lote) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
            .apply { setCanceledOnTouchOutside(false) }
    }

    private fun confirmDeleteLocalAndCloud(lote: Lote) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_lote_title))
            .setMessage(getString(R.string.delete_lote_local_message))
            .setPositiveButton(getString(R.string.confirm_delete)) { _, _ -> deleteLocalAndCloud(lote) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
            .apply { setCanceledOnTouchOutside(false) }
    }

    private fun deleteLocalOnly(lote: Lote) {
        viewModel.deleteLocalOnly(lote) { result ->
            if (isAdded) {
                var shouldNavigate = true
                when {
                    result.missingLote -> showDetailMessage(getString(R.string.lote_delete_not_found), MessageTone.WARNING)
                    result.success && result.failedFileCount > 0 -> showDetailMessage(getString(R.string.lote_delete_local_partial), MessageTone.WARNING)
                    result.success -> showDetailMessage(getString(R.string.lote_delete_local_success), MessageTone.SUCCESS)
                    else -> {
                        showDetailMessage(getString(R.string.lote_delete_failed), MessageTone.ERROR)
                        shouldNavigate = false
                    }
                }
                if (shouldNavigate) findNavController().navigateUp()
            }
        }
    }

    private fun deleteLocalAndCloud(lote: Lote) {
        viewModel.deleteLocalAndCloud(lote) { result ->
            if (isAdded) {
                var shouldNavigate = true
                when {
                    result.missingLote -> showDetailMessage(getString(R.string.lote_delete_not_found), MessageTone.WARNING)
                    !result.success -> {
                        showDetailMessage(getString(R.string.lote_delete_failed), MessageTone.ERROR)
                        shouldNavigate = false
                    }
                    result.requiresRemoteDelete -> {
                        if (NetworkUtils.isNetworkAvailable(requireContext())) {
                            SyncManager.enqueueManualSync(requireContext())
                            showDetailMessage(getString(R.string.syncing), MessageTone.INFO)
                        } else {
                            showDetailMessage(getString(R.string.no_connection_sync_pending), MessageTone.WARNING)
                        }
                    }
                    result.failedFileCount > 0 -> showDetailMessage(getString(R.string.lote_delete_local_partial), MessageTone.WARNING)
                    else -> showDetailMessage(getString(R.string.lote_delete_global_local_success), MessageTone.SUCCESS)
                }
                if (shouldNavigate) findNavController().navigateUp()
            }
        }
    }

    private fun shareLote() {
        val lote = viewModel.selectedLote.value ?: return
        try {
            val pdfFile = createLotesReportPdf(listOf(lote), requireContext())
            if (pdfFile == null) {
                showDetailMessage(getString(R.string.error_generating_pdf), MessageTone.ERROR)
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
            showDetailMessage(getString(R.string.error_sharing_detail), MessageTone.ERROR)
        }
    }

    private fun showDetailMessage(message: String, tone: MessageTone = MessageTone.INFO) {
        val root = _binding?.root ?: return
        val background = when (tone) {
            MessageTone.SUCCESS -> R.color.success_soft
            MessageTone.WARNING -> R.color.warning_soft
            MessageTone.ERROR -> R.color.error_soft
            MessageTone.INFO -> R.color.info_soft
        }
        val action = when (tone) {
            MessageTone.SUCCESS -> R.color.colorPrimaryDark
            MessageTone.WARNING -> R.color.chip_incomplete_text
            MessageTone.ERROR -> R.color.error
            MessageTone.INFO -> R.color.info
        }
        Snackbar.make(root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(root.context, background))
            .setTextColor(ContextCompat.getColor(root.context, R.color.textPrimary))
            .setActionTextColor(ContextCompat.getColor(root.context, action))
            .setAction(getString(R.string.close)) { }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
