package com.gaiaspa.metrics_detection.ui.utils

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.databinding.FragmentFullscreenImageBinding
import com.google.android.material.snackbar.Snackbar
import java.io.File

/**
 * Fullscreen image viewer.
 *
 * Opens an image from a local file path. If the file does not exist at
 * open time, a placeholder icon is shown and a Snackbar notifies the user
 * instead of crashing the navigation flow.
 *
 * On dismiss, sends a "deleteCacheRequest" fragment result so the caller
 * can clean up temporary view cache files.
 */
class FullscreenImageFragment : Fragment() {

    private var _binding: FragmentFullscreenImageBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<FullscreenImageFragmentArgs>()
    private var imagePath: String? = ""
    private var navigatedBack = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFullscreenImageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        imagePath = args.imagePath ?: ""

        if (imagePath.orEmpty().isNotBlank() && isAdded) {
            val cleanPath = imagePath.orEmpty().replace("file://", "")
            val file = File(cleanPath)
            if (!file.exists()) {
                binding.ivFullscreen.setImageResource(R.drawable.ic_image)
                if (isAdded) showImageNotAvailableMessage()
            } else {
                val bitmap = runCatching { loadBitmapFromPath(imagePath.orEmpty()) }.getOrNull()
                if (bitmap != null && !bitmap.isRecycled) {
                    binding.ivFullscreen.setImageBitmap(bitmap)
                } else {
                    binding.ivFullscreen.setImageResource(R.drawable.ic_image)
                    if (isAdded) showImageNotAvailableMessage()
                }
            }
        } else {
            binding.ivFullscreen.setImageResource(R.drawable.ic_image)
            if (isAdded) showImageNotAvailableMessage()
        }

        binding.btnClose.setOnClickListener {
            navigatedBack = true
            runCatching { findNavController().popBackStack() }
        }
    }

    private fun showImageNotAvailableMessage() {
        Snackbar.make(binding.root, R.string.image_unavailable_device, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(requireContext(), R.color.warning_soft))
            .setTextColor(ContextCompat.getColor(requireContext(), R.color.textPrimary))
            .show()
    }

    private fun loadBitmapFromPath(path: String) = try {
        val uri = Uri.parse(path)
        when {
            uri.scheme == "file" -> BitmapFactory.decodeFile(uri.path)
            isAdded -> requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
            else -> null
        }
    } catch (e: IllegalStateException) {
        // Fragment not attached to activity - safe to ignore
        Log.w("FullscreenImage", "Fragment not attached when loading bitmap")
        null
    } catch (e: Exception) {
        Log.e("FullscreenImage", "Could not open image", e)
        null
    }

    override fun onDestroyView() {
        if (!navigatedBack) {
            imagePath?.let { path ->
                parentFragmentManager.setFragmentResult(
                    "deleteCacheRequest",
                    bundleOf("imagePath" to path)
                )
            }
        }
        _binding = null
        super.onDestroyView()
    }
}
