package com.gaiaspa.metrics_detection.ui.home

import android.app.Dialog
import android.content.ContentResolver
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.data.model.CalPredict
import com.gaiaspa.metrics_detection.databinding.ItemImagePredictionBinding
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet
import com.github.mikephil.charting.renderer.BarChartRenderer
import com.github.mikephil.charting.utils.ViewPortHandler
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Adapter for the capture RecyclerView.
 *
 * Supports two render modes:
 * - LEGACY: flat list of single images with status/histogram.
 * - RACIMO: grouped Frente/Reverso pairs with fusion status,
 *   per-face capture buttons, and multi-view progress badges.
 *
 * Handles responsive layout (horizontal/vertical stacking) based on
 * screen width and font scale.
 */
class ImagePredictionAdapter(
    private val items: MutableList<HomeViewModel.ImagePrediction>,
    private val onDelete: (position: Int) -> Unit,
    private val onDeleteRacimo: (racimoIndex: Int) -> Unit = {},
    private val onDeletePhoto: (racimoIndex: Int, role: HomeViewModel.PhotoRole) -> Unit = { _, _ -> },
    private val onTakePhoto: (racimoIndex: Int, role: HomeViewModel.PhotoRole) -> Unit = { _, _ -> },
    private val onUploadPhoto: (racimoIndex: Int, role: HomeViewModel.PhotoRole) -> Unit = { _, _ -> },
    private val onSwapFrontBack: (racimoIndex: Int) -> Unit = {},
    private val onImageClick: (bitmap: Bitmap) -> Unit
) : RecyclerView.Adapter<ImagePredictionAdapter.PredictionViewHolder>() {

    private enum class RenderMode { LEGACY, RACIMO }

    private val racimos = mutableListOf<RacimoUiModel>()
    private var renderMode = RenderMode.LEGACY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PredictionViewHolder {
        val binding = ItemImagePredictionBinding
            .inflate(LayoutInflater.from(parent.context), parent, false)
        return PredictionViewHolder(
            binding,
            onDelete,
            onDeleteRacimo,
            onDeletePhoto,
            onTakePhoto,
            onUploadPhoto,
            onSwapFrontBack,
            onImageClick
        )
    }

    override fun onBindViewHolder(holder: PredictionViewHolder, position: Int) {
        if (renderMode == RenderMode.RACIMO) {
            holder.bindRacimo(racimos[position])
        } else {
            holder.bind(items[position], position)
        }
    }

    override fun getItemCount(): Int =
        if (renderMode == RenderMode.RACIMO) racimos.size else items.size

    /**
     * Switches to legacy (flat list) mode and replaces all items.
     * Used when multi-view fusion is disabled.
     */
    fun updateList(newList: List<HomeViewModel.ImagePrediction>) {
        renderMode = RenderMode.LEGACY
        items.clear()
        items.addAll(newList)
        racimos.clear()
        notifyDataSetChanged()
    }

    /**
     * Switches to racimo (grouped) mode and replaces all models.
     * Used when multi-view fusion is enabled.
     */
    fun updateRacimos(newList: List<RacimoUiModel>) {
        renderMode = RenderMode.RACIMO
        racimos.clear()
        racimos.addAll(newList)
        items.clear()
        notifyDataSetChanged()
    }

    class PredictionViewHolder(
        private val b: ItemImagePredictionBinding,
        private val onDelete: (Int) -> Unit,
        private val onDeleteRacimo: (Int) -> Unit,
        private val onDeletePhoto: (Int, HomeViewModel.PhotoRole) -> Unit,
        private val onTakePhoto: (Int, HomeViewModel.PhotoRole) -> Unit,
        private val onUploadPhoto: (Int, HomeViewModel.PhotoRole) -> Unit,
        private val onSwapFrontBack: (Int) -> Unit,
        private val onImageClick: (Bitmap) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {

        // Track expanded/collapsed state for each ViewHolder
        private var isExpanded = false

        /**
         * Binds a single ImagePrediction item in LEGACY mode.
         * Shows the preview bitmap, status chip, and result/histogram
         * if processing is complete.
         */
        fun bind(item: HomeViewModel.ImagePrediction, position: Int) {
            b.tvIndex.text = b.root.context.getString(R.string.bunch_title, position + 1)
            b.tvPhotoSectionTitle.visibility = View.GONE
            b.tvPhotoALabel.visibility = View.GONE
            b.tvPhotoAStatus.visibility = View.GONE
            b.tvPhotoBStatus.visibility = View.GONE
            b.layoutPhotoB.visibility = View.GONE
            b.spacePhotoPair.visibility = View.GONE
            b.layoutProgressBadges.visibility = View.GONE
            b.tvFusedResultBadge.visibility = View.GONE
            b.layoutFusionActions.visibility = View.GONE
            b.layoutCompactSummary.visibility = View.GONE
            b.btnPrimaryRacimoAction.visibility = View.GONE
            b.layoutPhotoAButtonRow.visibility = View.GONE
            b.layoutPhotoBButtonRow.visibility = View.GONE
            b.layoutPhotoAOverlayActions.visibility = View.GONE
            b.layoutPhotoBOverlayActions.visibility = View.GONE
            b.btnDeleteItem.text = b.root.context.getString(R.string.delete)
            b.btnDeleteItem.contentDescription = b.root.context.getString(R.string.delete)
            hideResultAndHistogram()
            applyChip(R.string.status_pending, R.drawable.bg_chip_pending, R.color.chip_pending_text)

            val preview = item.previewBitmap
            if (preview != null && !preview.isRecycled) {
                b.ivPhoto.clearColorFilter()
                b.ivPhoto.alpha = 1f
                b.ivPhoto.setImageBitmap(preview)
                b.ivPhoto.setOnClickListener { if (!preview.isRecycled) onImageClick(preview) }
                setPhotoStroke(b.cardPhotoA, true)
            } else {
                b.ivPhoto.setImageResource(R.drawable.ic_gallery)
                b.ivPhoto.setColorFilter(color(R.color.textHint))
                b.ivPhoto.alpha = 0.68f
                b.ivPhoto.setOnClickListener(null)
                setPhotoStroke(b.cardPhotoA, false)
            }

            when (item.status) {
                HomeViewModel.Status.PENDING -> {
                    b.tvPredictionInfo.text = b.root.context.getString(R.string.pending)
                    b.tvPredictionInfo.setTextColor(color(R.color.textSecondary))
                    b.progressItem.visibility = View.GONE
                }
                HomeViewModel.Status.NORMALIZING -> {
                    applyChip(R.string.status_processing, R.drawable.bg_chip_info, R.color.chip_info_text)
                    b.tvPredictionInfo.text = b.root.context.getString(R.string.normalizing_image)
                    b.tvPredictionInfo.setTextColor(color(R.color.info))
                    b.progressItem.visibility = View.VISIBLE
                }
                HomeViewModel.Status.PROCESSING -> {
                    applyChip(R.string.status_processing, R.drawable.bg_chip_info, R.color.chip_info_text)
                    b.tvPredictionInfo.text = b.root.context.getString(R.string.processing)
                    b.tvPredictionInfo.setTextColor(color(R.color.colorPrimary))
                    b.progressItem.visibility = View.VISIBLE
                }
                HomeViewModel.Status.DONE -> {
                    b.progressItem.visibility = View.GONE
                    val p = item.prediction
                    if (p != null && p.status && p.qty > 0) {
                        applyChip(R.string.status_processed, R.drawable.bg_chip_fused, R.color.chip_fused_text)
                        b.tvPredictionInfo.text = b.root.context.getString(R.string.result_ready_single)
                        b.tvPredictionInfo.setTextColor(color(R.color.textSecondary))
                        showResult(p, isFused = false)
                        showHistogram(p, isFused = false)
                    } else {
                        applyChip(R.string.status_error, R.drawable.bg_chip_error, R.color.chip_error_text)
                        b.tvPredictionInfo.text = p?.error ?: b.root.context.getString(R.string.prediction_missing)
                        b.tvPredictionInfo.setTextColor(color(R.color.error))
                    }
                }
                HomeViewModel.Status.ERROR -> {
                    applyChip(R.string.status_error, R.drawable.bg_chip_error, R.color.chip_error_text)
                    b.progressItem.visibility = View.GONE
                    b.tvPredictionInfo.text = b.root.context.getString(
                        R.string.error_prefix,
                        item.errorMessage.orEmpty()
                    )
                    b.tvPredictionInfo.setTextColor(color(R.color.error))
                }
            }

            b.btnDeleteItem.setOnClickListener {
                MaterialAlertDialogBuilder(b.root.context)
                    .setTitle(b.root.context.getString(R.string.confirm_title))
                    .setMessage(b.root.context.getString(R.string.confirm_delete_image))
                    .setPositiveButton(b.root.context.getString(R.string.yes)) { _, _ ->
                        val positionToDelete = adapterPosition
                        if (positionToDelete != RecyclerView.NO_POSITION) onDelete(positionToDelete)
                    }
                    .setNegativeButton(b.root.context.getString(R.string.no), null)
                    .run { runCatching { show().apply { setCanceledOnTouchOutside(false) } } }
            }

            b.root.post { applyResponsiveLayout() }
        }

        /**
         * Binds a RacimoUiModel in RACIMO mode.
         * Renders dual-face card with per-face images, status badges,
         * capture/replace action buttons, compact summary metrics,
         * and the fused histogram. Handles expand-to-detail via
         * [showBunchDetailDialog].
         */
        fun bindRacimo(model: RacimoUiModel) {
            // Reset expanded state when rebinding
            isExpanded = false
            
            // Note: layoutPhotoPair and tvPhotoSectionTitle visibility
            // will be set by updateDetailsVisibility() based on isExpanded state
            b.tvPhotoALabel.visibility = View.VISIBLE
            b.tvPhotoAStatus.visibility = View.VISIBLE
            b.tvPhotoBStatus.visibility = View.VISIBLE
            b.spacePhotoPair.visibility = View.VISIBLE
            b.layoutPhotoB.visibility = View.VISIBLE
            b.layoutProgressBadges.visibility = View.VISIBLE
            b.tvPhotoProgressBadge.visibility = View.GONE
            b.tvFusedResultBadge.visibility = View.GONE
            b.tvPredictionInfo.visibility = View.GONE
            b.layoutFusionActions.visibility = View.GONE
            b.btnDeleteItem.text = b.root.context.getString(R.string.delete_bunch)
            b.btnDeleteItem.contentDescription = b.root.context.getString(R.string.cd_delete_bunch)
            b.tvPhotoALabel.text = b.root.context.getString(R.string.photo_a_front)
            b.tvPhotoBLabel.text = b.root.context.getString(R.string.photo_b_back)
            b.tvIndex.text = b.root.context.getString(R.string.bunch_title, model.racimoIndex)
            b.layoutCompactSummary.visibility = View.VISIBLE
            hideResultAndHistogram()

            val hasPhotoA = model.imageAPath != null || model.overlayAPath != null
            val hasPhotoB = model.imageBPath != null || model.overlayBPath != null
            val loadedPhotoCount = listOf(hasPhotoA, hasPhotoB).count { it }

            setPhotoStatus(b.tvPhotoAStatus, hasPhotoA)
            setPhotoStatus(b.tvPhotoBStatus, hasPhotoB)
            bindImage(
                cardView = b.cardPhotoA,
                imageView = b.ivPhoto,
                path = model.overlayAPath ?: model.imageAPath,
                loaded = hasPhotoA,
                loadedDescription = R.string.photo_a_loaded_cd,
                pendingDescription = R.string.photo_a_pending_cd
            )
            bindImage(
                cardView = b.cardPhotoB,
                imageView = b.ivPhotoB,
                path = model.overlayBPath ?: model.imageBPath,
                loaded = hasPhotoB,
                loadedDescription = R.string.photo_b_loaded_cd,
                pendingDescription = R.string.photo_b_pending_cd
            )
            configurePhotoPreview(model, hasPhotoA, hasPhotoB)
            configureFaceActions(
                primaryButton = b.btnTakePhotoA,
                secondaryButton = b.btnUploadPhotoA,
                overlayLayout = b.layoutPhotoAOverlayActions,
                replaceOverlay = b.btnPhotoAReplaceOverlay,
                deleteOverlay = b.btnPhotoADeleteOverlay,
                expandOverlay = b.btnPhotoAExpandOverlay,
                isLoaded = hasPhotoA,
                racimoIndex = model.racimoIndex,
                role = HomeViewModel.PhotoRole.A
            )
            configureFaceActions(
                primaryButton = b.btnTakePhotoB,
                secondaryButton = b.btnUploadPhotoB,
                overlayLayout = b.layoutPhotoBOverlayActions,
                replaceOverlay = b.btnPhotoBReplaceOverlay,
                deleteOverlay = b.btnPhotoBDeleteOverlay,
                expandOverlay = b.btnPhotoBExpandOverlay,
                isLoaded = hasPhotoB,
                racimoIndex = model.racimoIndex,
                role = HomeViewModel.PhotoRole.B
            )

            bindCompactSummary(model)
            configurePrimaryAction(model)
            when (model.state) {
                RacimoUiModel.State.EMPTY -> {
                    applyChip(R.string.status_pending, R.drawable.bg_chip_pending, R.color.chip_pending_text)
                    applyPhotoProgress(loadedPhotoCount, isFused = false)
                    b.progressItem.visibility = View.GONE
                }
                RacimoUiModel.State.FRONT_MISSING,
                RacimoUiModel.State.BACK_MISSING -> {
                    applyChip(stateTextRes(model.state), R.drawable.bg_chip_incomplete, R.color.chip_incomplete_text)
                    applyPhotoProgress(loadedPhotoCount, isFused = false)
                    b.progressItem.visibility = View.GONE
                }
                RacimoUiModel.State.PROCESSING -> {
                    applyChip(R.string.status_processing, R.drawable.bg_chip_info, R.color.chip_info_text)
                    applyPhotoProgress(loadedPhotoCount, isFused = false)
                    b.progressItem.visibility = View.VISIBLE
                }
                RacimoUiModel.State.COMPLETE -> {
                    applyChip(R.string.complete, R.drawable.bg_chip_fused, R.color.chip_fused_text)
                    applyPhotoProgress(loadedPhotoCount, isFused = true)
                    b.progressItem.visibility = View.GONE
                }
                RacimoUiModel.State.LOW_QUALITY -> {
                    applyChip(R.string.low_quality, R.drawable.bg_chip_incomplete, R.color.chip_incomplete_text)
                    applyPhotoProgress(loadedPhotoCount, isFused = true)
                    b.progressItem.visibility = View.GONE
                }
                RacimoUiModel.State.FRONT_ERROR,
                RacimoUiModel.State.BACK_ERROR,
                RacimoUiModel.State.PROCESSING_FAILED -> {
                    applyChip(stateTextRes(model.state), R.drawable.bg_chip_error, R.color.chip_error_text)
                    applyPhotoProgress(loadedPhotoCount, isFused = false)
                    b.progressItem.visibility = View.GONE
                }
            }

            b.btnDeleteItem.setOnClickListener {
                MaterialAlertDialogBuilder(b.root.context)
                    .setTitle(b.root.context.getString(R.string.confirm_title))
                    .setMessage(b.root.context.getString(R.string.confirm_delete_bunch))
                    .setPositiveButton(b.root.context.getString(R.string.yes)) { _, _ -> onDeleteRacimo(model.racimoIndex) }
                    .setNegativeButton(b.root.context.getString(R.string.no), null)
                    .run { runCatching { show().apply { setCanceledOnTouchOutside(false) } } }
            }

            // Handle expand/collapse toggle for details view
            b.btnToggleDetails.setOnClickListener {
                showBunchDetailDialog(model)
            }
            b.root.setOnClickListener { showBunchDetailDialog(model) }
            b.layoutCompactSummary.setOnClickListener { showBunchDetailDialog(model) }
            b.compactBarChart.setOnClickListener { showHistogramDetail(fusedForDisplay(model), isFused = true) }
            b.cardRepresentative.setOnClickListener { showRepresentativePreview(model) }

            // Set initial visibility state (compact view by default)
            updateDetailsVisibility(model)

            b.root.post { applyResponsiveLayout() }
        }

        private fun updateDetailsVisibility(model: RacimoUiModel) {
            // Toggle photo pair and section title visibility
            b.layoutPhotoPair.visibility = View.GONE
            b.tvPhotoSectionTitle.visibility = View.GONE
            b.btnToggleDetails.setText(R.string.view_details)
            b.btnToggleDetails.rotation = 0f
        }

        private fun animateContentChange(expand: Boolean) {
            // Simple fade animation for content transition
            val duration = 300L
            if (expand) {
                b.layoutPhotoPair.alpha = 0f
                b.layoutPhotoPair.animate()
                    .alpha(1f)
                    .duration = duration
            } else {
                b.layoutPhotoPair.animate()
                    .alpha(0f)
                    .duration = duration
            }
        }

        private fun bindCompactSummary(model: RacimoUiModel) {
            val representative = resolveRepresentativeBitmap(model)
            if (representative != null && !representative.isRecycled) {
                Log.d("RACIMO_IMG_BIND", "bindCompactSummary: bitmap RESOLVED OK")
                showLoadedBitmap(b.ivRepresentative, representative)
                setPhotoStroke(b.cardRepresentative, true)
            } else {
                Log.w("RACIMO_IMG_BIND", "bindCompactSummary: bitmap NULL or RECYCLED, showing placeholder")
                showUnavailablePlaceholder(b.ivRepresentative)
                b.cardRepresentative.strokeWidth = dp(1)
                b.cardRepresentative.setStrokeColor(
                    ColorStateList.valueOf(color(if (model.state.name.contains("ERROR")) R.color.error else R.color.md_border_soft))
                )
            }

            val fused = fusedForDisplay(model)
            if (fused != null) {
                b.tvCompactQty.text = fused.qty.toString()
                b.tvCompactMean.text = b.root.context.getString(R.string.metric_mean_compact, fused.mean)
                b.tvCompactMode.text = b.root.context.getString(R.string.metric_mode_compact, fused.mode)
                b.tvCompactStd.text = b.root.context.getString(R.string.metric_std_compact, fused.std)
                setupChart(b.compactBarChart, fused.bins, fused.pred, compact = true)
            } else {
                b.tvCompactQty.text = "--"
                b.tvCompactMean.text = "--"
                b.tvCompactMode.text = "--"
                b.tvCompactStd.text = "--"
                setupEmptyChart(b.compactBarChart, showMessage = false)
            }
        }

        private fun resolveRepresentativeBitmap(model: RacimoUiModel): Bitmap? {
            // Fallback chain: representative path -> overlay A -> image A -> overlay B -> image B
            Log.d("RACIMO_IMG_BIND", "resolve: repPath='${model.representativeImagePath}' " +
                "overlayA='${model.overlayAPath}' imgA='${model.imageAPath}' " +
                "overlayB='${model.overlayBPath}' imgB='${model.imageBPath}' " +
                "role='${model.selectedImageRole}'")

            val rep = decodeBitmap(model.representativeImagePath)
            if (rep != null && !rep.isRecycled) return rep

            Log.d("RACIMO_IMG_BIND", "resolve: trying overlayAPath")
            val overlayA = decodeBitmap(model.overlayAPath)
            if (overlayA != null && !overlayA.isRecycled) return overlayA

            Log.d("RACIMO_IMG_BIND", "resolve: trying imageAPath")
            val imgA = decodeBitmap(model.imageAPath)
            if (imgA != null && !imgA.isRecycled) return imgA

            Log.d("RACIMO_IMG_BIND", "resolve: trying overlayBPath")
            val overlayB = decodeBitmap(model.overlayBPath)
            if (overlayB != null && !overlayB.isRecycled) return overlayB

            Log.d("RACIMO_IMG_BIND", "resolve: trying imageBPath")
            val imgB = decodeBitmap(model.imageBPath)
            if (imgB != null && !imgB.isRecycled) return imgB

            Log.w("RACIMO_IMG_BIND", "resolve: ALL PATHS FAILED")
            return null
        }

        private fun fusedForDisplay(model: RacimoUiModel): CalPredict? {
            return model.fusedPrediction?.takeIf { model.hasValidFusedPrediction }
        }

        private fun configurePrimaryAction(model: RacimoUiModel) {
            val roleAction: Pair<Int, HomeViewModel.PhotoRole>? = when (model.state) {
                RacimoUiModel.State.FRONT_MISSING -> R.string.add_front_image to HomeViewModel.PhotoRole.A
                RacimoUiModel.State.BACK_MISSING -> R.string.add_back_image to HomeViewModel.PhotoRole.B
                RacimoUiModel.State.FRONT_ERROR -> R.string.replace_front_image to HomeViewModel.PhotoRole.A
                RacimoUiModel.State.BACK_ERROR -> R.string.replace_back_image to HomeViewModel.PhotoRole.B
                else -> null
            }

            when {
                roleAction != null -> {
                    b.btnPrimaryRacimoAction.visibility = View.VISIBLE
                    b.btnPrimaryRacimoAction.setText(roleAction.first)
                    b.btnPrimaryRacimoAction.setIconResource(R.drawable.ic_gallery)
                    b.btnPrimaryRacimoAction.setOnClickListener { onUploadPhoto(model.racimoIndex, roleAction.second) }
                }
                model.state == RacimoUiModel.State.PROCESSING_FAILED -> {
                    b.btnPrimaryRacimoAction.visibility = View.VISIBLE
                    b.btnPrimaryRacimoAction.setText(R.string.replace_image)
                    b.btnPrimaryRacimoAction.setIconResource(R.drawable.ic_gallery)
                    b.btnPrimaryRacimoAction.setOnClickListener { showReplaceRoleDialog(model.racimoIndex) }
                }
                model.state == RacimoUiModel.State.LOW_QUALITY -> {
                    b.btnPrimaryRacimoAction.visibility = View.VISIBLE
                    b.btnPrimaryRacimoAction.setText(R.string.replace_image)
                    b.btnPrimaryRacimoAction.setIconResource(R.drawable.ic_gallery)
                    b.btnPrimaryRacimoAction.setOnClickListener { showReplaceRoleDialog(model.racimoIndex) }
                }
                else -> {
                    b.btnPrimaryRacimoAction.visibility = View.GONE
                    b.btnPrimaryRacimoAction.setOnClickListener(null)
                }
            }
        }

        private fun showReplaceRoleDialog(racimoIndex: Int) {
            val options = arrayOf(
                b.root.context.getString(R.string.photo_a_front),
                b.root.context.getString(R.string.photo_b_back)
            )
            MaterialAlertDialogBuilder(b.root.context)
                .setTitle(b.root.context.getString(R.string.replace_which_image))
                .setItems(options) { _, which ->
                    val role = if (which == 0) HomeViewModel.PhotoRole.A else HomeViewModel.PhotoRole.B
                    showCaptureChoiceForRole(racimoIndex, role)
                }
                .setNegativeButton(b.root.context.getString(R.string.close), null)
                .run { runCatching { show() }.onFailure { Log.w("RACIMO_IMG_BIND", "showReplaceRoleDialog failed") } }
        }

        private fun showCaptureChoiceForRole(racimoIndex: Int, role: HomeViewModel.PhotoRole) {
            val roleLabel = b.root.context.getString(
                if (role == HomeViewModel.PhotoRole.A) R.string.photo_a_front else R.string.photo_b_back
            )
            MaterialAlertDialogBuilder(b.root.context)
                .setTitle(b.root.context.getString(R.string.replace_photo_sheet_title, roleLabel))
                .setItems(
                    arrayOf(
                        b.root.context.getString(R.string.capture_source_camera),
                        b.root.context.getString(R.string.capture_source_gallery)
                    )
                ) { _, which ->
                    if (which == 0) onTakePhoto(racimoIndex, role) else onUploadPhoto(racimoIndex, role)
                }
                .setNegativeButton(b.root.context.getString(R.string.close), null)
                .run { runCatching { show() }.onFailure { Log.w("RACIMO_IMG_BIND", "showCaptureChoiceForRole failed") } }
        }

        private fun showPhotoPreviewForModel(model: RacimoUiModel, initialRole: HomeViewModel.PhotoRole = HomeViewModel.PhotoRole.A) {
            val previews = buildPhotoPreviews(model)
            if (previews.isEmpty()) {
                MaterialAlertDialogBuilder(b.root.context)
                    .setTitle(b.root.context.getString(R.string.image_failed_to_load))
                    .setMessage(b.root.context.getString(R.string.image_failed_to_load_detail))
                    .setPositiveButton(b.root.context.getString(R.string.replace_image)) { _, _ ->
                        showReplaceRoleDialog(model.racimoIndex)
                    }
                    .setNegativeButton(b.root.context.getString(R.string.close), null)
                    .show()
                return
            }
            val initialIndex = previews.indexOfFirst { it.role == initialRole }.takeIf { it >= 0 } ?: 0
            showPhotoPreviewDialog(previews, initialIndex, model.racimoIndex)
        }

        private fun buildPhotoPreviews(model: RacimoUiModel): List<PhotoPreview> {
            return buildList {
                add(
                    PhotoPreview(
                        labelRes = R.string.photo_a_front,
                        role = HomeViewModel.PhotoRole.A,
                        path = model.overlayAPath ?: model.imageAPath
                    )
                )
                add(
                    PhotoPreview(
                        labelRes = R.string.photo_b_back,
                        role = HomeViewModel.PhotoRole.B,
                        path = model.overlayBPath ?: model.imageBPath
                    )
                )
            }.filter { !it.path.isNullOrBlank() }
        }

        private fun showBunchDetailDialog(model: RacimoUiModel) {
            if (!isContextValid()) return
            val dialog = BottomSheetDialog(b.root.context)
            val root = LinearLayout(b.root.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(18))
                setBackgroundColor(color(R.color.md_surface))
            }

            val header = LinearLayout(b.root.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isBaselineAligned = false
            }
            header.addView(
                TextView(b.root.context).apply {
                    text = b.root.context.getString(R.string.bunch_title, model.racimoIndex)
                    setTextColor(color(R.color.textPrimary))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            header.addView(
                TextView(b.root.context).apply {
                    text = b.root.context.getString(stateTextRes(model.state))
                    setBackgroundResource(stateBackgroundRes(model.state))
                    setTextColor(color(stateTextColorRes(model.state)))
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            )
            root.addView(header)

            val tabs = TabLayout(b.root.context).apply {
                addTab(newTab().setText(R.string.summary))
                addTab(newTab().setText(R.string.images))
            }
            root.addView(tabs)

            val content = FrameLayout(b.root.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            root.addView(content)

            fun render(tabIndex: Int) {
                content.removeAllViews()
                content.addView(
                    when (tabIndex) {
                        1 -> buildImagesTab(model, dialog)
                        else -> buildSummaryTab(model, dialog)
                    }
                )
            }
            tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) = render(tab.position)
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
            render(0)

            dialog.setContentView(root)
            dialog.setCanceledOnTouchOutside(true)
            runCatching { dialog.show() }.onFailure {
                Log.w("RACIMO_IMG_BIND", "Bunch detail dialog show failed")
            }
        }

        private fun buildSummaryTab(model: RacimoUiModel, dialog: Dialog): View {
            val root = LinearLayout(b.root.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, 0)
            }
            val top = LinearLayout(b.root.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isBaselineAligned = false
            }
            val representative = ImageView(b.root.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                val bitmap = resolveRepresentativeBitmap(model)
                if (bitmap != null && !bitmap.isRecycled) setImageBitmap(bitmap) else {
                    setImageResource(R.drawable.ic_gallery)
                    setColorFilter(color(R.color.textHint))
                }
                setOnClickListener { showPhotoPreviewForModel(model) }
            }
            top.addView(representative, LinearLayout.LayoutParams(dp(94), dp(94)))
            top.addView(
                TextView(b.root.context).apply {
                    val fused = fusedForDisplay(model)
                    text = if (fused != null) {
                        "${fused.qty}\n${context.getString(R.string.result_qty_label)}\n" +
                            context.getString(R.string.metric_mean_compact, fused.mean) + "\n" +
                            context.getString(R.string.metric_mode_compact, fused.mode) + "\n" +
                            context.getString(R.string.metric_std_compact, fused.std)
                    } else {
                        "--\n${context.getString(R.string.result_qty_label)}"
                    }
                    setTextColor(color(R.color.textPrimary))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(dp(14), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            root.addView(top)

            root.addView(TextView(b.root.context).apply {
                text = b.root.context.getString(if (fusedForDisplay(model) != null) R.string.all_images_valid else stateMessageRes(model.state))
                setTextColor(color(if (model.state == RacimoUiModel.State.COMPLETE) R.color.textSecondary else stateTextColorRes(model.state)))
                setPadding(0, dp(12), 0, dp(8))
            })

            val chart = BarChart(b.root.context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260))
            }
            setupInteractiveChart(chart, fusedForDisplay(model))
            root.addView(chart)
            root.addView(actionRow(model, dialog))
            return root
        }

        private fun buildImagesTab(model: RacimoUiModel, dialog: Dialog): View {
            return LinearLayout(b.root.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, 0)
                addView(imageRoleRow(model, HomeViewModel.PhotoRole.A, model.imageAState, model.overlayAPath ?: model.imageAPath, dialog))
                addView(imageRoleRow(model, HomeViewModel.PhotoRole.B, model.imageBState, model.overlayBPath ?: model.imageBPath, dialog))
                addView(makeButton(R.string.swap_front_back, R.drawable.ic_reload) {
                    dialog.dismiss()
                    onSwapFrontBack(model.racimoIndex)
                })
            }
        }

        private fun actionRow(model: RacimoUiModel, dialog: Dialog): View {
            return LinearLayout(b.root.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, 0)
                addView(makeButton(R.string.replace, R.drawable.ic_gallery) {
                    showReplaceRoleDialog(model.racimoIndex)
                })
                addView(makeButton(R.string.delete_bunch, R.drawable.ic_delete, danger = true) {
                    dialog.dismiss()
                    confirmDeleteBunch(model.racimoIndex)
                })
            }
        }

        private fun imageRoleRow(
            model: RacimoUiModel,
            role: HomeViewModel.PhotoRole,
            state: RacimoUiModel.ImageState,
            path: String?,
            dialog: Dialog
        ): View {
            val labelRes = if (role == HomeViewModel.PhotoRole.A) R.string.photo_a_front else R.string.photo_b_back
            return LinearLayout(b.root.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(14))
                addView(TextView(context).apply {
                    text = "${context.getString(labelRes).uppercase(Locale.getDefault())}  ${context.getString(imageStateTextRes(state))}"
                    setTextColor(color(imageStateColorRes(state)))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                val image = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val bitmap = decodeBitmap(path)
                    if (bitmap != null && !bitmap.isRecycled) setImageBitmap(bitmap) else {
                        setImageResource(R.drawable.ic_gallery)
                        setColorFilter(color(R.color.textHint))
                    }
                    setOnClickListener { showPhotoPreviewForModel(model, role) }
                }
                addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)).apply { topMargin = dp(8) })
                addView(makeButton(R.string.replace, R.drawable.ic_reload) {
                    dialog.dismiss()
                    showCaptureChoiceForRole(model.racimoIndex, role)
                })
                addView(makeButton(R.string.delete_photo, R.drawable.ic_delete, danger = true) {
                    dialog.dismiss()
                    confirmDeletePhoto(model.racimoIndex, role)
                })
            }
        }

        private fun makeButton(
            @StringRes textRes: Int,
            @DrawableRes iconRes: Int,
            danger: Boolean = false,
            onClick: () -> Unit
        ): MaterialButton {
            return MaterialButton(b.root.context).apply {
                setText(textRes)
                setIconResource(iconRes)
                isAllCaps = false
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = dp(8)
                backgroundTintList = ColorStateList.valueOf(color(if (danger) R.color.button_danger_bg else R.color.button_secondary_bg))
                strokeColor = ColorStateList.valueOf(color(if (danger) R.color.error else R.color.md_border_soft))
                strokeWidth = dp(1)
                setTextColor(color(if (danger) R.color.error else R.color.colorPrimaryDark))
                iconTint = ColorStateList.valueOf(color(if (danger) R.color.error else R.color.colorPrimaryDark))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                    topMargin = dp(8)
                }
                setOnClickListener { onClick() }
            }
        }

        private fun confirmDeleteBunch(racimoIndex: Int) {
            runCatching {
                MaterialAlertDialogBuilder(b.root.context)
                    .setTitle(b.root.context.getString(R.string.confirm_title))
                    .setMessage(b.root.context.getString(R.string.confirm_delete_bunch))
                    .setPositiveButton(b.root.context.getString(R.string.yes)) { _, _ -> onDeleteRacimo(racimoIndex) }
                    .setNegativeButton(b.root.context.getString(R.string.no), null)
                    .show()
                    .apply { setCanceledOnTouchOutside(false) }
            }.onFailure {
                Log.w("RACIMO_IMG_BIND", "confirmDeleteBunch dialog failed")
            }
        }

        private fun confirmDeletePhoto(racimoIndex: Int, role: HomeViewModel.PhotoRole) {
            runCatching {
                MaterialAlertDialogBuilder(b.root.context)
                    .setTitle(b.root.context.getString(R.string.confirm_title))
                    .setMessage(b.root.context.getString(R.string.confirm_delete_image))
                    .setPositiveButton(b.root.context.getString(R.string.yes)) { _, _ -> onDeletePhoto(racimoIndex, role) }
                    .setNegativeButton(b.root.context.getString(R.string.no), null)
                    .show()
                    .apply { setCanceledOnTouchOutside(false) }
            }.onFailure {
                Log.w("RACIMO_IMG_BIND", "confirmDeletePhoto dialog failed")
            }
        }

        @StringRes
        private fun stateTextRes(state: RacimoUiModel.State): Int = when (state) {
            RacimoUiModel.State.COMPLETE -> R.string.complete
            RacimoUiModel.State.FRONT_MISSING -> R.string.front_missing
            RacimoUiModel.State.BACK_MISSING -> R.string.back_missing
            RacimoUiModel.State.FRONT_ERROR -> R.string.front_image_error
            RacimoUiModel.State.BACK_ERROR -> R.string.back_image_error
            RacimoUiModel.State.LOW_QUALITY -> R.string.low_quality
            RacimoUiModel.State.PROCESSING_FAILED -> R.string.no_valid_grapes
            RacimoUiModel.State.PROCESSING -> R.string.processing
            RacimoUiModel.State.EMPTY -> R.string.status_pending
        }

        @StringRes
        private fun stateMessageRes(state: RacimoUiModel.State): Int = when (state) {
            RacimoUiModel.State.COMPLETE -> R.string.all_images_valid
            RacimoUiModel.State.FRONT_MISSING -> R.string.front_image_missing
            RacimoUiModel.State.BACK_MISSING -> R.string.back_image_missing
            RacimoUiModel.State.FRONT_ERROR -> R.string.front_image_error
            RacimoUiModel.State.BACK_ERROR -> R.string.back_image_error
            RacimoUiModel.State.LOW_QUALITY -> R.string.low_quality_image_detected
            RacimoUiModel.State.PROCESSING_FAILED -> R.string.processing_failed
            RacimoUiModel.State.PROCESSING -> R.string.processing
            RacimoUiModel.State.EMPTY -> R.string.empty_bunch_message
        }

        @DrawableRes
        private fun stateBackgroundRes(state: RacimoUiModel.State): Int = when (state) {
            RacimoUiModel.State.COMPLETE -> R.drawable.bg_chip_fused
            RacimoUiModel.State.FRONT_MISSING,
            RacimoUiModel.State.BACK_MISSING,
            RacimoUiModel.State.LOW_QUALITY -> R.drawable.bg_chip_incomplete
            RacimoUiModel.State.FRONT_ERROR,
            RacimoUiModel.State.BACK_ERROR,
            RacimoUiModel.State.PROCESSING_FAILED -> R.drawable.bg_chip_error
            RacimoUiModel.State.PROCESSING -> R.drawable.bg_chip_info
            RacimoUiModel.State.EMPTY -> R.drawable.bg_chip_pending
        }

        @ColorRes
        private fun stateTextColorRes(state: RacimoUiModel.State): Int = when (state) {
            RacimoUiModel.State.COMPLETE -> R.color.chip_fused_text
            RacimoUiModel.State.FRONT_MISSING,
            RacimoUiModel.State.BACK_MISSING,
            RacimoUiModel.State.LOW_QUALITY -> R.color.chip_incomplete_text
            RacimoUiModel.State.FRONT_ERROR,
            RacimoUiModel.State.BACK_ERROR,
            RacimoUiModel.State.PROCESSING_FAILED -> R.color.chip_error_text
            RacimoUiModel.State.PROCESSING -> R.color.chip_info_text
            RacimoUiModel.State.EMPTY -> R.color.chip_pending_text
        }

        @StringRes
        private fun imageStateTextRes(state: RacimoUiModel.ImageState): Int = when (state) {
            RacimoUiModel.ImageState.VALID -> R.string.valid
            RacimoUiModel.ImageState.MISSING -> R.string.missing
            RacimoUiModel.ImageState.FAILED -> R.string.failed
            RacimoUiModel.ImageState.LOW_QUALITY -> R.string.low_quality
            RacimoUiModel.ImageState.PROCESSING -> R.string.processing
        }

        @ColorRes
        private fun imageStateColorRes(state: RacimoUiModel.ImageState): Int = when (state) {
            RacimoUiModel.ImageState.VALID -> R.color.colorPrimaryDark
            RacimoUiModel.ImageState.MISSING,
            RacimoUiModel.ImageState.LOW_QUALITY -> R.color.warning
            RacimoUiModel.ImageState.FAILED -> R.color.error
            RacimoUiModel.ImageState.PROCESSING -> R.color.info
        }

        private fun showResult(prediction: CalPredict, isFused: Boolean) {
            showWithFade(b.layoutResult)
            b.tvResultFusedBadge.visibility = if (isFused) View.VISIBLE else View.GONE
            b.tvQtyValue.text = prediction.qty.toString()
            b.tvMetricMean.text = b.root.context.getString(R.string.metric_mean_value, prediction.mean)
            b.tvMetricMode.text = b.root.context.getString(R.string.metric_mode_value, prediction.mode)
            b.tvMetricStd.text = b.root.context.getString(R.string.metric_std_value, prediction.std)
        }

        private fun showHistogram(prediction: CalPredict, isFused: Boolean) {
            showWithFade(b.layoutHistogramSection)
            b.barChart.visibility = View.VISIBLE
            setupChartOrPlaceholder(b.barChart, prediction)
            b.barChart.setOnClickListener { showHistogramDetail(prediction, isFused) }
            b.btnHistogramDetail.setOnClickListener { showHistogramDetail(prediction, isFused) }
        }

        private fun hideResultAndHistogram() {
            b.layoutResult.visibility = View.GONE
            hideHistogram()
        }

        private fun hideHistogram() {
            b.layoutHistogramSection.visibility = View.GONE
            b.barChart.clear()
        }

        private fun showWithFade(view: View) {
            if (view.visibility != View.VISIBLE) {
                view.alpha = 0f
                view.visibility = View.VISIBLE
                view.animate().alpha(1f).setDuration(180L).start()
            } else {
                view.alpha = 1f
            }
        }

        private fun applyChip(
            @StringRes textRes: Int,
            @DrawableRes backgroundRes: Int,
            @ColorRes textColorRes: Int
        ) {
            b.tvReviewBadge.visibility = View.VISIBLE
            b.tvReviewBadge.text = b.root.context.getString(textRes)
            b.tvReviewBadge.setBackgroundResource(backgroundRes)
            b.tvReviewBadge.setTextColor(color(textColorRes))
        }

        private fun applyPhotoProgress(loadedCount: Int, isFused: Boolean) {
            val textRes = when {
                isFused -> R.string.status_fused
                loadedCount >= 2 -> R.string.photo_progress_complete
                loadedCount == 1 -> R.string.photo_progress_one
                else -> R.string.photo_progress_empty
            }
            val backgroundRes = if (isFused || loadedCount >= 2) {
                R.drawable.bg_chip_fused
            } else {
                R.drawable.bg_chip_pending
            }
            val textColorRes = if (isFused || loadedCount >= 2) R.color.chip_fused_text else R.color.chip_pending_text
            b.tvPhotoProgressBadge.text = b.root.context.getString(textRes)
            b.tvPhotoProgressBadge.setBackgroundResource(backgroundRes)
            b.tvPhotoProgressBadge.setTextColor(color(textColorRes))
            b.tvPhotoProgressBadge.visibility = View.VISIBLE
            b.tvFusedResultBadge.visibility = View.GONE
        }

        private fun configurePhotoPreview(model: RacimoUiModel, hasPhotoA: Boolean, hasPhotoB: Boolean) {
            val previews = buildList {
                if (hasPhotoA) {
                    add(
                        PhotoPreview(
                            labelRes = R.string.photo_a_front,
                            role = HomeViewModel.PhotoRole.A,
                            path = model.overlayAPath ?: model.imageAPath
                        )
                    )
                }
                if (hasPhotoB) {
                    add(
                        PhotoPreview(
                            labelRes = R.string.photo_b_back,
                            role = HomeViewModel.PhotoRole.B,
                            path = model.overlayBPath ?: model.imageBPath
                        )
                    )
                }
            }.filter { !it.path.isNullOrBlank() }

            if (previews.isEmpty()) return

            val indexA = previews.indexOfFirst { it.labelRes == R.string.photo_a_front }.coerceAtLeast(0)
            val indexB = previews.indexOfFirst { it.labelRes == R.string.photo_b_back }.coerceAtLeast(0)
            val openA = View.OnClickListener { showPhotoPreviewDialog(previews, indexA, model.racimoIndex) }
            val openB = View.OnClickListener { showPhotoPreviewDialog(previews, indexB, model.racimoIndex) }

            if (hasPhotoA) {
                b.ivPhoto.setOnClickListener(openA)
                b.cardPhotoA.setOnClickListener(openA)
                b.btnPhotoAExpandOverlay.setOnClickListener(openA)
            }
            if (hasPhotoB) {
                b.ivPhotoB.setOnClickListener(openB)
                b.cardPhotoB.setOnClickListener(openB)
                b.btnPhotoBExpandOverlay.setOnClickListener(openB)
            }
        }

        private fun showRepresentativePreview(model: RacimoUiModel) {
            showPhotoPreviewForModel(model)
        }

        private fun showLoadedBitmap(imageView: ImageView, bitmap: Bitmap) {
            imageView.imageTintList = null
            imageView.clearColorFilter()
            imageView.alpha = 1f
            imageView.setImageBitmap(bitmap)
            Log.d("RACIMO_IMG_BIND", "showLoadedBitmap: id=${imageView.id} w=${imageView.width}h=${imageView.height} vis=${imageView.visibility} alpha=${imageView.alpha} drawable=${imageView.drawable?.javaClass?.simpleName} scale=${imageView.scaleType} tint=${imageView.imageTintList} bg=${imageView.background?.javaClass?.simpleName}")
        }

        private fun showUnavailablePlaceholder(imageView: ImageView) {
            imageView.imageTintList = ColorStateList.valueOf(color(R.color.textHint))
            imageView.setImageResource(R.drawable.ic_gallery)
            imageView.alpha = 0.62f
            Log.d("RACIMO_IMG_BIND", "showUnavailablePlaceholder: id=${imageView.id}")
        }

        private fun setPhotoStatus(statusView: android.widget.TextView, loaded: Boolean) {
            statusView.text = statusView.context.getString(
                if (loaded) R.string.photo_uploaded else R.string.photo_pending
            )
            statusView.setTextColor(color(if (loaded) R.color.colorPrimaryDark else R.color.textSecondary))
        }

        private fun configureFaceActions(
            primaryButton: MaterialButton,
            secondaryButton: MaterialButton,
            overlayLayout: LinearLayout,
            replaceOverlay: MaterialButton,
            deleteOverlay: MaterialButton,
            expandOverlay: MaterialButton,
            isLoaded: Boolean,
            racimoIndex: Int,
            role: HomeViewModel.PhotoRole
        ) {
            val roleLabel = primaryButton.context.getString(
                if (role == HomeViewModel.PhotoRole.A) R.string.photo_a_front else R.string.photo_b_back
            )
            val inlineRow = primaryButton.parent as? View
            inlineRow?.visibility = if (isLoaded) View.GONE else View.VISIBLE
            overlayLayout.visibility = if (isLoaded) View.VISIBLE else View.GONE

            val primaryTextColor = color(R.color.colorPrimaryDark)
            val primaryBg = color(R.color.button_secondary_bg)
            val primaryStroke = color(R.color.md_border_soft)
            val secondaryTextColor = color(R.color.colorPrimaryDark)
            val secondaryBg = color(R.color.button_secondary_bg)
            val secondaryStroke = color(R.color.md_border_soft)

            primaryButton.text = primaryButton.context.getString(R.string.take_photo)
            primaryButton.setIconResource(R.drawable.ic_camera)
            primaryButton.iconTint = ColorStateList.valueOf(primaryTextColor)
            primaryButton.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            primaryButton.iconPadding = dp(6)
            primaryButton.backgroundTintList = ColorStateList.valueOf(primaryBg)
            primaryButton.strokeColor = ColorStateList.valueOf(primaryStroke)
            primaryButton.setTextColor(primaryTextColor)
            primaryButton.contentDescription = primaryButton.context.getString(
                R.string.cd_action_for_photo,
                primaryButton.text,
                roleLabel
            )

            secondaryButton.text = secondaryButton.context.getString(R.string.upload_photo)
            secondaryButton.setIconResource(R.drawable.ic_gallery)
            secondaryButton.iconTint = ColorStateList.valueOf(secondaryTextColor)
            secondaryButton.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            secondaryButton.iconPadding = dp(6)
            secondaryButton.backgroundTintList = ColorStateList.valueOf(secondaryBg)
            secondaryButton.strokeColor = ColorStateList.valueOf(secondaryStroke)
            secondaryButton.setTextColor(secondaryTextColor)
            secondaryButton.contentDescription = secondaryButton.context.getString(
                R.string.cd_action_for_photo,
                secondaryButton.text,
                roleLabel
            )

            primaryButton.setOnClickListener { onTakePhoto(racimoIndex, role) }
            secondaryButton.setOnClickListener {
                onUploadPhoto(racimoIndex, role)
            }

            replaceOverlay.contentDescription = replaceOverlay.context.getString(R.string.cd_replace_photo, roleLabel)
            deleteOverlay.contentDescription = deleteOverlay.context.getString(R.string.cd_delete_photo, roleLabel)
            expandOverlay.contentDescription = expandOverlay.context.getString(R.string.cd_expand_photo, roleLabel)
            replaceOverlay.setOnClickListener {
                MaterialAlertDialogBuilder(replaceOverlay.context)
                    .setTitle(replaceOverlay.context.getString(R.string.replace_photo_sheet_title, roleLabel))
                    .setItems(
                        arrayOf(
                            replaceOverlay.context.getString(R.string.capture_source_camera),
                            replaceOverlay.context.getString(R.string.capture_source_gallery)
                        )
                    ) { _, which ->
                        if (which == 0) onTakePhoto(racimoIndex, role) else onUploadPhoto(racimoIndex, role)
                    }
                    .setNegativeButton(replaceOverlay.context.getString(R.string.close), null)
                    .run { runCatching { show().apply { setCanceledOnTouchOutside(true) } } }
            }
            deleteOverlay.setOnClickListener {
                MaterialAlertDialogBuilder(deleteOverlay.context)
                    .setTitle(deleteOverlay.context.getString(R.string.confirm_title))
                    .setMessage(deleteOverlay.context.getString(R.string.confirm_delete_image))
                    .setPositiveButton(deleteOverlay.context.getString(R.string.yes)) { _, _ ->
                        onDeletePhoto(racimoIndex, role)
                    }
                    .setNegativeButton(deleteOverlay.context.getString(R.string.no), null)
                    .run { runCatching { show().apply { setCanceledOnTouchOutside(false) } } }
            }
        }

        private fun bindImage(
            cardView: MaterialCardView,
            imageView: ImageView,
            path: String?,
            loaded: Boolean,
            @StringRes loadedDescription: Int,
            @StringRes pendingDescription: Int
        ) {
            val bitmap = decodeBitmap(path)
            if (bitmap != null && !bitmap.isRecycled) {
                imageView.clearColorFilter()
                imageView.alpha = 1f
                imageView.setImageBitmap(bitmap)
                imageView.setOnClickListener { if (!bitmap.isRecycled) onImageClick(bitmap) }
                setPhotoStroke(cardView, true)
            } else {
                imageView.setImageResource(R.drawable.ic_gallery)
                imageView.setColorFilter(color(R.color.textHint))
                imageView.alpha = 0.62f
                imageView.setOnClickListener(null)
                setPhotoStroke(cardView, false)
            }
            cardView.contentDescription = cardView.context.getString(
                if (loaded && bitmap != null) loadedDescription else pendingDescription
            )
        }

        private fun setPhotoStroke(cardView: MaterialCardView, loaded: Boolean) {
            cardView.strokeWidth = dp(1)
            cardView.setStrokeColor(
                ColorStateList.valueOf(color(if (loaded) R.color.colorPrimaryMedium else R.color.md_border_soft))
            )
            cardView.setCardBackgroundColor(color(if (loaded) R.color.md_surface else R.color.md_surface_soft))
        }

        private fun applyResponsiveLayout() {
            val density = b.root.resources.displayMetrics.density
            val widthDp = b.root.width.takeIf { it > 0 }?.let { it / density } ?: return
            val fontScale = b.root.resources.configuration.fontScale
            // Stack photos vertically on narrow screens (<360dp) or large fonts
            val stackPhotos = widthDp < 360f || fontScale >= 1.25f
            val stackButtons = widthDp < 430f || fontScale >= 1.15f
            val stackMetrics = widthDp < 360f || fontScale >= 1.3f

            b.layoutPhotoPair.orientation = if (stackPhotos) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setWeightedChild(b.layoutPhotoA, stackPhotos)
            setWeightedChild(b.layoutPhotoB, stackPhotos)
            setSpacer(b.spacePhotoPair, stackPhotos, 10)

            setButtonRow(b.layoutPhotoAButtonRow, b.btnTakePhotoA, b.btnUploadPhotoA, b.spacePhotoAButtons, stackButtons)
            setButtonRow(b.layoutPhotoBButtonRow, b.btnTakePhotoB, b.btnUploadPhotoB, b.spacePhotoBButtons, stackButtons)
            setMetrics(stackMetrics)
        }

        private fun setWeightedChild(view: View, stacked: Boolean) {
            val params = (view.layoutParams as? LinearLayout.LayoutParams) ?: return
            params.width = if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else 0
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.weight = if (stacked) 0f else 1f
            params.setMargins(0, 0, 0, 0)
            view.layoutParams = params
        }

        private fun setSpacer(space: Space, vertical: Boolean, sizeDp: Int) {
            val params = (space.layoutParams as? LinearLayout.LayoutParams) ?: return
            params.width = if (vertical) 1 else dp(sizeDp)
            params.height = if (vertical) dp(sizeDp) else 1
            params.weight = 0f
            space.layoutParams = params
        }

        private fun setButtonParams(button: MaterialButton, stacked: Boolean, topMargin: Int) {
            val params = (button.layoutParams as? LinearLayout.LayoutParams) ?: return
            params.width = if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else 0
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.weight = if (stacked) 0f else 1f
            params.setMargins(0, if (stacked) dp(topMargin) else 0, 0, 0)
            button.layoutParams = params
        }

        private fun setMetrics(stacked: Boolean) {
            b.layoutMetrics.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            listOf(b.tvMetricMean, b.tvMetricMode, b.tvMetricStd).forEachIndexed { index, textView ->
                val params = (textView.layoutParams as? LinearLayout.LayoutParams) ?: return@forEachIndexed
                params.width = if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else 0
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                params.weight = if (stacked) 0f else 1f
                params.setMargins(0, if (stacked && index > 0) dp(6) else 0, 0, 0)
                textView.layoutParams = params
            }
        }

        private fun setButtonRow(
            row: LinearLayout,
            firstButton: MaterialButton,
            secondButton: MaterialButton,
            space: Space,
            stacked: Boolean
        ) {
            row.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setButtonParams(firstButton, stacked, topMargin = 0)
            setSpacer(space, stacked, 8)
            setButtonParams(secondButton, stacked, topMargin = 0)
        }

        private fun setupChart(barChart: BarChart, bins: List<Float>, values: List<Int>, compact: Boolean = false) {
            if (bins.isEmpty() || values.isEmpty() || bins.size != values.size) {
                setupEmptyChart(barChart)
                return
            }

            val entries = values.mapIndexed { index, value -> BarEntry(index.toFloat(), value.toFloat()) }
            val barDataSet = BarDataSet(entries, "").apply {
                color = color(R.color.histogram_end)
                setDrawValues(false)
            }

            barChart.setRenderer(
                RoundedBarChartRenderer(
                    barChart,
                    barChart.animator,
                    barChart.viewPortHandler,
                    dp(5).toFloat()
                )
            )
            barChart.data = BarData(barDataSet).apply { barWidth = 0.64f }

            if (compact) {
                barChart.xAxis.isEnabled = false
                barChart.axisLeft.isEnabled = false
                barChart.setExtraOffsets(0f, 0f, 0f, 2f)
            } else {
                val labels = bins.map { String.format(Locale.getDefault(), "%.1f", it) }
                with(barChart.xAxis) {
                    isEnabled = true
                    position = XAxis.XAxisPosition.BOTTOM
                    granularity = 1f
                    textColor = color(R.color.histogram_axis)
                    textSize = 10f
                    yOffset = 6f
                    labelRotationAngle = if (labels.size > 7) -30f else 0f
                    valueFormatter = IndexAxisValueFormatter(labels)
                    setAvoidFirstLastClipping(true)
                    setDrawGridLines(false)
                    setDrawAxisLine(false)
                }

                with(barChart.axisLeft) {
                    isEnabled = true
                    axisMinimum = 0f
                    granularity = 1f
                    textColor = color(R.color.histogram_axis)
                    textSize = 10f
                    gridColor = color(R.color.histogram_grid)
                    gridLineWidth = 0.6f
                    setDrawAxisLine(false)
                }

                barChart.setExtraOffsets(4f, 6f, 4f, 8f)
            }

            barChart.axisRight.isEnabled = false
            barChart.description.isEnabled = false
            barChart.legend.isEnabled = false
            barChart.setTouchEnabled(false)
            barChart.setScaleEnabled(false)
            barChart.setPinchZoom(false)
            barChart.isDoubleTapToZoomEnabled = false
            barChart.setFitBars(true)
            barChart.minOffset = 0f
            barChart.setNoDataText("")
            barChart.animateY(450)
            barChart.invalidate()
        }

        private fun setupChartOrPlaceholder(barChart: BarChart, prediction: CalPredict?, showEmptyMessage: Boolean = true) {
            if (prediction == null || prediction.bins.isEmpty() || prediction.pred.isEmpty()) {
                setupEmptyChart(barChart, showMessage = showEmptyMessage)
            } else {
                setupChart(barChart, prediction.bins, prediction.pred)
            }
        }

        private fun setupEmptyChart(barChart: BarChart, showMessage: Boolean = true) {
            barChart.clear()
            barChart.setNoDataText(if (showMessage) barChart.context.getString(R.string.no_size_distribution_available) else "")
            barChart.setNoDataTextColor(color(R.color.textSecondary))
            barChart.description.isEnabled = false
            barChart.legend.isEnabled = false
            barChart.axisLeft.isEnabled = false
            barChart.axisRight.isEnabled = false
            barChart.xAxis.isEnabled = false
            barChart.invalidate()
        }

        private fun setupInteractiveChart(barChart: BarChart, prediction: CalPredict?) {
            if (prediction == null || prediction.bins.isEmpty() || prediction.pred.isEmpty()) {
                setupEmptyChart(barChart)
                return
            }
            setupChart(barChart, prediction.bins, prediction.pred)
            barChart.setTouchEnabled(true)
            barChart.setHighlightPerTapEnabled(true)
            barChart.marker = HistogramMarkerView(barChart.context, prediction.bins)
            barChart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                override fun onValueSelected(e: Entry, h: Highlight) = Unit
                override fun onNothingSelected() = Unit
            })
            barChart.invalidate()
        }

        private fun showHistogramDetail(prediction: CalPredict?, isFused: Boolean) {
            val container = LinearLayout(b.root.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(8), dp(4), 0)
            }

            if (prediction == null || prediction.bins.isEmpty() || prediction.pred.isEmpty()) {
                container.addView(
                    TextView(b.root.context).apply {
                        text = context.getString(R.string.no_size_distribution_available)
                        setTextColor(color(R.color.textSecondary))
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                        gravity = Gravity.CENTER
                        setPadding(0, dp(42), 0, dp(42))
                    }
                )
                runCatching {
                    MaterialAlertDialogBuilder(b.root.context)
                        .setTitle(b.root.context.getString(R.string.size_distribution))
                        .setView(container)
                        .setPositiveButton(b.root.context.getString(R.string.close), null)
                        .show()
                        .apply { setCanceledOnTouchOutside(false) }
                }.onFailure {
                    Log.w("RACIMO_IMG_BIND", "Histogram detail empty dialog failed")
                }
                return
            }

            if (isFused) {
                container.addView(
                    TextView(b.root.context).apply {
                        text = context.getString(R.string.fused_result_badge)
                        setTextColor(color(R.color.chip_fused_text))
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        background = ContextCompat.getDrawable(context, R.drawable.bg_chip_fused)
                        setPadding(dp(10), dp(5), dp(10), dp(5))
                    }
                )
            }

            container.addView(
                TextView(b.root.context).apply {
                    text = prediction.qty.toString()
                    setTextColor(color(R.color.textPrimary))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 34f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, dp(8), 0, 0)
                }
            )
            container.addView(
                TextView(b.root.context).apply {
                    text = context.getString(R.string.result_qty_label)
                    setTextColor(color(R.color.textSecondary))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            )

            val metrics = LinearLayout(b.root.context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                setPadding(0, dp(12), 0, dp(8))
            }
            listOf(
                b.root.context.getString(R.string.metric_mean_value, prediction.mean),
                b.root.context.getString(R.string.metric_mode_value, prediction.mode),
                b.root.context.getString(R.string.metric_std_value, prediction.std)
            ).forEach { metricText ->
                metrics.addView(
                    TextView(b.root.context).apply {
                        text = metricText
                        setTextColor(color(R.color.textPrimary))
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            container.addView(metrics)

            val chart = BarChart(b.root.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(320)
                )
                minimumHeight = dp(280)
                contentDescription = context.getString(R.string.cd_histogram)
            }
            setupChart(chart, prediction.bins, prediction.pred)
            container.addView(chart)

            runCatching {
                MaterialAlertDialogBuilder(b.root.context)
                    .setTitle(b.root.context.getString(R.string.size_distribution))
                    .setView(container)
                    .setPositiveButton(b.root.context.getString(R.string.close), null)
                    .show()
                    .apply { setCanceledOnTouchOutside(false) }
            }
        }

        private fun showPhotoPreviewDialog(previews: List<PhotoPreview>, initialIndex: Int, racimoIndex: Int) {
            if (!isContextValid()) return
            val context = b.root.context

            val dialog = Dialog(context)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val root = FrameLayout(context).apply {
                setBackgroundColor(color(R.color.fullscreen_scrim))
            }

            val pager = ViewPager2(context).apply {
                adapter = PhotoPreviewAdapter(previews)
                setCurrentItem(initialIndex.coerceIn(0, previews.lastIndex), false)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                offscreenPageLimit = previews.size.coerceAtLeast(1)
            }

            root.addView(pager)

            val actionBar = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(18))
                background = ColorDrawable(color(R.color.overlay_panel_bg))
            }
            val roleIndicator = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextColor(color(R.color.overlay_icon))
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            actionBar.addView(roleIndicator)

            val buttonGrid = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            val replaceButton = overlayTextButton(R.string.replace)
            val setFrontButton = overlayTextButton(R.string.set_as_front)
            val setBackButton = overlayTextButton(R.string.set_as_back)
            val deleteButton = overlayTextButton(R.string.delete_photo, danger = true)

            fun buttonRow(vararg buttons: MaterialButton): LinearLayout {
                return LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    isBaselineAligned = false
                    buttons.forEachIndexed { index, button ->
                        addView(
                            button,
                            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                                if (index > 0) leftMargin = dp(8)
                            }
                        )
                    }
                }
            }

            buttonGrid.addView(buttonRow(replaceButton, setFrontButton))
            buttonGrid.addView(
                buttonRow(setBackButton, deleteButton),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            )
            actionBar.addView(buttonGrid)
            root.addView(
                actionBar,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            )

            fun bindOverlayActions(position: Int) {
                val preview = previews[position.coerceIn(0, previews.lastIndex)]
                roleIndicator.text = context.getString(preview.labelRes).uppercase(Locale.getDefault())
                replaceButton.setOnClickListener {
                    dialog.dismiss()
                    showCaptureChoiceForRole(racimoIndex, preview.role)
                }
                setFrontButton.isEnabled = preview.role != HomeViewModel.PhotoRole.A
                setBackButton.isEnabled = preview.role != HomeViewModel.PhotoRole.B
                setFrontButton.alpha = if (setFrontButton.isEnabled) 1f else 0.45f
                setBackButton.alpha = if (setBackButton.isEnabled) 1f else 0.45f
                setFrontButton.setOnClickListener {
                    if (preview.role != HomeViewModel.PhotoRole.A) {
                        dialog.dismiss()
                        onSwapFrontBack(racimoIndex)
                    }
                }
                setBackButton.setOnClickListener {
                    if (preview.role != HomeViewModel.PhotoRole.B) {
                        dialog.dismiss()
                        onSwapFrontBack(racimoIndex)
                    }
                }
                deleteButton.setOnClickListener {
                    dialog.dismiss()
                    confirmDeletePhoto(racimoIndex, preview.role)
                }
            }
            bindOverlayActions(initialIndex.coerceIn(0, previews.lastIndex))
            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    bindOverlayActions(position)
                }
            })

            val closeButton = MaterialButton(context).apply {
                setIconResource(R.drawable.ic_close)
                iconTint = ColorStateList.valueOf(color(R.color.overlay_icon))
                backgroundTintList = ColorStateList.valueOf(color(R.color.overlay_button_bg))
                cornerRadius = dp(22)
                minWidth = dp(44)
                minHeight = dp(44)
                insetTop = 0
                insetBottom = 0
                text = ""
                contentDescription = context.getString(R.string.fullscreen_close)
                setOnClickListener { dialog.dismiss() }
            }

            root.addView(
                closeButton,
                FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dp(18), dp(18), 0)
                }
            )

            dialog.setContentView(root)
            dialog.setCanceledOnTouchOutside(true)
            runCatching { dialog.show() }.onFailure { return }
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }

        private fun overlayTextButton(@StringRes textRes: Int, danger: Boolean = false): MaterialButton {
            return MaterialButton(b.root.context).apply {
                setText(textRes)
                isAllCaps = false
                minWidth = 0
                insetTop = 0
                insetBottom = 0
                textSize = 11f
                maxLines = 2
                includeFontPadding = false
                gravity = Gravity.CENTER
                setPadding(dp(6), 0, dp(6), 0)
                backgroundTintList = ColorStateList.valueOf(color(R.color.overlay_button_bg))
                setTextColor(color(if (danger) R.color.error else R.color.overlay_icon))
            }
        }

        private fun isContextValid(): Boolean {
            val ctx = b.root.context ?: return false
            return ctx !is android.app.Activity || !(ctx as android.app.Activity).isFinishing
        }

        private fun dp(value: Int): Int = (value * b.root.resources.displayMetrics.density).toInt()

        private fun color(@ColorRes colorRes: Int): Int =
            ContextCompat.getColor(b.root.context, colorRes)

        private fun decodeBitmap(path: String?): Bitmap? = runCatching {
            if (path.isNullOrBlank()) {
                Log.v("RACIMO_IMG_BIND", "decodeBitmap: path is null/blank")
                return@runCatching null
            }
            Log.d("RACIMO_IMG_BIND", "decodeBitmap: trying path='$path'")

            // Handle content:// URIs (Gallery) and file:// paths separately

            return@runCatching when {
                path.startsWith("content://") -> {
                    val uri = Uri.parse(path)
                    b.root.context.contentResolver.openInputStream(uri)?.use { stream ->
                        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(stream, null, probe)
                        if (probe.outWidth <= 0 || probe.outHeight <= 0) {
                            Log.w("RACIMO_IMG_BIND", "decodeBitmap: content:// bounds invalid w=${probe.outWidth} h=${probe.outHeight}")
                            return@runCatching null
                        }
                        var sample = 1
                        while (probe.outWidth / sample > 512 || probe.outHeight / sample > 512) {
                            sample *= 2
                        }
                        b.root.context.contentResolver.openInputStream(uri)?.use { retry ->
                            BitmapFactory.decodeStream(retry, null, BitmapFactory.Options().apply { inSampleSize = sample })
                        }
                    }
                }
                else -> {
                    val cleanPath = path.replace("file://", "")
                    val file = File(cleanPath)
                    if (!file.exists()) {
                        Log.w("RACIMO_IMG_BIND", "decodeBitmap: file NOT FOUND at '$cleanPath'")
                        return@runCatching null
                    }
                    if (file.length() <= 0) {
                        Log.w("RACIMO_IMG_BIND", "decodeBitmap: file EMPTY at '$cleanPath'")
                        return@runCatching null
                    }
                    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(cleanPath, probe)
                    if (probe.outWidth <= 0 || probe.outHeight <= 0) {
                        Log.w("RACIMO_IMG_BIND", "decodeBitmap: bounds invalid w=${probe.outWidth} h=${probe.outHeight} for '$cleanPath'")
                        return@runCatching null
                    }
                    var sample = 1
                    while (probe.outWidth / sample > 512 || probe.outHeight / sample > 512) {
                        sample *= 2
                    }
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bitmap = BitmapFactory.decodeFile(cleanPath, opts)
                    if (bitmap == null) {
                        Log.w("RACIMO_IMG_BIND", "decodeBitmap: decode returned null for '$cleanPath'")
                    }
                    bitmap
                }
            }
        }.onFailure { e ->
            Log.e("RACIMO_IMG_BIND", "decodeBitmap: exception: ${e.message}", e)
        }.getOrNull()

        private fun decodePreviewBitmap(path: String?): Bitmap? = runCatching {
            val cleanPath = path?.replace("file://", "") ?: return@runCatching null
            val file = File(cleanPath)
            if (!file.exists() || file.length() <= 0) return@runCatching null
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(cleanPath, probe)
            var sample = 1
            while (probe.outWidth / sample > 1400 || probe.outHeight / sample > 1400) {
                sample *= 2
            }
            BitmapFactory.decodeFile(cleanPath, BitmapFactory.Options().apply { inSampleSize = sample })
        }.onFailure { e ->
            Log.e("ImagePredictionAdapter", "Error decoding preview bitmap: ${e.message}", e)
        }.getOrNull()

        private data class PhotoPreview(
            @StringRes val labelRes: Int,
            val role: HomeViewModel.PhotoRole,
            val path: String?
        )

        private inner class PhotoPreviewAdapter(
            private val previews: List<PhotoPreview>
        ) : RecyclerView.Adapter<PhotoPreviewAdapter.PhotoPreviewViewHolder>() {

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoPreviewViewHolder {
                val container = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, 0)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                val label = TextView(parent.context).apply {
                    gravity = Gravity.CENTER
                    setTextColor(color(R.color.textPrimary))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val image = ZoomableImageView(parent.context).apply {
                    scaleType = ImageView.ScaleType.MATRIX
                    setPadding(dp(8), dp(6), dp(8), dp(16))
                }
                container.addView(
                    label,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                container.addView(
                    image,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    ).apply { topMargin = dp(10) }
                )
                return PhotoPreviewViewHolder(container, label, image)
            }

            override fun onBindViewHolder(holder: PhotoPreviewViewHolder, position: Int) {
                val preview = previews[position]
                holder.label.text = holder.itemView.context.getString(preview.labelRes)
                val bitmap = decodePreviewBitmap(preview.path)
                if (bitmap != null && !bitmap.isRecycled) {
                    holder.image.setImageBitmap(bitmap)
                } else {
                    holder.image.setImageResource(R.drawable.ic_gallery)
                    holder.image.setColorFilter(color(R.color.textHint))
                }
            }

            override fun getItemCount(): Int = previews.size

            inner class PhotoPreviewViewHolder(
                itemView: View,
                val label: TextView,
                val image: ImageView
            ) : RecyclerView.ViewHolder(itemView)
        }

        private class ZoomableImageView(context: android.content.Context) : AppCompatImageView(context) {
            private val drawMatrix = Matrix()
            private val savedMatrix = Matrix()
            private val start = PointF()
            private var normalizedScale = 1f
            private var mode = NONE

            private val scaleDetector = ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val previousScale = normalizedScale
                        normalizedScale = (normalizedScale * detector.scaleFactor).coerceIn(1f, 4f)
                        val scaleFactor = normalizedScale / previousScale
                        drawMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                        imageMatrix = drawMatrix
                        parent?.requestDisallowInterceptTouchEvent(normalizedScale > 1.02f)
                        return true
                    }
                }
            )

            override fun setImageBitmap(bm: Bitmap?) {
                super.setImageBitmap(bm)
                post { resetToFitCenter() }
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                scaleDetector.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        savedMatrix.set(drawMatrix)
                        start.set(event.x, event.y)
                        mode = DRAG
                        parent?.requestDisallowInterceptTouchEvent(normalizedScale > 1.02f)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (mode == DRAG && normalizedScale > 1.02f && !scaleDetector.isInProgress) {
                            drawMatrix.set(savedMatrix)
                            drawMatrix.postTranslate(event.x - start.x, event.y - start.y)
                            imageMatrix = drawMatrix
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        mode = NONE
                        parent?.requestDisallowInterceptTouchEvent(false)
                        if (normalizedScale <= 1.02f) resetToFitCenter()
                    }
                }
                return true
            }

            private fun resetToFitCenter() {
                val drawable = drawable ?: return
                if (width == 0 || height == 0) return
                if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return
                val availableWidth = width - paddingLeft - paddingRight
                val availableHeight = height - paddingTop - paddingBottom
                if (availableWidth <= 0 || availableHeight <= 0) return
                val scale = min(
                    availableWidth.toFloat() / drawable.intrinsicWidth.toFloat(),
                    availableHeight.toFloat() / drawable.intrinsicHeight.toFloat()
                )
                val dx = paddingLeft + (availableWidth - drawable.intrinsicWidth * scale) / 2f
                val dy = paddingTop + (availableHeight - drawable.intrinsicHeight * scale) / 2f
                drawMatrix.reset()
                drawMatrix.postScale(scale, scale)
                drawMatrix.postTranslate(dx, dy)
                imageMatrix = drawMatrix
                normalizedScale = 1f
            }

            private companion object {
                const val NONE = 0
                const val DRAG = 1
            }
        }

        private inner class HistogramMarkerView(
            context: android.content.Context,
            private val bins: List<Float>
        ) : com.github.mikephil.charting.components.MarkerView(context, R.layout.view_histogram_tooltip) {
            private val tooltipText: TextView = findViewById(R.id.tvTooltip)

            override fun refreshContent(entry: Entry, highlight: Highlight) {
                val index = entry.x.toInt()
                val bin = bins.getOrNull(index)
                val count = entry.y.toInt()
                tooltipText.text = if (bin != null) {
                    String.format("%.1f mm • %,d %s", bin, count, context.getString(R.string.grapes_unit))
                } else {
                    ""
                }
                super.refreshContent(entry, highlight)
            }
        }

        private class RoundedBarChartRenderer(
            chart: BarChart,
            animator: ChartAnimator,
            viewPortHandler: ViewPortHandler,
            private val radiusPx: Float
        ) : BarChartRenderer(chart, animator, viewPortHandler) {
            private val roundedBar = RectF()

            override fun drawDataSet(c: Canvas, dataSet: IBarDataSet, index: Int) {
                val trans = mChart.getTransformer(dataSet.axisDependency)
                val phaseX = mAnimator.phaseX
                val phaseY = mAnimator.phaseY
                val buffer = mBarBuffers[index]

                buffer.setPhases(phaseX, phaseY)
                buffer.setDataSet(index)
                buffer.setInverted(mChart.isInverted(dataSet.axisDependency))
                buffer.setBarWidth(mChart.barData.barWidth)
                buffer.feed(dataSet)
                trans.pointValuesToPixel(buffer.buffer)

                val isSingleColor = dataSet.colors.size == 1
                if (isSingleColor) mRenderPaint.color = dataSet.color

                for (j in 0 until buffer.size() step 4) {
                    if (!mViewPortHandler.isInBoundsLeft(buffer.buffer[j + 2])) continue
                    if (!mViewPortHandler.isInBoundsRight(buffer.buffer[j])) break
                    if (!isSingleColor) mRenderPaint.color = dataSet.getColor(j / 4)

                    val gradient = dataSet.gradientColor
                    if (gradient != null) {
                        mRenderPaint.shader = LinearGradient(
                            buffer.buffer[j],
                            buffer.buffer[j + 3],
                            buffer.buffer[j],
                            buffer.buffer[j + 1],
                            gradient.startColor,
                            gradient.endColor,
                            Shader.TileMode.CLAMP
                        )
                    } else {
                        mRenderPaint.shader = null
                    }

                    roundedBar.set(buffer.buffer[j], buffer.buffer[j + 1], buffer.buffer[j + 2], buffer.buffer[j + 3])
                    c.drawRoundRect(roundedBar, radiusPx, radiusPx, mRenderPaint)
                }
                mRenderPaint.shader = null
            }
        }
    }
}
