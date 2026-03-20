package com.gaiaspa.metrics_detection.ui.utils

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.*
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.navArgs
import com.gaiaspa.metrics_detection.databinding.FragmentFullscreenImageBinding
import com.gaiaspa.metrics_detection.ui.utils.FullscreenImageFragmentArgs

class FullscreenImageFragment : Fragment() {

    private var _binding: FragmentFullscreenImageBinding? = null
    private val binding get() = _binding!!

    // Safe Args genera una clase FullscreenImageFragmentArgs
    private val args by navArgs<FullscreenImageFragmentArgs>()
    private var imagePath: String? = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFullscreenImageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Recuperar la ruta del archivo
        val imagePath = args.imagePath ?: ""

        // Convertir la ruta a Bitmap (ejemplo)
        if (imagePath.isNotBlank()) {
            val bitmap = loadBitmapFromPath(imagePath)
            binding.ivFullscreen.setImageBitmap(bitmap)
        }

        // Botón para cerrar (o volver atrás)
        binding.btnClose.setOnClickListener {
            parentFragmentManager.popBackStack()
            // o findNavController().popBackStack()
        }
    }

    private fun loadBitmapFromPath(path: String) = try {
        // Si es un path local, podrías hacer algo como:
        val uri = Uri.parse(path)
        val inputStream = requireContext().contentResolver.openInputStream(uri)
        val bmp = BitmapFactory.decodeStream(inputStream)
        inputStream?.close()
        bmp
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    override fun onDestroyView() {
        super.onDestroyView()

        imagePath?.let { path ->
            parentFragmentManager.setFragmentResult(
                "deleteCacheRequest",
                bundleOf("imagePath" to path)
            )
        }

        _binding = null
    }
}
