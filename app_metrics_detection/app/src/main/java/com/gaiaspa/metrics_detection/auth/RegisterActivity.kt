package com.gaiaspa.metrics_detection.auth

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.gaiaspa.metrics_detection.databinding.ActivityRegisterBinding

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRegister.setOnClickListener {
            // Registro estático
            // Podrías simular guardado en base de datos, etc.
            // Luego redirigimos a login
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        binding.tvLogin.setOnClickListener {
            finish()
        }
    }
}
