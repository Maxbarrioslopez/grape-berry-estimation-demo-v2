package com.gaiaspa.metrics_detection

import android.app.Application
import com.gaiaspa.metrics_detection.network.TokenProvider

class MetricsDetectionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TokenProvider.init(this)
    }
}
