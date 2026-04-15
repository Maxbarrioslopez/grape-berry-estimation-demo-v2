package com.gaiaspa.metrics_detection.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.databinding.FragmentStep2Binding
import com.gaiaspa.metrics_detection.ui.history.HistoryViewModel
import com.gaiaspa.metrics_detection.utils.NetworkUtils
import com.gaiaspa.metrics_detection.worker.SyncManager
import java.io.File
import java.io.FileOutputStream

class Step2Fragment : Fragment() {

    companion object {
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
    }

    private var _binding: FragmentStep2Binding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val historyViewModel: HistoryViewModel by activityViewModels()

    private lateinit var adapter: ImagePredictionAdapter
    private var tempPhotoUri: Uri? = null
    private var tempPhotoFile: File? = null

    // ✅ REFACTOR CÁMARA: Captura a archivo real (TakePicture) en lugar de miniatura (TakePicturePreview)
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            tempPhotoFile?.let { homeViewModel.addImage(it.absolutePath) }
        }
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) launchCamera()
            else Toast.makeText(requireContext(), "Permiso de cámara denegado", Toast.LENGTH_SHORT).show()
        }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val file = saveUriToTempFile(it)
            file?.let { f -> homeViewModel.addImage(f.absolutePath) }
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
        binding.tvVariety.text = homeViewModel.selectedVariety.value?.name ?: "—"
        
        adapter = ImagePredictionAdapter(
            items = mutableListOf(),
            onDelete = { position -> homeViewModel.removeImageAt(position) },
            onImageClick = { bmp -> showFullscreenImage(bmp) }
        )
        
        binding.toolbarStep2.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.recyclerPredictions.layoutManager = LinearLayoutManager(requireContext()).apply {
            reverseLayout = true
            stackFromEnd = true
        }
        binding.recyclerPredictions.adapter = adapter

        homeViewModel.isSavingLote.observe(viewLifecycleOwner) { isSaving ->
            binding.btnSaveBatch.isEnabled = !isSaving
            binding.btnSaveBatch.text = if (isSaving) "Guardando..." else "Guardar lote"
        }

        homeViewModel.imagePredictions.observe(viewLifecycleOwner) { list ->
            adapter.updateList(list)
            val hasImages = list.isNotEmpty()
            binding.btnCamera.text = if (hasImages) "Agregar más fotos" else "Agregar Foto"

            val isAnyProcessing = list.any { 
                it.status == HomeViewModel.Status.NORMALIZING || it.status == HomeViewModel.Status.PROCESSING 
            }
            binding.btnSaveBatch.isEnabled = hasImages && !isAnyProcessing && (homeViewModel.isSavingLote.value == false)

            val color = if (binding.btnSaveBatch.isEnabled)
                android.graphics.Color.parseColor("#006400")
            else
                android.graphics.Color.parseColor("#B0B0B0")
            binding.btnSaveBatch.setBackgroundColor(color)
        }

        homeViewModel.selectedVariety.observe(viewLifecycleOwner) { varOpt ->
            binding.tvVariety.text = if (varOpt != null) "${varOpt.name} (id=${varOpt.id})" else "—"
        }

        binding.btnCamera.setOnClickListener { ensureCameraPermissionAndLaunch() }
        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.btnBackDelete.setOnClickListener { showConfirmationDialog() }

        binding.btnSaveBatch.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Confirmación")
                .setMessage("¿Desea guardar el lote con las fotos actuales?")
                .setPositiveButton("Sí") { _, _ ->
                    homeViewModel.saveBatch { success ->
                        if (success) {
                            Toast.makeText(requireContext(), "Lote guardado localmente", Toast.LENGTH_SHORT).show()
                            if (NetworkUtils.isNetworkAvailable(requireContext())) {
                                SyncManager.enqueueManualSync(requireContext())
                            }
                            findNavController().popBackStack()
                        } else {
                            Toast.makeText(requireContext(), "Error al guardar el lote", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("No", null)
                .show()
        }
    }

    private fun ensureCameraPermissionAndLaunch() {
        if (ContextCompat.checkSelfPermission(requireContext(), CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            requestCameraPermission.launch(CAMERA_PERMISSION)
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
            takePhoto.launch(tempPhotoUri)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error al abrir la cámara", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveUriToTempFile(uri: Uri): File? {
        return try {
            val file = File(requireContext().cacheDir, "gallery_pick_${System.currentTimeMillis()}.jpg")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file
        } catch (e: Exception) { null }
    }

    private fun showFullscreenImage(bitmap: Bitmap) {
        val fileName = "temp_view_${System.currentTimeMillis()}.png"
        val tempFile = File(requireContext().cacheDir, fileName)
        tempFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val action = Step2FragmentDirections.actionStep2FragmentToFullscreenImageFragment("file://${tempFile.absolutePath}")
        findNavController().navigate(action)
    }

    private fun showConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirmación")
            .setMessage("¿Estás seguro de que quieres resetear lote?")
            .setPositiveButton("Sí") { _, _ ->
                homeViewModel.imagePredictions.value = mutableListOf()
                findNavController().popBackStack()
            }
            .setNegativeButton("No", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
