package com.gaiaspa.metrics_detection.ui.profile

import android.os.Bundle
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.gaiaspa.metrics_detection.MainActivity
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.data.model.Profile
import com.gaiaspa.metrics_detection.data.repository.LoteRepository
import com.gaiaspa.metrics_detection.data.repository.ProfileRepository
import com.gaiaspa.metrics_detection.databinding.FragmentProfileBinding
import com.gaiaspa.metrics_detection.ui.history.HistoryViewModel
import com.gaiaspa.metrics_detection.worker.BatchDownloadWorker

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
        viewModelFactory = ProfileViewModelFactory(repository, loteRepository)
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
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            }
        }

        // Boton Logout
        binding.btnLogout.setOnClickListener {
            viewModel.logout()
            (activity as? MainActivity)?.logout()
        }

        // Boton para iniciar descarga mediante Worker
        binding.btnDownloadLotes.setOnClickListener {
            enqueueDownloadWork()
        }

        // Boton para eliminar solo los datos sincronizados (Danger Zone)
        binding.btnClearDataSynced.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Eliminar datos sincronizados")
                .setMessage("¿Estás seguro de que deseas eliminar los lotes sincronizados?")
                .setPositiveButton("Sí, eliminar") { _, _ ->
                    viewModel.clearOnlyDataSynced()
                    Toast.makeText(requireContext(), "Datos sincronizados eliminados", Toast.LENGTH_SHORT).show()
                    historyViewModel.refreshLotes()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        // Boton para eliminar TODOS los datos locales (Danger Zone)
        binding.btnClearAllData.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Eliminar TODOS los datos locales")
                .setMessage("Esta acción borrará todos los datos locales. Los datos no sincronizados se borrarán de forma permanente. ¿Deseas continuar?")
                .setPositiveButton("Sí, eliminar") { _, _ ->
                    viewModel.clearLocalData()
                    Toast.makeText(requireContext(), "Todos los datos locales han sido eliminados", Toast.LENGTH_SHORT).show()
                    historyViewModel.refreshLotes()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun updateUI(profile: Profile) {
        binding.tvNameHeader.text = "${profile.name} ${profile.lastname}"
        binding.tvRoleHeader.text = profile.role
        binding.tvEmailHeader.text = profile.email
        binding.tvRut.text = profile.rut
        binding.tvAvailable.text = if (profile.isAvailable) "Disponible" else "No disponible"

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
                        val finalMessage = workInfo.outputData.getString("progress") ?: "Descarga completada."
                        Toast.makeText(requireContext(), finalMessage, Toast.LENGTH_LONG).show()
                    } else {
                        val progress = workInfo.progress.getString("progress")
                        val tvDynamic = downloadDialog?.findViewById<TextView>(R.id.tvProgressText)
                        tvDynamic?.text = progress
                    }
                }
            }
    }

    private fun showDownloadDialog() {
        val builder = AlertDialog.Builder(requireContext())
        val dialogView = layoutInflater.inflate(R.layout.dialog_progress, null)
        builder.setView(dialogView)
        builder.setCancelable(false)
        downloadDialog = builder.create()
        downloadDialog?.show()
    }

    private fun hideDownloadDialog() {
        downloadDialog?.dismiss()
        downloadDialog = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
