package com.gaiaspa.metrics_detection
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Function to format a timestamp to a date-time string.
 */
fun formatTimestampToDateTime(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())
    return dateFormat.format(timestamp)
}
