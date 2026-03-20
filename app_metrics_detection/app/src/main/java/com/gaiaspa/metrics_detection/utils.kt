package com.gaiaspa.metrics_detection
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Función para formatear el timestamp a una cadena de fecha y hora.
 */
fun formatTimestampToDateTime(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())
    return dateFormat.format(timestamp)
}
