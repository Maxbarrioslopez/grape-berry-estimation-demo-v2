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
import androidx.work.WorkManager
import com.gaiaspa.metrics_detection.auth.LoginActivity
import com.gaiaspa.metrics_detection.databinding.ActivityMainBinding
import com.gaiaspa.metrics_detection.i18n.LanguagePreferenceManager
import com.gaiaspa.metrics_detection.network.TokenProvider
import com.gaiaspa.metrics_detection.worker.SyncManager
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    companion object {
        private const val SYNC_WORK_NAME = "SyncLotesWork"
        private const val TAG = "MainActivity"
    }

    private fun applySavedNightMode() {
        val prefs = getSharedPreferences("dark_mode", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("enabled", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySavedNightMode()
        if (FeatureFlags.FEATURE_LANGUAGE_SWITCH) {
            LanguagePreferenceManager.applySavedLanguageIfAny(this)
        }

        val rotationAllowed = FeatureFlags.FEATURE_SCREEN_ROTATION_TOGGLE &&
            getSharedPreferences("rotation", Context.MODE_PRIVATE).getBoolean("allowed", false)
        applyRotationLock(rotationAllowed)

        if (!BuildConfig.DEMO_MODE) {
            SyncManager.enqueueManualSync(this)
            SyncManager.schedulePeriodicSync(this)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()

        if (!BuildConfig.DEMO_MODE) {
            observeSyncWorkerState()
        }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment

        if (navHostFragment == null) {
            Log.e(TAG, "NavHostFragment not found. Check that the id is correct.")
            return
        }

        navController = navHostFragment.navController
        binding.bottomNav.setupWithNavController(navController)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateBottomNavSelection(destination.id)
        }
    }

    private fun updateBottomNavSelection(destinationId: Int) {
        when (destinationId) {
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
                binding.bottomNav.menu.setGroupCheckable(0, false, true)
            }
        }
    }

    private fun observeSyncWorkerState() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(SYNC_WORK_NAME)
            .observe(this) { workInfos ->
                if (workInfos.isNullOrEmpty()) return@observe

                when (workInfos.first().state) {
                    androidx.work.WorkInfo.State.ENQUEUED -> {}
                    androidx.work.WorkInfo.State.RUNNING -> {
                        showToast("Syncing...")
                    }
                    androidx.work.WorkInfo.State.SUCCEEDED -> {
                        showToast("Sync completed.")
                    }
                    androidx.work.WorkInfo.State.FAILED -> {
                        showToast("Sync failed.")
                    }
                    else -> {
                        Log.d(TAG, "Worker state: ${workInfos.first().state}")
                    }
                }
            }
    }

    private fun showToast(message: String) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } else {
            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    this.cancel()
                }
            }
        }
    }

    fun applyRotationLock(allowed: Boolean) {
        requestedOrientation = if (allowed) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    fun logout() {
        if (BuildConfig.DEMO_MODE) {
            TokenProvider.clearSession()
            finishAffinity()
            return
        }
        WorkManager.getInstance(this).cancelUniqueWork(SYNC_WORK_NAME)
        TokenProvider.logout()
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
