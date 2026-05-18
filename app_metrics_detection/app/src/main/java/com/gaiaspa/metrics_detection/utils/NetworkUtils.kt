/**
 * Utilidad para verificar la disponibilidad de conectividad de red.
 *
 * Usa [ConnectivityManager] con [NetworkCapabilities] (API 21+) para determinar
 * si el dispositivo tiene acceso a internet vía WiFi, datos móviles o Ethernet.
 * No verifica conectividad real a un host externo; solo chequea que la interfaz
 * de red esté activa.
 */
// NetworkUtils.kt
package com.gaiaspa.metrics_detection.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkUtils {

    /**
     * Verifica si el dispositivo tiene conectividad de red activa.
     *
     * @param context cualquier contexto (se usa para obtener el servicio de conectividad).
     * @return true si hay al menos una red activa con transporte WiFi, celular o Ethernet.
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
