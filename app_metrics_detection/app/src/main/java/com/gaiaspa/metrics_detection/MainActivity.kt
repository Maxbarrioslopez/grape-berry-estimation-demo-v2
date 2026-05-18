package com.gaiaspa.metrics_detection

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.gaiaspa.metrics_detection.auth.LoginActivity
import com.gaiaspa.metrics_detection.databinding.ActivityMainBinding
import com.gaiaspa.metrics_detection.i18n.LanguagePreferenceManager
import com.gaiaspa.metrics_detection.network.TokenProvider
import com.gaiaspa.metrics_detection.worker.SyncManager
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.view.View
import android.view.inputmethod.InputMethodManager

/**
 * Main navigation host.
 *
 * Hosts the bottom-navigation tabs (Home, History, Profile, Support)
 * and provides WorkManager sync lifecycle observation. On start it
 * enqueues a manual sync and schedules periodic sync at 15 min intervals.
 *
 * The logout() callback clears all tokens and WorkManager tasks before
 * redirecting to LoginActivity.
 *
 * Also manages runtime preferences: dark mode, screen rotation lock,
 * and language switching (via feature flag).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    companion object {
        private const val SYNC_WORK_NAME = "SyncLotesWork"
        private const val TAG = "MainActivity"
    }

    /**
     * Lee la preferencia de modo oscuro desde SharedPreferences y la aplica
     * globalmente mediante AppCompatDelegate.
     */
    private fun applySavedNightMode() {
        val prefs = getSharedPreferences("dark_mode", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("enabled", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    /**
     * Inicializa la actividad principal: aplica preferencias de tema, idioma y
     * rotación, dispara la sincronización inicial y periódica vía WorkManager,
     * infla el layout y configura la navegación por pestañas.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySavedNightMode()
        if (FeatureFlags.FEATURE_LANGUAGE_SWITCH) {
            LanguagePreferenceManager.applySavedLanguageIfAny(this)
        }

        val rotationAllowed = FeatureFlags.FEATURE_SCREEN_ROTATION_TOGGLE &&
            getSharedPreferences("rotation", Context.MODE_PRIVATE).getBoolean("allowed", false)
        applyRotationLock(rotationAllowed)

        // Lanzar una sincronizacion manual
        SyncManager.enqueueManualSync(this)

        // Programar sincronización periódica
        SyncManager.schedulePeriodicSync(this)

        // Inflar layout y configurar binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configurar navegación y BottomNavigationView
        setupNavigation()

        // Observar el estado del WorkManager para la sincronización
        observeSyncWorkerState()
    }


    /**
     * Configura el NavHostFragment, NavController y la vinculación con el BottomNavigationView.
     */

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment

        if (navHostFragment == null) {
            Log.e(TAG, "NavHostFragment no encontrado. Verifica que el id sea correcto.")
            return
        }

        navController = navHostFragment.navController
        binding.bottomNav.setupWithNavController(navController)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateBottomNavSelection(destination.id)
        }
    }

    /**
     * Mapea el destino actual a la opción correspondiente del BottomNavigationView.
     */
    private fun updateBottomNavSelection(destinationId: Int) {
        when (destinationId) {
            R.id.homeFragment, R.id.step1Fragment, R.id.step2Fragment -> {
                binding.bottomNav.menu.findItem(R.id.homeFragment).isChecked = true
            }
            R.id.historyFragment, R.id.historyDetailFragment -> {
                binding.bottomNav.menu.findItem(R.id.historyFragment).isChecked = true
            }
            R.id.profileFragment -> {
                binding.bottomNav.menu.findItem(R.id.profileFragment).isChecked = true
            }
            R.id.supportFragment -> {
                binding.bottomNav.menu.findItem(R.id.supportFragment).isChecked = true
            }
            else -> {
                // Para destinos que no forman parte del BottomNavigationView,
                // se deseleccionan todas las opciones.
                binding.bottomNav.menu.setGroupCheckable(0, false, true)
            }
        }
    }

    /**
     * Observa el estado del worker responsable de sincronizar datos y proporciona feedback al usuario.
     */
    private fun observeSyncWorkerState() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(SYNC_WORK_NAME)
            .observe(this) { workInfos ->
                if (workInfos.isNullOrEmpty()) return@observe

                // Se utiliza el primer WorkInfo disponible
                when (workInfos.first().state) {
                    WorkInfo.State.ENQUEUED -> {
                        //showToast("Sincronización programada.")
                    }
                    WorkInfo.State.RUNNING -> {
                        showToast("Sincronizando...")
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        showToast("Sincronización completada.")
                    }
                    WorkInfo.State.FAILED -> {
                        showToast("Sincronización fallida.")
                    }
                    else -> {
                        Log.d(TAG, "Estado del worker: ${workInfos.first().state}")
                    }
                }
            }
    }

    /**
     * Muestra un Toast con el mensaje especificado.
     *
     * Se utiliza repeatOnLifecycle para asegurar que el Toast se muestre
     * únicamente cuando el Lifecycle se encuentre al menos en estado RESUMED.
     */
    private fun showToast(message: String) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } else {
            lifecycleScope.launch {
                // Se ejecuta el bloque siempre que el lifecycle esté en estado RESUMED.
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    // Cancelamos la coroutine para que se ejecute solo una vez.
                    this.cancel()
                }
            }
        }
    }

    /**
     * Aplica el bloqueo de rotación según preferencia del usuario.
     * OFF (default) → solo portrait; ON → sensor-based (landscape + portrait).
     */
    fun applyRotationLock(allowed: Boolean) {
        requestedOrientation = if (allowed) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /**
     * Realiza el logout, limpia los tokens y redirige a LoginActivity.
     */
    fun logout() {
        WorkManager.getInstance(this).cancelUniqueWork(SYNC_WORK_NAME)
        TokenProvider.logout()
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        //startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
