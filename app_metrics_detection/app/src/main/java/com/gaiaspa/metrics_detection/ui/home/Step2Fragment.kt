package com.gaiaspa.metrics_detection.ui.home

import android.Manifest
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gaiaspa.metrics_detection.FeatureFlags
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.databinding.FragmentStep2Binding
import com.gaiaspa.metrics_detection.ui.history.HistoryViewModel
import com.gaiaspa.metrics_detection.utils.NetworkUtils
import com.gaiaspa.metrics_detection.worker.SyncManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.FileOutputStream

/**
 * Step 2 of the capture flow: image acquisition and processing.
 *
 * Manages camera and gallery capture for Frente/Reverso (PhotoRole.A / B)
 * pairs. In multi-view fusion mode each pair belongs to a racimo (bunch).
 * Results are displayed in a RecyclerView with per-image status, histogram,
 * and overlay preview.
 *
 * This fragment is also responsible for the save-batch confirmation dialog
 * and triggering WorkManager sync after a successful save.
 */
class Step2Fragment : Fragment() {

    companion object {
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
        private const val KEY_PENDING_RACIMO = "pending_racimo"
        private const val KEY_PENDING_ROLE = "pending_role"
        private const val KEY_AUTO_REVERSE = "auto_reverse"
        private const val KEY_TEMP_FILE = "temp_file"
        private const val KEY_TEMP_URI = "temp_uri"
    }

    private var _binding: FragmentStep2Binding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val historyViewModel: HistoryViewModel by activityViewModels()

    private lateinit var adapter: ImagePredictionAdapter
    private var tempPhotoUri: Uri? = null
    private var tempPhotoFile: File? = null
    private var isNavigating = false
    private var pendingPhotoTarget: PhotoTarget? = null
    private var autoPromptReverseAfterResult: Boolean = false
    private var selectedFilter: BunchFilter = BunchFilter.ALL

    private enum class MessageTone { SUCCESS, WARNING, ERROR, INFO }
    private enum class CaptureSource { CAMERA, GALLERY }
    private enum class BunchFilter { ALL, PROCESSED, PENDING }

    private data class PhotoTarget(
        val racimoIndex: Int,
        val role: HomeViewModel.PhotoRole
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState ?: return
        val pendingRacimo = savedInstanceState.getInt(KEY_PENDING_RACIMO, -1)
        val pendingRole = savedInstanceState.getString(KEY_PENDING_ROLE)
            ?.let { runCatching { HomeViewModel.PhotoRole.valueOf(it) }.getOrNull() }
        if (pendingRacimo > 0 && pendingRole != null) {
            pendingPhotoTarget = PhotoTarget(pendingRacimo, pendingRole)
        }
        autoPromptReverseAfterResult = savedInstanceState.getBoolean(KEY_AUTO_REVERSE, false)
        tempPhotoFile = savedInstanceState.getString(KEY_TEMP_FILE)?.let { File(it) }
        tempPhotoUri = savedInstanceState.getString(KEY_TEMP_URI)?.let(Uri::parse)
    }

    // Camera capture uses TakePicture (full-quality file) instead of TakePicturePreview (thumbnail)
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempPhotoFile != null) {
            handleCapturedPath(tempPhotoFile?.absolutePath ?: return@registerForActivityResult)
        } else {
            pendingPhotoTarget = null
            autoPromptReverseAfterResult = false
        }
        tempPhotoFile = null
        tempPhotoUri = null
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) launchCamera()
            else showMessage(getString(R.string.camera_permission_denied), MessageTone.ERROR)
        }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val file = saveUriToTempFile(uri)
            if (file != null) {
                handleCapturedPath(file.absolutePath)
            } else {
                showMessage(getString(R.string.gallery_photo_read_error), MessageTone.ERROR)
                pendingPhotoTarget = null
                autoPromptReverseAfterResult = false
            }
        } else {
            pendingPhotoTarget = null
            autoPromptReverseAfterResult = false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStep2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvCompany.text = homeViewModel.company.value
        binding.tvVessel.text = homeViewModel.vessel.value
        binding.tvBlock.text = homeViewModel.block.value
        binding.tvVariety.text = homeViewModel.selectedVariety.value?.name ?: getString(R.string.unavailable)
        homeViewModel.ensureInitialRacimo()
        
        adapter = ImagePredictionAdapter(
            items = mutableListOf(),
            onDelete = { position -> homeViewModel.removeImageAt(position) },
            onDeleteRacimo = { racimoIndex -> homeViewModel.removeRacimoAt(racimoIndex) },
            onDeletePhoto = { racimoIndex, role -> homeViewModel.removePhotoForRacimo(racimoIndex, role) },
            onTakePhoto = { racimoIndex, role ->
                startCapture(
                    target = PhotoTarget(racimoIndex, role),
                    source = CaptureSource.CAMERA,
                    autoContinueToReverse = shouldPromptReverseAfter(racimoIndex, role)
                )
            },
            onUploadPhoto = { racimoIndex, role ->
                startCapture(
                    target = PhotoTarget(racimoIndex, role),
                    source = CaptureSource.GALLERY,
                    autoContinueToReverse = shouldPromptReverseAfter(racimoIndex, role)
                )
            },
            onSwapFrontBack = { racimoIndex -> confirmSwapFrontBack(racimoIndex) },
            onImageClick = { bmp -> showFullscreenImage(bmp) }
        )
        
        binding.toolbarStep2.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.recyclerPredictions.layoutManager = LinearLayoutManager(requireContext()).apply {
            reverseLayout = true
            stackFromEnd = true
        }
        binding.recyclerPredictions.adapter = adapter

        homeViewModel.isSavingLote.observe(viewLifecycleOwner) { isSaving ->
            updateSaveButtonState()
        }

        homeViewModel.imagePredictions.observe(viewLifecycleOwner) { list ->
            if (FeatureFlags.multiViewFusionEnabled) {
                val models = homeViewModel.buildRacimoUiModels(list)
                adapter.updateRacimos(applyBunchFilter(models))
                updateEmptyState(models.isEmpty())
                updateGuidedCaptureUi(models)
            } else {
                adapter.updateList(list)
                updateEmptyState(list.isEmpty())
                binding.topActions.visibility = View.VISIBLE
                binding.layoutBunchFilters.visibility = View.GONE
            }

            val hasImages = list.isNotEmpty()
            binding.btnCamera.text = if (FeatureFlags.multiViewFusionEnabled) {
                getString(R.string.new_bunch)
            } else if (hasImages) {
                getString(R.string.add_more_photos)
            } else {
                getString(R.string.add_photo)
            }
            binding.btnCamera.setIconResource(
                if (FeatureFlags.multiViewFusionEnabled) R.drawable.ic_playlist_add else R.drawable.ic_camera
            )
            binding.btnGallery.visibility =
                if (FeatureFlags.multiViewFusionEnabled) View.GONE else View.VISIBLE

            binding.tvNextCaptureGuide.visibility =
                if (FeatureFlags.multiViewFusionEnabled) View.VISIBLE else View.GONE
            if (!FeatureFlags.multiViewFusionEnabled) {
                binding.tvNextCaptureGuide.text = homeViewModel.nextCaptureGuide()
            }

            updateSaveButtonState()
        }

        homeViewModel.visibleRacimoCount.observe(viewLifecycleOwner) {
            if (FeatureFlags.multiViewFusionEnabled) {
                val models = homeViewModel.buildRacimoUiModels()
                adapter.updateRacimos(applyBunchFilter(models))
                updateEmptyState(models.isEmpty())
                updateGuidedCaptureUi(models)
                updateSaveButtonState()
            }
        }

        homeViewModel.selectedVariety.observe(viewLifecycleOwner) { varOpt ->
            binding.tvVariety.text = varOpt?.name ?: getString(R.string.unavailable)
        }

        binding.btnCamera.setOnClickListener {
            if (FeatureFlags.multiViewFusionEnabled) {
                showGuidedCaptureSheet()
            } else {
                ensureCameraPermissionAndLaunch()
            }
        }
        binding.btnGallery.setOnClickListener {
            runCatching { pickImage.launch("image/*") }.onFailure {
                Log.e("Step2Fragment", "Gallery picker launch failed", it)
            }
        }
        binding.btnEmptyNewBunch.setOnClickListener {
            if (FeatureFlags.multiViewFusionEnabled) {
                showGuidedCaptureSheet()
            } else {
                ensureCameraPermissionAndLaunch()
            }
        }
        binding.btnBackDelete.setOnClickListener { showConfirmationDialog() }
        setupBunchFilters()

        binding.btnSaveBatch.setOnClickListener {
            runCatching {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.confirm_title))
                    .setMessage(getString(R.string.confirm_save_batch))
                    .setPositiveButton(getString(R.string.yes)) { _, _ ->
                        homeViewModel.saveBatch { success ->
                            if (!isAdded) return@saveBatch
                            if (success) {
                                showMessage(getString(R.string.batch_saved_locally), MessageTone.SUCCESS)
                                if (isAdded && NetworkUtils.isNetworkAvailable(requireContext())) {
                                    SyncManager.enqueueManualSync(requireContext())
                                }
                                findNavController().popBackStack()
                            } else {
                                val message = homeViewModel.saveErrorMessage.value ?: getString(R.string.batch_save_error)
                                showMessage(message, MessageTone.ERROR)
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.no), null)
                    .show()
                    .apply { setCanceledOnTouchOutside(false) }
            }.onFailure {
                Log.e("Step2Fragment", "Save batch dialog failed", it)
            }
        }
    }

    private fun setupBunchFilters() {
        binding.btnFilterAll.setOnClickListener { setBunchFilter(BunchFilter.ALL) }
        binding.btnFilterProcessed.setOnClickListener { setBunchFilter(BunchFilter.PROCESSED) }
        binding.btnFilterPending.setOnClickListener { setBunchFilter(BunchFilter.PENDING) }
        updateFilterButtons()
    }

    private fun setBunchFilter(filter: BunchFilter) {
        selectedFilter = filter
        updateFilterButtons()
        val models = homeViewModel.buildRacimoUiModels()
        adapter.updateRacimos(applyBunchFilter(models))
        updateEmptyState(models.isEmpty())
    }

    private fun applyBunchFilter(models: List<RacimoUiModel>): List<RacimoUiModel> {
        return when (selectedFilter) {
            BunchFilter.ALL -> models
            BunchFilter.PROCESSED -> models.filter { it.hasValidFusedPrediction }
            BunchFilter.PENDING -> models.filter { !it.hasValidFusedPrediction }
        }
    }

    private fun updateFilterButtons() {
        if (_binding == null) return
        val activeColor = ContextCompat.getColor(requireContext(), R.color.colorPrimary)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.button_secondary_bg)
        val activeText = ContextCompat.getColor(requireContext(), R.color.textOnPrimary)
        val inactiveText = ContextCompat.getColor(requireContext(), R.color.colorPrimaryDark)
        listOf(
            binding.btnFilterAll to BunchFilter.ALL,
            binding.btnFilterProcessed to BunchFilter.PROCESSED,
            binding.btnFilterPending to BunchFilter.PENDING
        ).forEach { (button, filter) ->
            val selected = selectedFilter == filter
            button.backgroundTintList = ColorStateList.valueOf(if (selected) activeColor else inactiveColor)
            button.setTextColor(if (selected) activeText else inactiveText)
        }
    }

    private fun showGuidedCaptureSheet() {
        val target = nextGuidedTarget()
        showCaptureSourceSheet(
            target = target,
            autoContinueToReverse = target.role == HomeViewModel.PhotoRole.A && !hasPhoto(target.racimoIndex, HomeViewModel.PhotoRole.B)
        )
    }

    private fun showCaptureSourceSheet(
        target: PhotoTarget,
        autoContinueToReverse: Boolean
    ) {
        if (!isAdded || _binding == null) return
        val title = if (target.role == HomeViewModel.PhotoRole.A) {
            getString(R.string.capture_front_sheet_title)
        } else {
            getString(R.string.capture_back_sheet_title)
        }

        val dialog = BottomSheetDialog(requireContext())
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(26))
        }

        content.addView(TextView(requireContext()).apply {
            text = title
            setTextColor(ContextCompat.getColor(requireContext(), R.color.textPrimary))
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })

        content.addView(TextView(requireContext()).apply {
            text = getString(R.string.capture_sheet_tip)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.textSecondary))
            textSize = 13f
            setPadding(0, dp(6), 0, dp(14))
        })

        content.addView(captureOptionButton(
            text = getString(R.string.capture_source_camera),
            iconRes = R.drawable.ic_camera
        ) {
            dialog.dismiss()
            startCapture(target, CaptureSource.CAMERA, autoContinueToReverse)
        })

        content.addView(captureOptionButton(
            text = getString(R.string.capture_source_gallery),
            iconRes = R.drawable.ic_gallery
        ) {
            dialog.dismiss()
            startCapture(target, CaptureSource.GALLERY, autoContinueToReverse)
        })

        runCatching {
            dialog.setContentView(content)
            dialog.setCanceledOnTouchOutside(true)
            dialog.show()
        }.onFailure {
            showMessage(getString(R.string.error_generic), MessageTone.ERROR)
        }
    }

    private fun captureOptionButton(
        text: String,
        iconRes: Int,
        onClick: () -> Unit
    ): MaterialButton {
        return MaterialButton(requireContext()).apply {
            this.text = text
            setIconResource(iconRes)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(12)
            minHeight = dp(58)
            cornerRadius = dp(16)
            isAllCaps = false
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            insetTop = 0
            insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.button_secondary_bg))
            strokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.md_border_soft))
            strokeWidth = dp(1)
            setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
            iconTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorPrimaryDark))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }
    }

    private fun startCapture(
        target: PhotoTarget,
        source: CaptureSource,
        autoContinueToReverse: Boolean
    ) {
        pendingPhotoTarget = target
        autoPromptReverseAfterResult = autoContinueToReverse
        when (source) {
            CaptureSource.CAMERA -> ensureCameraPermissionAndLaunch()
            CaptureSource.GALLERY -> {
                runCatching { pickImage.launch("image/*") }.onFailure {
                    Log.e("Step2Fragment", "Gallery picker launch failed", it)
                }
            }
        }
    }

    private fun handleCapturedPath(path: String) {
        val imageFile = File(path)
        validateCapturedImage(imageFile)?.let { message ->
            showMessage(message, MessageTone.ERROR)
            pendingPhotoTarget = null
            autoPromptReverseAfterResult = false
            return
        }

        if (!FeatureFlags.multiViewFusionEnabled) {
            homeViewModel.addImage(path)
            pendingPhotoTarget = null
            autoPromptReverseAfterResult = false
            return
        }

        val target = pendingPhotoTarget
        if (target == null) {
            pendingPhotoTarget = null
            autoPromptReverseAfterResult = false
            return
        }

        val shouldPromptReverse = autoPromptReverseAfterResult && target.role == HomeViewModel.PhotoRole.A
        val added = homeViewModel.upsertPhotoForRacimo(
            racimoIndex = target.racimoIndex,
            role = target.role,
            path = path
        )

        pendingPhotoTarget = null
        autoPromptReverseAfterResult = false

        if (!added) {
            showMessage(getString(R.string.add_photo_a_first), MessageTone.WARNING)
            return
        }
        showMessage(
            getString(if (target.role == HomeViewModel.PhotoRole.A) R.string.front_image_replaced else R.string.back_image_replaced),
            MessageTone.SUCCESS
        )

        if (shouldPromptReverse) {
            view?.post {
                if (isAdded) {
                    showCaptureSourceSheet(
                        target = PhotoTarget(target.racimoIndex, HomeViewModel.PhotoRole.B),
                        autoContinueToReverse = false
                    )
                }
            }
        }
    }

    private fun validateCapturedImage(file: File): String? {
        return try {
            if (!file.exists() || file.length() <= 0L) return getString(R.string.image_failed_to_load)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            when {
                options.outWidth <= 0 || options.outHeight <= 0 -> getString(R.string.image_could_not_be_processed)
                options.outWidth < 256 || options.outHeight < 256 -> getString(R.string.image_too_small)
                else -> null
            }
        } catch (e: OutOfMemoryError) {
            Log.e("Step2Fragment", "OutOfMemory validating image", e)
            getString(R.string.image_could_not_be_processed)
        } catch (e: Exception) {
            Log.e("Step2Fragment", "Error validating image", e)
            getString(R.string.image_could_not_be_processed)
        }
    }

    private fun nextGuidedTarget(): PhotoTarget {
        val models = homeViewModel.buildRacimoUiModels()
        val incomplete = models.firstOrNull { !it.isComplete }
        if (incomplete != null) {
            val role = if (!hasPhoto(incomplete.racimoIndex, HomeViewModel.PhotoRole.A)) {
                HomeViewModel.PhotoRole.A
            } else {
                HomeViewModel.PhotoRole.B
            }
            return PhotoTarget(incomplete.racimoIndex, role)
        }
        return PhotoTarget(models.size + 1, HomeViewModel.PhotoRole.A)
    }

    private fun shouldPromptReverseAfter(
        racimoIndex: Int,
        role: HomeViewModel.PhotoRole
    ): Boolean {
        return FeatureFlags.multiViewFusionEnabled &&
            role == HomeViewModel.PhotoRole.A &&
            !hasPhoto(racimoIndex, HomeViewModel.PhotoRole.B)
    }

    private fun hasPhoto(racimoIndex: Int, role: HomeViewModel.PhotoRole): Boolean {
        val model = homeViewModel.buildRacimoUiModels()
            .firstOrNull { it.racimoIndex == racimoIndex }
            ?: return false
        return if (role == HomeViewModel.PhotoRole.A) {
            model.imageAPath != null || model.overlayAPath != null
        } else {
            model.imageBPath != null || model.overlayBPath != null
        }
    }

    private fun updateGuidedCaptureUi(models: List<RacimoUiModel>) {
        val isEmpty = models.isEmpty()
        binding.topActions.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.layoutBunchFilters.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.tvNextCaptureGuide.text = when {
            isEmpty -> getString(R.string.capture_front_prompt)
            models.any { it.hasAnyPhoto && !it.isComplete } -> getString(R.string.capture_back_prompt)
            homeViewModel.canSaveCurrentBatch() -> getString(R.string.result_ready)
            else -> getString(R.string.bunch_progressive_guide)
        }
        binding.tvNextCaptureGuide.visibility = View.VISIBLE

        val params = binding.btnCamera.layoutParams as? LinearLayout.LayoutParams ?: return
        params.width = ViewGroup.LayoutParams.WRAP_CONTENT
        params.weight = 0f
        params.gravity = Gravity.CENTER_HORIZONTAL
        binding.btnCamera.layoutParams = params
        binding.topActions.gravity = Gravity.CENTER
    }

    private fun ensureCameraPermissionAndLaunch() {
        if (!isAdded) return
        if (ContextCompat.checkSelfPermission(requireContext(), CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            runCatching { requestCameraPermission.launch(CAMERA_PERMISSION) }.onFailure {
                Log.e("Step2Fragment", "Permission request failed", it)
            }
        }
    }

    private fun launchCamera() {
        try {
            val photoFile = File(requireContext().cacheDir, "camera_capture_${System.currentTimeMillis()}.jpg")
            tempPhotoFile = photoFile
            tempPhotoUri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.provider",
                photoFile
            )
            runCatching {
                takePhoto.launch(tempPhotoUri)
            }.onFailure { e ->
                Log.e("Step2Fragment", "Camera launch failed: ${e.message}", e)
                if (isAdded) showMessage(getString(R.string.camera_open_error), MessageTone.ERROR)
            }
        } catch (e: Exception) {
            Log.e("Step2Fragment", "Camera setup failed", e)
            if (isAdded) showMessage(getString(R.string.camera_open_error), MessageTone.ERROR)
        }
    }

    private fun updateSaveButtonState() {
        val list = homeViewModel.imagePredictions.value.orEmpty()
        val isSaving = homeViewModel.isSavingLote.value == true
        val hasImages = list.isNotEmpty()
        val isAnyProcessing = list.any {
            it.status == HomeViewModel.Status.NORMALIZING || it.status == HomeViewModel.Status.PROCESSING
        }
        val canSave = if (FeatureFlags.multiViewFusionEnabled) {
            homeViewModel.canSaveCurrentBatch()
        } else {
            hasImages && !isAnyProcessing
        }
        binding.btnSaveBatch.isEnabled = canSave && !isSaving
        binding.btnSaveBatch.text = if (isSaving) getString(R.string.saving) else getString(R.string.save_batch)
        applySaveButtonVisuals()
    }

    private fun applySaveButtonVisuals() {
        val colorRes = if (binding.btnSaveBatch.isEnabled) R.color.colorPrimary else R.color.buttonDisabled
        binding.btnSaveBatch.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), colorRes)
        )
        binding.btnSaveBatch.alpha = if (binding.btnSaveBatch.isEnabled) 1f else 0.82f
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.layoutEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerPredictions.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showMessage(message: String, tone: MessageTone = MessageTone.INFO) {
        _binding?.root?.let { root ->
            val background = when (tone) {
                MessageTone.SUCCESS -> R.color.success_soft
                MessageTone.WARNING -> R.color.warning_soft
                MessageTone.ERROR -> R.color.error_soft
                MessageTone.INFO -> R.color.info_soft
            }
            val foreground = when (tone) {
                MessageTone.SUCCESS -> R.color.colorPrimaryDark
                MessageTone.WARNING -> R.color.chip_incomplete_text
                MessageTone.ERROR -> R.color.error
                MessageTone.INFO -> R.color.info
            }
            Snackbar.make(root, message, Snackbar.LENGTH_LONG)
                .setAction(getString(R.string.close)) { }
                .setBackgroundTint(ContextCompat.getColor(requireContext(), background))
                .setTextColor(ContextCompat.getColor(requireContext(), R.color.textPrimary))
                .setActionTextColor(ContextCompat.getColor(requireContext(), foreground))
                .show()
        }
    }

    private fun saveUriToTempFile(uri: Uri): File? {
        return try {
            val file = File(requireContext().cacheDir, "gallery_pick_${System.currentTimeMillis()}.jpg")
            val copied = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            if (copied == null) null else file
        } catch (e: Exception) {
            Log.e("Step2Fragment", "Error copying gallery image", e)
            null
        }
    }

    private fun showFullscreenImage(bitmap: Bitmap) {
        if (isNavigating || bitmap.isRecycled || !isAdded) return
        isNavigating = true
        runCatching {
            val fileName = "temp_view_${System.currentTimeMillis()}.png"
            val tempFile = File(requireContext().cacheDir, fileName)
            tempFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val action = Step2FragmentDirections.actionStep2FragmentToFullscreenImageFragment("file://${tempFile.absolutePath}")
            findNavController().navigate(action)
        }.onFailure {
            showMessage(getString(R.string.preview_generation_error), MessageTone.ERROR)
        }.also {
            isNavigating = false
        }
    }

    private fun showConfirmationDialog() {
        if (!isAdded) return
        runCatching {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.confirm_title))
                .setMessage(getString(R.string.confirm_reset_batch))
                .setPositiveButton(getString(R.string.yes)) { _, _ ->
                    homeViewModel.clearImages()
                    findNavController().popBackStack()
                }
                .setNegativeButton(getString(R.string.no), null)
                .show()
                .apply { setCanceledOnTouchOutside(false) }
        }.onFailure {
            Log.e("Step2Fragment", "Confirmation dialog failed", it)
        }
    }

    private fun confirmSwapFrontBack(racimoIndex: Int) {
        if (!isAdded) return
        runCatching {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.swap_front_back))
                .setMessage(getString(R.string.confirm_swap_front_back))
                .setPositiveButton(getString(R.string.yes)) { _, _ ->
                    val error = homeViewModel.swapRacimoFrontBack(racimoIndex)
                    if (error == null) {
                        showMessage(getString(R.string.processing), MessageTone.INFO)
                    } else {
                        showMessage(error, MessageTone.WARNING)
                    }
                }
                .setNegativeButton(getString(R.string.no), null)
                .show()
                .apply { setCanceledOnTouchOutside(false) }
        }.onFailure {
            Log.e("Step2Fragment", "Swap dialog failed", it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPhotoTarget?.let { target ->
            outState.putInt(KEY_PENDING_RACIMO, target.racimoIndex)
            outState.putString(KEY_PENDING_ROLE, target.role.name)
        }
        outState.putBoolean(KEY_AUTO_REVERSE, autoPromptReverseAfterResult)
        tempPhotoFile?.let { outState.putString(KEY_TEMP_FILE, it.absolutePath) }
        tempPhotoUri?.let { outState.putString(KEY_TEMP_URI, it.toString()) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
