package com.gaiaspa.metrics_detection.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.ContextCompat
import com.gaiaspa.metrics_detection.R

/**
 * Clase para dibujar sobre una única imagen utilizando resultados de segmentación.
 */
class SingleImageDrawer(private val context: Context) {

    /**
     * Obtiene el siguiente color para las cajas delimitadoras.
     * Puedes mejorar esta función para que seleccione colores de manera dinámica si lo deseas.
     */
    private var currentColorBox = 0

    private fun getNextColor(): Int {
        // Aquí puedes implementar una lógica para rotar entre varios colores si lo deseas
        return ContextCompat.getColor(context, R.color.colorPrimary)
    }

    /**
     * Obtiene el color asociado a una clase específica.
     *
     * @param clsName Nombre de la clase.
     * @return Color correspondiente a la clase.
     */
    private fun getClassColor(clsName: String): Int {
        return when {
            clsName.startsWith("pingpong") -> ContextCompat.getColor(context, R.color.brown) // Color café para pingpong
            clsName.startsWith("bunch_red") -> ContextCompat.getColor(context, R.color.dark_red) // Color rojo oscuro para bunch_red
            clsName.startsWith("grape_red") -> ContextCompat.getColor(context, R.color.pink) // Color rosado para grape_red
            clsName.startsWith("bunch_green") -> ContextCompat.getColor(context, R.color.dark_green) // Color verde oscuro para bunch_green
            clsName.startsWith("grape_green") -> ContextCompat.getColor(context, R.color.light_green) // Color verde claro para grape_green
            else -> ContextCompat.getColor(context, R.color.colorPrimary) // Color predeterminado
        }
    }

    /**
     * Dibuja las segmentaciones sobre una única imagen.
     *
     * @param original Imagen original sobre la cual dibujar.
     * @param segmentationResults Lista de resultados de segmentación.
     * @return Imagen con las segmentaciones dibujadas.
     */
    fun draw(original: Bitmap, segmentationResults: List<SegmentationResult>): Pair<Bitmap, Bitmap?> {
        if (segmentationResults.isEmpty()) {
            return Pair(original, null)
        }

        // Mapear colores por clase
        val colorMap: MutableMap<String, Int> = mutableMapOf()
        segmentationResults.forEach { result ->
            if (!colorMap.containsKey(result.box.clsName)) {
                colorMap[result.box.clsName] = getClassColor(result.box.clsName)
            }
        }
        val width = segmentationResults.first().mask[0].size
        val height = segmentationResults.first().mask.size
        val combined = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Dibujar cada resultado de segmentación
        segmentationResults.forEach { result ->
            val overlayColor = colorMap[result.box.clsName] ?: getNextColor()
            applyTransparentOverlay(combined, result, overlayColor)
        }

        return Pair(original, combined)
    }

    /**
     * Aplica una superposición transparente y dibuja bounding boxes y etiquetas.
     *
     * @param canvas Canvas sobre el cual dibujar.
     * @param segmentationResult Resultado de segmentación individual.
     * @param overlayColor Color de la superposición.
     */
    private fun applyTransparentOverlay(
        overlay: Bitmap,
        segmentationResult: SegmentationResult,
        overlayColor: Int,
    ) {
        val box = segmentationResult.box
        val width = overlay.width
        val height = overlay.height


        if (box.clsName.startsWith("bunch_")) {
            val canvas = Canvas(overlay)
            val boxPaint = Paint().apply {
                color = overlayColor
                strokeWidth = 2F
                style = Paint.Style.STROKE
            }

            val left = (box.x1 * width).toInt()
            val top = (box.y1 * height).toInt()
            val right = (box.x2 * width).toInt()
            val bottom = (box.y2 * height).toInt()

            // Dibujar el bounding box
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), boxPaint)

            val textBackgroundPaint = Paint().apply {
                color = overlayColor
                style = Paint.Style.FILL
            }

            val textPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                textSize = 10f
            }

            val bounds = android.graphics.Rect()
            // Si la clase es "bunch", agregar el bunchId en el texto
            val labelText = "${box.clsName} (ID: ${segmentationResult.bunchId})"
            textPaint.getTextBounds(labelText, 0, labelText.length, bounds)

            val textWidth = bounds.width()
            val textHeight = bounds.height()
            val padding = 8

            // Dibujar el fondo de texto con el nombre de la clase (y bunchId si es necesario)
            canvas.drawRect(
                left.toFloat(),
                top.toFloat() - textHeight - 2 * padding,
                left + textWidth + 2 * padding.toFloat(),
                top.toFloat(),
                textBackgroundPaint
            )
            // Dibujar el texto (nombre de la clase + bunchId si es un "bunch")
            canvas.drawText(
                labelText,
                left.toFloat() + padding,
                top.toFloat() - padding.toFloat(),
                textPaint
            )
        }

        // Para "grape_", solo dibujar la máscara con bordes más oscuros (sin bounding box ni texto)
        if (box.clsName.startsWith("grape_")) {
            val maskPaint = Paint().apply {
                val darkerColor = darkenColor(overlayColor)
                color = applyTransparentOverlayColor(darkerColor)
                style = Paint.Style.FILL
            }

            val mask = segmentationResult.mask
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (mask[y][x] > 0) {
                        overlay.setPixel(x, y, maskPaint.color)
                    }
                }
            }
        }

        // Para "pingpong", dibujar la máscara y el bounding box
        if (box.clsName.startsWith("pingpong")) {
            val canvas = Canvas(overlay)

            // Dibujar la máscara de pingpong
            val maskPaint = Paint().apply {
                color = applyTransparentOverlayColor(overlayColor)
                style = Paint.Style.FILL
            }

            val mask = segmentationResult.mask
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (mask[y][x] > 0) {
                        overlay.setPixel(x, y, maskPaint.color)
                    }
                }
            }

            // Dibujar el bounding box de pingpong
            val boxPaint = Paint().apply {
                color = overlayColor
                strokeWidth = 2F
                style = Paint.Style.STROKE
            }

            val left = (box.x1 * width).toInt()
            val top = (box.y1 * height).toInt()
            val right = (box.x2 * width).toInt()
            val bottom = (box.y2 * height).toInt()

            // Dibujar el bounding box
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), boxPaint)

            val textBackgroundPaint = Paint().apply {
                color = overlayColor
                style = Paint.Style.FILL
            }

            val textPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                textSize = 10f
            }

            val bounds = android.graphics.Rect()
            textPaint.getTextBounds(box.clsName, 0, box.clsName.length, bounds)

            val textWidth = bounds.width()
            val textHeight = bounds.height()
            val padding = 8

            // Dibujar el fondo de texto con el nombre de la clase
            canvas.drawRect(
                left.toFloat(),
                top.toFloat() - textHeight - 2 * padding,
                left + textWidth + 2 * padding.toFloat(),
                top.toFloat(),
                textBackgroundPaint
            )
            // Dibujar el texto (nombre de la clase)
            canvas.drawText(box.clsName, left.toFloat() + padding, top.toFloat() - padding.toFloat(), textPaint)
        }
    }

    /**
     * Oscurece un color dado reduciendo sus componentes RGB.
     *
     * @param color Color original.
     * @return Color oscurecido.
     */
    private fun darkenColor(color: Int): Int {
        val red = (Color.red(color) - 50).coerceAtLeast(0)
        val green = (Color.green(color) - 50).coerceAtLeast(0)
        val blue = (Color.blue(color) - 50).coerceAtLeast(0)
        return Color.rgb(red, green, blue)
    }

    /**
     * Aplica un nivel de transparencia al color.
     *
     * @param color Color original.
     * @return Color con transparencia aplicada.
     */
    private fun applyTransparentOverlayColor(color: Int): Int {
        val alpha = 96 // Ajusta el nivel de transparencia según sea necesario
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)

        return Color.argb(alpha, red, green, blue)
    }
}
