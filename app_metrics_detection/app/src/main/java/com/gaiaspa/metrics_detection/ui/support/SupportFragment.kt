package com.gaiaspa.metrics_detection.ui.support

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.gaiaspa.metrics_detection.R
import com.gaiaspa.metrics_detection.databinding.FragmentSupportBinding

class SupportFragment : Fragment() {

    private var _binding: FragmentSupportBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSupportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Contact info
        binding.tvEmail.text = getString(R.string.support_email)
        binding.tvPhone.text = getString(R.string.support_phone)
        binding.tvHours.text = getString(R.string.contact_hours)

        // Traditional FAQ questions & answers
        binding.faq1Question.text = getString(R.string.faq_password_recovery)
        binding.faq1Answer.text = getString(R.string.faq_password_recovery_answer)

        binding.faq2Question.text = getString(R.string.faq_history_access)
        binding.faq2Answer.text = getString(R.string.faq_history_access_answer)

        binding.faq3Question.text = getString(R.string.faq_contact)
        binding.faq3Answer.text = getString(R.string.faq_contact_answer)

        // Toggle helper function
        fun setupToggle(container: View, answer: View, icon: ImageView) {
            container.setOnClickListener {
                val open = answer.visibility == View.VISIBLE
                answer.visibility = if (open) View.GONE else View.VISIBLE
                icon.setImageResource(
                    if (open) R.drawable.ic_expand_more
                    else R.drawable.ic_expand_less
                )
            }
        }

        // Set up toggles
        setupToggle(binding.faq1Header, binding.faq1Answer, binding.faq1Icon)
        setupToggle(binding.faq3Header, binding.faq3Answer, binding.faq3Icon)
        setupToggle(binding.faq2Header, binding.faq2Answer, binding.faq2Icon)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
