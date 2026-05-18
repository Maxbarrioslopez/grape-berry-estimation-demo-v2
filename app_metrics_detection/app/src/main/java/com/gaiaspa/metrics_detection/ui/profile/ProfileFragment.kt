package com.gaiaspa.metrics_detection.ui.profile

import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.gaiaspa.metrics_detection.FeatureFlags
import com.gaiaspa.metrics_detection.MainActivity
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.data.model.Profile
import com.gaiaspa.metrics_detection.data.repository.LoteRepository
import com.gaiaspa.metrics_detection.data.repository.ProfileRepository
import com.gaiaspa.metrics_detection.databinding.FragmentProfileBinding
import com.gaiaspa.metrics_detection.i18n.LanguagePreferenceManager
import com.gaiaspa.metrics_detection.ui.history.HistoryViewModel
import com.gaiaspa.metrics_detection.worker.BatchDownloadWorker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: ProfileViewModel
    private lateinit var viewModelFactory: ProfileViewModelFactory
    private val historyViewModel: HistoryViewModel by lazy {
        ViewModelProvider(requireActivity()).get(HistoryViewModel::class.java)
    }

    // Diálogo de progreso
    private var downloadDialog: AlertDialog? = null
    private enum class MessageTone { SUCCESS, WARNING, ERROR, INFO }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Inicializa repositorios y ViewModel
        val repository = ProfileRepository.getInstance(requireContext())
        val loteRepository = LoteRepository.getInstance(requireContext())
        viewModelFactory = ProfileViewModelFactory(repository, loteRepository, requireContext())
        viewModel = ViewModelProvider(this, viewModelFactory).get(ProfileViewModel::class.java)

        // Observa perfil para actualizar UI
        viewModel.profile.observe(viewLifecycleOwner) { profile ->
            profile?.let { updateUI(it) }
        }
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
        viewModel.error.observe(viewLifecycleOwner) { errorMsg ->
            errorMsg?.let {
                showProfileMessage(it, MessageTone.ERROR)
            }
        }

        // Boton Logout
        binding.btnLogout.setOnClickListener {
            viewModel.logout()
            (activity as? MainActivity)?.logout()
        }

        setupLanguageButton()

        // Boton para iniciar descarga mediante Worker
        binding.btnDownloadLotes.setOnClickListener {
            enqueueDownloadWork()
        }
        binding.btnClearTemporaryFiles.setOnClickListener {
            confirmLocalCleanup(
                title = getString(R.string.clear_temporary_files_title),
                message = getString(R.string.clear_temporary_files_message),
                actionText = getString(R.string.delete),
                onConfirmed = {
                    val deleted = clearTemporaryFiles()
                    showProfileMessage(
                        getString(if (deleted > 0) R.string.temporary_files_removed else R.string.nothing_to_clear),
                        MessageTone.SUCCESS
                    )
                }
            )
        }
        setupDarkModeSwitch()
        setupRotationSwitch()

        // Boton para eliminar solo los datos sincronizados (Danger Zone)
        binding.btnClearDataSynced.setOnClickListener {
            confirmLocalCleanup(
                title = getString(R.string.delete_synced_data_title),
                message = getString(R.string.delete_synced_data_message),
                actionText = getString(R.string.confirm_delete),
                onConfirmed = {
                    viewModel.clearOnlyDataSynced()
                    showProfileMessage(
                        "${getString(R.string.synced_data_deleted)} ${getString(R.string.local_only_cloud_safe)}",
                        MessageTone.SUCCESS
                    )
                    historyViewModel.refreshLotes()
                }
            )
        }

        // Boton para eliminar TODOS los datos locales (Danger Zone)
        binding.btnClearAllData.setOnClickListener {
            confirmLocalCleanup(
                title = getString(R.string.delete_all_local_data_title),
                message = getString(R.string.delete_all_local_data_message),
                actionText = getString(R.string.confirm_delete),
                onConfirmed = {
                    viewModel.clearLocalData()
                    showProfileMessage(
                        "${getString(R.string.all_local_data_deleted)} ${getString(R.string.local_only_cloud_safe)}",
                        MessageTone.WARNING
                    )
                    historyViewModel.refreshLotes()
                }
            )
        }
    }

    private fun setupLanguageButton() {
        binding.btnChangeLanguage.visibility =
            if (FeatureFlags.FEATURE_LANGUAGE_SWITCH) View.VISIBLE else View.GONE
        binding.btnChangeLanguage.setOnClickListener {
            if (!FeatureFlags.FEATURE_LANGUAGE_SWITCH) return@setOnClickListener
            (activity as? AppCompatActivity)?.let { appCompatActivity ->
                LanguagePreferenceManager.showSelector(
                    activity = appCompatActivity,
                    cancelable = true
                )
            }
        }
    }

    private fun updateUI(profile: Profile) {
        binding.tvNameHeader.text = "${profile.name} ${profile.lastname}"
        binding.tvRoleHeader.text = profile.role
        binding.tvEmailHeader.text = profile.email
        binding.tvRut.text = profile.rut
        binding.tvAvailable.text = if (profile.isAvailable) getString(R.string.available) else getString(R.string.unavailable)

        if (!profile.photoPath.isNullOrEmpty()) {
            Glide.with(this)
                .load(profile.photoPath)
                .placeholder(R.drawable.ic_default_profile)
                .circleCrop()
                .into(binding.ivProfilePicture)
        } else {
            binding.ivProfilePicture.setImageResource(R.drawable.ic_default_profile)
        }
    }

    private fun enqueueDownloadWork() {
        // Crea y encola el Worker
        val downloadWorkRequest = OneTimeWorkRequestBuilder<BatchDownloadWorker>().build()
        WorkManager.getInstance(requireContext()).enqueue(downloadWorkRequest)

        // Muestra el diálogo de progreso
        showDownloadDialog()

        // Observa el progreso del Worker
        WorkManager.getInstance(requireContext())
            .getWorkInfoByIdLiveData(downloadWorkRequest.id)
            .observe(viewLifecycleOwner) { workInfo ->
                if (workInfo != null) {
                    if (workInfo.state.isFinished) {
                        hideDownloadDialog()
                        val finalMessage = workInfo.outputData.getString("progress") ?: getString(R.string.download_complete)
                        showProfileMessage(finalMessage, MessageTone.SUCCESS)
                    } else {
                        val progress = workInfo.progress.getString("progress")
                        val tvDynamic = downloadDialog?.findViewById<TextView>(R.id.tvProgressText)
                        tvDynamic?.text = progress
                    }
                }
            }
    }

    private fun showDownloadDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_progress, null)
        downloadDialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .show()
    }

    private fun hideDownloadDialog() {
        downloadDialog?.dismiss()
        downloadDialog = null
    }

    private fun confirmLocalCleanup(
        title: String,
        message: String,
        actionText: String,
        onConfirmed: () -> Unit
    ) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(actionText) { _, _ ->
                runCatching(onConfirmed).onFailure {
                    showProfileMessage(getString(R.string.clear_storage_failed), MessageTone.ERROR)
                }
            }
            .show()
            .apply { setCanceledOnTouchOutside(false) }
    }

    private fun setupDarkModeSwitch() {
        val prefs = requireContext().getSharedPreferences("dark_mode", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("enabled", false)
        binding.switchDarkMode.isChecked = isDark
        binding.switchDarkMode.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean("enabled", enabled).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (enabled) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }

    private fun setupRotationSwitch() {
        binding.rotationToggleRow.visibility =
            if (FeatureFlags.FEATURE_SCREEN_ROTATION_TOGGLE) View.VISIBLE else View.GONE
        if (!FeatureFlags.FEATURE_SCREEN_ROTATION_TOGGLE) return

        val prefs = requireContext().getSharedPreferences("rotation", Context.MODE_PRIVATE)
        val isAllowed = prefs.getBoolean("allowed", false)
        binding.switchRotation.isChecked = isAllowed
        binding.switchRotation.setOnCheckedChangeListener { _, allowed ->
            prefs.edit().putBoolean("allowed", allowed).apply()
            (activity as? MainActivity)?.applyRotationLock(allowed)
        }
    }

    private fun clearTemporaryFiles(): Int {
        val cacheDir = context?.cacheDir ?: return 0
        return runCatching {
            cacheDir.walkBottomUp()
                .filter { it != cacheDir && it.isFile }
                .filter { file ->
                    file.name.startsWith("camera_capture_") ||
                        file.name.startsWith("gallery_pick_") ||
                        file.name.startsWith("temp_view_") ||
                        file.name.startsWith("report_") ||
                        file.name.startsWith("image_") ||
                        file.extension.equals("pdf", ignoreCase = true) ||
                        file.extension.equals("jpg", ignoreCase = true) ||
                        file.extension.equals("png", ignoreCase = true)
                }
                .count { file -> file.delete() }
        }.getOrElse {
            showProfileMessage(getString(R.string.clear_storage_failed), MessageTone.ERROR)
            0
        }
    }

    private fun showProfileMessage(message: String, tone: MessageTone = MessageTone.INFO) {
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
        downloadDialog?.dismiss()
        downloadDialog = null
        _binding = null
    }
}
