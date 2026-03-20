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

        // Datos de contacto
        binding.tvEmail.text = "support@metrics.com"
        binding.tvPhone.text = "+56 9 8765 4321"
        binding.tvHours.text = "Lunes – Viernes / 9:00 – 18:00"

        // Preguntas & respuestas
        binding.faq1Question.text = "¿Cómo puedo recuperar mi contraseña?"
        binding.faq1Answer.text = "Para recuperar tu contraseña, haz clic en '¿Olvidaste tu contraseña?' en la pantalla de inicio de sesión."

        binding.faq2Question.text = "¿Dónde puedo consultar mi historial de lotes?"
        binding.faq2Answer.text = "Puedes acceder al historial de lotes en la pestaña 'Historial' en la barra de navegación inferior."

        binding.faq3Question.text = "¿Cómo puedo contactar al soporte técnico?"
        binding.faq3Answer.text = "Puedes contactarnos a través del correo electrónico o el teléfono indicados arriba."

        // Función helper para toggle
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


        // Configuramos toggles
        setupToggle(binding.faq1Header, binding.faq1Answer, binding.faq1Icon)
        setupToggle(binding.faq3Header, binding.faq3Answer, binding.faq3Icon)
        setupToggle(binding.faq2Header, binding.faq2Answer, binding.faq2Icon)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
