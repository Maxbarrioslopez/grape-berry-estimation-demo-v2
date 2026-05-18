package com.gaiaspa.metrics_detection.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.databinding.FragmentHomeBinding

/**
 * Root fragment for the home/capture tab in the main navigation.
 *
 * Acts as a pass-through entry point: immediately navigates to
 * [Step1Fragment] on creation so the user lands directly on the
 * batch metadata form. Uses the nav graph's [HomeFragment] as the
 * start destination for the bottom navigation tab.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /**
     * Inflates the layout and immediately navigates to Step1Fragment
     * in [onViewCreated], so this screen is never actually seen.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * On view creation, immediately navigates to Step1Fragment.
     * This fragment serves as a trampoline — the user never sees it.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        findNavController().navigate(R.id.action_homeFragment_to_step1Fragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
