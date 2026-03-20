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

class Step2Fragment : Fragment() {

    companion object {
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
    }

    private var _binding: FragmentStep2Binding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val historyViewModel: HistoryViewModel by activityViewModels()

    private lateinit var adapter: ImagePredictionAdapter

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let { homeViewModel.addImage(it) }
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                launchCamera()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Debes conceder permiso de camara para tomar fotos.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            uriToBitmap(it)?.let { bmp ->
                homeViewModel.addImage(bmp)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStep2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Muestra la información ingresada en Step1
        binding.tvCompany.text = homeViewModel.company.value
        binding.tvVessel.text = homeViewModel.vessel.value
        binding.tvBlock.text = homeViewModel.block.value

        binding.tvVariety.text = homeViewModel.selectedVariety.value?.name ?: "—"
        adapter = ImagePredictionAdapter(
            items = mutableListOf(),
            onDelete = { position -> homeViewModel.removeImageAt(position) },
            onImageClick = { bmp -> showFullscreenImage(bmp) }
        )
        binding.toolbarStep2.setNavigationOnClickListener {
            //resetLote(false)
            findNavController().navigateUp()
        }
        // Listener para recibir resultado de eliminación (por ejemplo, desde un diálogo de confirmación en otro fragment)
        parentFragmentManager.setFragmentResultListener(
            "deleteCacheRequest",
            viewLifecycleOwner
        ) { _, bundle ->
            bundle.getString("imagePath")?.let { deleteImageFromCache(it) }
        }

        binding.recyclerPredictions.layoutManager = LinearLayoutManager(requireContext()).apply {
            reverseLayout = true
            // fuerza a que se llene desde el final (útil al hacer scroll)
            stackFromEnd = true
        }
        binding.recyclerPredictions.adapter = adapter

        // Observa la lista de imágenes/predicciones y actualiza el adapter
        homeViewModel.imagePredictions.observe(viewLifecycleOwner) { list ->
            adapter.updateList(list)
        }

        homeViewModel.selectedVariety.observe(viewLifecycleOwner) { varOpt ->
            binding.tvVariety.text = if (varOpt != null) "${varOpt.name} (id=${varOpt.id})" else "—"
        }

        // Botones de cámara y galeria
        binding.btnCamera.setOnClickListener { ensureCameraPermissionAndLaunch() }
        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }

        // Boton Regresar: Muestra un diálogo de confirmación usando MaterialAlertDialogBuilder
        binding.btnBackDelete.setOnClickListener { showConfirmationDialog() }

        // Observa el estado de las predicciones para habilitar o deshabilitar el boton Guardar Lote
        homeViewModel.imagePredictions.observe(viewLifecycleOwner) { predictions ->

            val hasImages = predictions.isNotEmpty()

            // Si ya se han agregado imagenes, se muestra “Agregar más fotos”
            binding.btnCamera.text = if (hasImages) "Agregar más fotos" else "Agregar Foto"

            val isProcessing = predictions.any { it.isProcessing }
            binding.btnSaveBatch.isEnabled = hasImages && !isProcessing

            // Actualiza el color (esto se puede sustituir por un selector de estado en los estilos de Material)
            val color = if (hasImages && !isProcessing)
                android.graphics.Color.parseColor("#006400")  // Verde oscuro
            else
                android.graphics.Color.parseColor("#B0B0B0")  // Gris
            binding.btnSaveBatch.setBackgroundColor(color)
        }

        // Doble verificacion al pulsar el boton Guardar Lote / Agregar más fotos
        binding.btnSaveBatch.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Confirmación")
                .setMessage("Usted esta guardando el lote ¿Esta seguro de no agregar mas fotos?" )
                .setPositiveButton("Sí") { dialog, _ ->
                    try {
                        val lote = homeViewModel.getLote()
                        homeViewModel.addLote(lote)
                        Toast.makeText(requireContext(), "Lote guardado con éxito", Toast.LENGTH_SHORT).show()

                        if (NetworkUtils.isNetworkAvailable(requireContext())) {
                            SyncManager.enqueueManualSync(requireContext())
                            Toast.makeText(requireContext(), "Sincronización manual iniciada", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), "No hay conexión a Internet. Sincronización pendiente.", Toast.LENGTH_SHORT).show()
                        }

                        resetLote(all = true)
                        findNavController().popBackStack()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(requireContext(), "Error al guardar el lote", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("No") { dialog, _ ->
                    // Si dice No: se procede a guardar el lote.
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun ensureCameraPermissionAndLaunch() {
        val context = requireContext()
        when {
            ContextCompat.checkSelfPermission(context, CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED -> {
                launchCamera()
            }
            shouldShowRequestPermissionRationale(CAMERA_PERMISSION) -> {
                MaterialAlertDialogBuilder(context)
                    .setTitle("Permiso de camara")
                    .setMessage("La app necesita acceso a la camara para capturar imagenes del lote.")
                    .setPositiveButton("Continuar") { _, _ ->
                        requestCameraPermission.launch(CAMERA_PERMISSION)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            else -> {
                requestCameraPermission.launch(CAMERA_PERMISSION)
            }
        }
    }

    private fun launchCamera() {
        try {
            takePhoto.launch(null)
        } catch (e: SecurityException) {
            Toast.makeText(
                requireContext(),
                "No fue posible abrir la camara por falta de permisos.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showFullscreenImage(bitmap: Bitmap) {
        val imagePath = saveBitmapToCache(bitmap)
        val action = Step2FragmentDirections.actionStep2FragmentToFullscreenImageFragment(imagePath)
        findNavController().navigate(action)
    }

    private fun deleteImageFromCache(path: String) {
        val file = File(path)
        if (file.exists()) {
            if (file.delete()) {
                println("Imagen eliminada de la caché: $path")
            } else {
                println("No se pudo eliminar la imagen de la caché: $path")
            }
        }
    }

    private fun showConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirmación")
            .setMessage("¿Estás seguro de que quieres resetear lote? Esto reiniciará el lote actual.")
            .setPositiveButton("Sí") { _, _ ->
                resetLote(all = true)
                findNavController().popBackStack()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun resetLote(all: Boolean) {
        if (all){
            homeViewModel.company.value = ""
            homeViewModel.vessel.value = ""
            homeViewModel.block.value = ""
            homeViewModel.selectedVariety.value = null // o first()
        }

        homeViewModel.imagePredictions.value = mutableListOf()
    }

    private fun saveBitmapToCache(bitmap: Bitmap): String {
        val fileName = "temp_${System.currentTimeMillis()}.png"
        val cacheDir = requireContext().cacheDir
        val tempFile = File(cacheDir, fileName)
        tempFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
        return "file://${tempFile.absolutePath}"
    }

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
