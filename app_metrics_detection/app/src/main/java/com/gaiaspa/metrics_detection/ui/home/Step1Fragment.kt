package com.gaiaspa.metrics_detection.ui.home

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.databinding.FragmentStep1Binding

class Step1Fragment : Fragment() {

    private var _binding: FragmentStep1Binding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStep1Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Restaurar inputs
        homeViewModel.company.observe(viewLifecycleOwner) { binding.etCompany.setText(it) }
        homeViewModel.vessel.observe(viewLifecycleOwner) { binding.etVessel.setText(it) }
        homeViewModel.block.observe(viewLifecycleOwner) { binding.etBlock.setText(it) }

        // Varieties -> adapter
        homeViewModel.availableVarieties.observe(viewLifecycleOwner) { vars ->
            val adapter = android.widget.ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                vars
            )
            binding.etVariety.setAdapter(adapter)

            // restaurar selección previa
            homeViewModel.selectedVariety.value?.let { sel ->
                binding.etVariety.setText(sel.name, false)
            }

            updateButtonState()
        }

        // Al seleccionar variedad, guardarla
        binding.etVariety.setOnItemClickListener { parent, _, position, _ ->
            val sel = parent.getItemAtPosition(position) as VarietyOption
            homeViewModel.selectedVariety.value = sel
            updateButtonState()
        }

        // Si se cambia la seleccion por LiveData (ej. restore), reflejarla en UI
        homeViewModel.selectedVariety.observe(viewLifecycleOwner) { sel ->
            if (sel != null && binding.etVariety.text.toString() != sel.name) {
                binding.etVariety.setText(sel.name, false)
            }
            updateButtonState()
        }

        // TextWatcher para habilitar boton
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateButtonState()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        binding.etCompany.addTextChangedListener(textWatcher)
        binding.etVessel.addTextChangedListener(textWatcher)
        binding.etBlock.addTextChangedListener(textWatcher)

        binding.btnNext.setOnClickListener {
            val c = binding.etCompany.text.toString().take(15)
            val v = binding.etVessel.text.toString().take(15)
            val b = binding.etBlock.text.toString().take(15)

            homeViewModel.company.value = c
            homeViewModel.vessel.value = v
            homeViewModel.block.value = b

            // La variedad ya quedó en selectedVariety
            findNavController().navigate(R.id.action_step1Fragment_to_step2Fragment)
        }

        updateButtonState()
    }

    private fun updateButtonState() {
        val hasText = binding.etCompany.text.toString().isNotEmpty() &&
                binding.etVessel.text.toString().isNotEmpty() &&
                binding.etBlock.text.toString().isNotEmpty()

        val hasVariety = homeViewModel.selectedVariety.value != null

        val enabled = hasText && hasVariety
        binding.btnNext.isEnabled = enabled

        val colorRes = if (enabled) R.color.colorPrimary else R.color.button_disabled
        binding.btnNext.backgroundTintList =
            ContextCompat.getColorStateList(requireContext(), colorRes)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
