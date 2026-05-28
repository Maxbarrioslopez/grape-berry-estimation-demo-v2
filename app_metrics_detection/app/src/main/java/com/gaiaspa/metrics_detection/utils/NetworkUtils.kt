/**
 * Utility for checking network connectivity availability.
 *
 * Uses [ConnectivityManager] with [NetworkCapabilities] (API 21+) to determine
 * whether the device has internet access via WiFi, mobile data, or Ethernet.
 * Does not verify actual connectivity to an external host; only checks that the
 * network interface is active.
 */
// NetworkUtils.kt
package com.gaiaspa.metrics_detection.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkUtils {

    /**
     * Checks if the device has active network connectivity.
     *
     * @param context any context (used to obtain the connectivity service).
     * @return true if there is at least one active network with WiFi, cellular, or Ethernet transport.
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val networkCapabilities =
            connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        return when {
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }
}
