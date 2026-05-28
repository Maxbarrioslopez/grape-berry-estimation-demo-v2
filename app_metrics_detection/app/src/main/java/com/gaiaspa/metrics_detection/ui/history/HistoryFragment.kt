package com.gaiaspa.metrics_detection.ui.history

import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.view.*
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.data.model.Lote
import com.gaiaspa.metrics_detection.databinding.FragmentHistoryBinding
import com.gaiaspa.metrics_detection.formatTimestampToDateTime
import com.gaiaspa.metrics_detection.pdf_utils.createLotesReportPdf
import com.gaiaspa.metrics_detection.utils.NetworkUtils
import com.gaiaspa.metrics_detection.worker.SyncManager
import com.google.android.material.snackbar.Snackbar
import java.io.File
import kotlin.math.max

/**
 * History tab fragment displaying the list of saved Lotes.
 *
 * Shows paginated Lote cards with sync status, multi-page selection,
 * and per-Lote delete options. Supports filtering by sync state (all,
 * synced, not synced), PDF report generation for selected batches,
 * and manual WorkManager sync trigger via the toolbar reload action.
 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HistoryViewModel by activityViewModels()
    private lateinit var adapter: LoteHistoryAdapter

    // Enum representing the filter option
    enum class FilterOption { ALL, SYNCED, NOT_SYNCED }
    private var selectedFilter: FilterOption = FilterOption.ALL
    private lateinit var filterLabels: Array<String>
    private enum class MessageTone { SUCCESS, WARNING, ERROR, INFO }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    private fun refreshCurrentPage() {
        val filtered = filterLotes(viewModel.allLotes)
        val startIndex = (viewModel.currentPage - 1) * viewModel.pageSize
        val pageItems = filtered.drop(startIndex).take(viewModel.pageSize)
        val isEmpty = filtered.isEmpty()
        adapter.updateData(pageItems, startIndex)
        binding.recyclerHistory.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.layoutEmptyHistory.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.paginationControls.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.btnPreviousPage.isEnabled = viewModel.currentPage > 1
        binding.btnNextPage.isEnabled = (viewModel.currentPage * viewModel.pageSize) < filtered.size
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // ─── Toolbar ───────────────────────────────────────────────
        // 2) Handle clicks on action items
        binding.toolbarDetail.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_reload -> {
                    // 1) Get the view rendering that item in the Toolbar
                    val reloadView = binding.toolbarDetail.findViewById<View>(R.id.action_reload)
                    // 2) Rotate the icon 360°
                    reloadView?.animate()
                        ?.rotationBy(360f)
                        ?.setDuration(600)
                        ?.start()

                    if (NetworkUtils.isNetworkAvailable(requireContext())) {
                        SyncManager.enqueueManualSync(requireContext())
                        showHistoryMessage(getString(R.string.sync_started), MessageTone.SUCCESS)
                    } else {
                        showHistoryMessage(getString(R.string.no_internet_connection), MessageTone.WARNING)
                    }
                    viewModel.resetPagination()
                    refreshCurrentPage()
                    true
                }
                R.id.action_share -> {
                    // 1) Get the share button view
                    val shareView = binding.toolbarDetail.findViewById<View>(R.id.action_share)
                    // 2) Apply a small "pop" scaling animation
                    shareView?.animate()
                        ?.scaleX(1.2f)?.scaleY(1.2f)
                        ?.setDuration(150)
                        ?.withEndAction {
                            shareView.animate()
                                .scaleX(1f)?.scaleY(1f)
                                ?.setDuration(150)
                                ?.start()
                            // 3) Call the share function
                            shareSelectedLotesPdf()
                        }
                        ?.start()
                    true
                }
                else -> false
            }
        }

        // 2) Recycler + Adapter
        adapter = LoteHistoryAdapter(viewModel) { lote ->
            viewModel.selectLote(lote)
            findNavController().navigate(
                HistoryFragmentDirections
                    .actionHistoryFragmentToLoteDetailFragment(lote.id)
            )
        }
        binding.recyclerHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerHistory.adapter = adapter

        // 3) Filter
        filterLabels = resources.getStringArray(R.array.filter_options)
        val autoAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            filterLabels
        )
        binding.autoFilter.setAdapter(autoAdapter)
        binding.autoFilter.setOnTouchListener { _, _ ->
            binding.autoFilter.showDropDown()
            false
        }
        binding.tilFilter.setEndIconOnClickListener {
            binding.autoFilter.showDropDown()
        }
        binding.autoFilter.setOnItemClickListener { _, _, pos, _ ->
            selectedFilter = when (pos) {
                1 -> FilterOption.SYNCED
                2 -> FilterOption.NOT_SYNCED
                else -> FilterOption.ALL
            }
            binding.autoFilter.setText(filterLabels[pos], false)
            viewModel.resetPagination()
            refreshCurrentPage()
        }

        // 4) Data + pagination
        viewModel.lotes.observe(viewLifecycleOwner) { refreshCurrentPage() }
        binding.btnPreviousPage.setOnClickListener {
            viewModel.loadPreviousPage()
            refreshCurrentPage()
        }
        binding.btnNextPage.setOnClickListener {
            viewModel.loadNextPage()
            refreshCurrentPage()
        }
    }

    /**
     * Applies the selected filter to a local list of batches.
     */
    private fun filterLotes(lotes: List<Lote>): List<Lote> {
        return when (selectedFilter) {
            FilterOption.ALL -> lotes
            FilterOption.SYNCED -> lotes.filter { it.synced }
            FilterOption.NOT_SYNCED -> lotes.filter { !it.synced }
        }
    }

    /**
     * Takes ALL batches the user checked (across multiple pages),
     * generates and shares a PDF with those batches.
     */
    private fun shareSelectedLotesPdf() {
        // Lotes checked in the ViewModel
        val selectedLotes = viewModel.getSelectedLotes()

        if (selectedLotes.isEmpty()) {
            showHistoryMessage(getString(R.string.no_lotes_selected), MessageTone.WARNING)
            return
        }

        try {
            val pdfFile = createLotesReportPdf(selectedLotes, requireContext())
            if (pdfFile == null) {
                showHistoryMessage(getString(R.string.error_generating_pdf), MessageTone.ERROR)
                return
            }

            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.provider",
                pdfFile
            )

            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_lotes_pdf)))
        } catch (e: Exception) {
            showHistoryMessage(getString(R.string.error_sharing_pdf), MessageTone.ERROR)
        }
    }

    /**
     * Generates a PDF with information for multiple batches (the selected ones),
     * drawing histograms for each CalPredict (when applicable).
     */
    private fun generateAllLotesPdf(lotes: List<Lote>): File? {
        val pdfDocument = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842

        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 12f
        }
        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas

        val marginLeft = 40f
        val marginTop = 40f
        val marginRight = 40f
        val marginBottom = 40f
        val usableWidth = pageWidth - marginLeft - marginRight
        val usableHeight = pageHeight - marginTop - marginBottom
        var currentY = marginTop

        fun checkPageSpace(requiredSpace: Float) {
            if (currentY + requiredSpace > (pageHeight - marginBottom)) {
                pdfDocument.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                currentY = marginTop
            }
        }

        // Main title
        canvas.drawText("Selected Batches (${lotes.size})", marginLeft, currentY, paint)
        currentY += 30f

        lotes.forEachIndexed { index, lote ->
            checkPageSpace(200f)

            paint.textSize = 13f
            canvas.drawText("Batch ${index + 1}: ${if (lote.synced) "(Synced)" else "(Local)"}", marginLeft, currentY, paint)
            currentY += 20f
            paint.textSize = 12f

            canvas.drawText(" - Batch ID: ${lote.id}", marginLeft, currentY, paint)
            currentY += 15f
            canvas.drawText(" - User ID: ${lote.userId}", marginLeft, currentY, paint)
            currentY += 15f
            canvas.drawText(" - Company: ${lote.company}", marginLeft, currentY, paint)
            currentY += 15f
            canvas.drawText(" - Vessel: ${lote.vessel}", marginLeft, currentY, paint)
            currentY += 15f
            canvas.drawText(" - Block: ${lote.block}", marginLeft, currentY, paint)
            currentY += 15f

            canvas.drawText(
                " - Predicted At: ${formatTimestampToDateTime(lote.predictedAt)}",
                marginLeft, currentY, paint
            )
            currentY += 25f

            // CalPredicts with histogram
            if (lote.calPredicts.isEmpty()) {
                canvas.drawText("   No CalPredicts", marginLeft, currentY, paint)
                currentY += 30f
            } else {
                lote.calPredicts.forEachIndexed { cpIndex, cp ->
                    checkPageSpace(220f)
                    canvas.drawText("   CalPredict ${cpIndex + 1}:", marginLeft, currentY, paint)
                    currentY += 15f
                    canvas.drawText(
                        "   - Status: ${cp.status}, Error: ${cp.error}, Color: ${cp.bunchColor}",
                        marginLeft, currentY, paint
                    )
                    currentY += 15f
                    canvas.drawText(
                        "   - Qty: ${cp.qty}, STD: ${cp.std}, MEAN: ${cp.mean}, Mode: ${cp.mode}",
                        marginLeft, currentY, paint
                    )
                    currentY += 20f

                    // Only draw histogram if cp.status is true, for example
                    if (cp.status) {
                        val adjustedPred = cp.pred.map { pVal -> max(0, pVal) }

                        val histX = marginLeft + 20f
                        val histY = currentY
                        val histWidth = usableWidth * 0.75f
                        val histHeight = 150f

                        // Use the histogram function (drawModernHistogram)
                        drawModernHistogram(
                            canvas = canvas,
                            left = histX,
                            top = histY,
                            width = histWidth,
                            height = histHeight,
                            bins = cp.bins,
                            pred = adjustedPred,
                            title = "Quantity per Bin",
                            paint = paint
                        )
                        currentY += histHeight + 40f
                    }
                }
            }

            // Separator
            canvas.drawLine(marginLeft, currentY, marginLeft + usableWidth, currentY, paint)
            currentY += 40f
        }

        pdfDocument.finishPage(page)

        val pdfFile = File(requireContext().cacheDir, "Selected_Batches.pdf")
        return try {
            pdfFile.outputStream().use { output ->
                pdfDocument.writeTo(output)
            }
            pdfFile
        } catch (e: Exception) {
            null
        } finally {
            pdfDocument.close()
        }
    }

    private fun showHistoryMessage(message: String, tone: MessageTone = MessageTone.INFO) {
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

    /**
     * Example function for drawing a "modern" histogram.
     */
    private fun drawModernHistogram(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        bins: List<Float>,
        pred: List<Int>,
        title: String,
        paint: Paint
    ) {
        if (bins.isEmpty() || pred.isEmpty() || bins.size != pred.size) return

        val originalColor = paint.color
        val originalStyle = paint.style
        val originalTextSize = paint.textSize
        val originalTextAlign = paint.textAlign

        paint.color = Color.parseColor("#FAFAFA")
        paint.style = Paint.Style.FILL
        canvas.drawRect(left, top, left + width, top + height, paint)

        val maxVal = pred.maxOrNull()?.takeIf { it > 0 } ?: 1
        val numBars = bins.size

        val marginX = 40f
        val marginY = 40f
        val graphLeft = left + marginX
        val graphTop = top + marginY
        val graphRight = (left + width) - marginX
        val graphBottom = (top + height) - marginY
        val graphWidth = graphRight - graphLeft
        val graphHeight = graphBottom - graphTop

        // Axes
        paint.color = Color.DKGRAY
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f

        canvas.drawLine(graphLeft, graphTop, graphLeft, graphBottom, paint)
        canvas.drawLine(graphLeft, graphBottom, graphRight, graphBottom, paint)

        // Grid
        val gridLines = 5
        val stepValue = maxVal.toFloat() / gridLines
        val stepHeight = graphHeight / gridLines
        paint.pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)

        for (i in 1..gridLines) {
            val yPos = graphBottom - (stepHeight * i)
            canvas.drawLine(graphLeft, yPos, graphRight, yPos, paint)
        }

        // Bars
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.color = Color.BLUE

        val barWidth = graphWidth / numBars
        for (i in bins.indices) {
            val barValue = pred[i].toFloat()
            val ratio = barValue / maxVal
            val barHeight = graphHeight * ratio

            val bLeft = graphLeft + i * barWidth + 3f
            val bRight = bLeft + barWidth - 6f
            val bBottom = graphBottom
            val bTop = bBottom - barHeight

            canvas.drawRect(bLeft, bTop, bRight, bBottom, paint)
        }

        // Y-axis labels
        paint.color = Color.BLACK
        paint.textSize = 10f
        paint.textAlign = Paint.Align.RIGHT
        for (i in 0..gridLines) {
            val yVal = stepValue * i
            val yPos = graphBottom - (stepHeight * i)
            canvas.drawText(String.format("%.0f", yVal), graphLeft - 5f, yPos + 4f, paint)
        }

        // X-axis labels (bins) rotated -45°
        paint.textAlign = Paint.Align.LEFT
        bins.forEachIndexed { i, binVal ->
            val barCenterX = graphLeft + i * barWidth + (barWidth / 2)
            val labelX = barCenterX
            val labelY = graphBottom + 25f

            canvas.save()
            canvas.rotate(-45f, labelX, labelY)
            val labelText = String.format("%.1f", binVal)
            canvas.drawText(labelText, labelX, labelY, paint)
            canvas.restore()
        }

        // Legend
        val legendX = graphRight - 80f
        val legendY = graphTop - 20f
        paint.color = Color.BLUE
        canvas.drawRect(legendX, legendY, legendX + 10f, legendY + 10f, paint)
        paint.color = Color.DKGRAY
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(title, legendX + 15f, legendY + 10f, paint)

        paint.color = originalColor
        paint.style = originalStyle
        paint.textSize = originalTextSize
        paint.textAlign = originalTextAlign
        paint.pathEffect = null
    }

    override fun onResume() {
        super.onResume()
        viewModel.resetPagination()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
