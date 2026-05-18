/**
 * Generación de reportes PDF multi-página a partir de objetos de dominio [Lote].
 *
 * ## Rol en la arquitectura
 * Este archivo contiene la función pública [createLotesReportPdf] y las utilidades
 * privadas de decodificación de imágenes y resolución de rutas necesarias para construir
 * cada página del reporte. El PDF resultante incluye:
 * - Una portada con resumen de lotes.
 * - Una página por lote con cabecera (tarjeta), miniatura de imagen, histograma de
 *   calibres (delegado a [drawModernHistogram]) y tabla de estadísticas.
 * - Soporte para fusión frente/reverso usando los metadatos de [CalPredict].
 *
 * Las imágenes se decodifican a baja resolución (muestreo adaptativo) con [safeDecode]
 * para mantener el PDF ligero.
 */
@file:JvmName("CreatePDF")

package com.gaiaspa.metrics_detection.pdf_utils

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.BitmapFactory
import android.util.Log
import com.gaiaspa.metrics_detection.data.model.CalPredict
import com.gaiaspa.metrics_detection.data.model.Lote
import java.io.File
import java.util.*
import kotlin.math.max

private const val PDF_IMG_TAG = "PDF_IMG_BIND"

/*──────────────────── Utilidades generales ───────────────────*/

/** Potencia de 2 más pequeña para que ambos lados ≤ reqPx. */
private fun calcSample(w: Int, h: Int, reqPx: Int = 300): Int {
    var s = 1
    while (w / s > reqPx || h / s > reqPx) s = s shl 1
    return s
}

/** Decodifica un bitmap compacto y seguro; puede devolver `null`. */
private fun safeDecode(path: String, reqPx: Int = 300): Bitmap? {
    val cleanPath = path.replace("file://", "")
    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(cleanPath, probe)
    val opts = BitmapFactory.Options().apply {
        inSampleSize       = calcSample(probe.outWidth, probe.outHeight, reqPx)
        inPreferredConfig  = Bitmap.Config.RGB_565   // 2 B/px
        inDither           = false
    }
    val bmp = BitmapFactory.decodeFile(cleanPath, opts)
    Log.d(PDF_IMG_TAG, "safeDecode path=$cleanPath result=${bmp != null} (${probe.outWidth}x${probe.outHeight})")
    return bmp
}

/**
 * Busca el grupo de fusión correspondiente al índice [index] dentro de los metadatos
 * de fusión de este [CalPredict].
 *
 * Primero busca por [FusionGroup.fusedPredictionIndex]; si no encuentra, devuelve el
 * grupo en la posición [index] como fallback posicional.
 */
private fun CalPredict.fusionGroupForIndex(index: Int) =
    fusionMetadata?.groups?.firstOrNull { it.fusedPredictionIndex == index }
        ?: fusionMetadata?.groups?.getOrNull(index)

/**
 * Resuelve la ruta de imagen preferida para un [CalPredict] en el índice dado,
 * guiada por [FusionGroup.selectedImageRole] ("A" o "B").
 *
 * La prioridad de busqueda es: upload > normalized > source > overlay, mas las
 * rutas especificas de la vista A o B registradas en el grupo de fusion.
 *
 * @return Ruta absoluta del archivo mas prioritario que exista en disco, o `null`.
 */
private fun Lote.selectedMetadataImagePath(cp: CalPredict, index: Int): String? {
    val group = cp.fusionGroupForIndex(index) ?: return null
    return if (group.selectedImageRole == "B") {
        firstExistingValidPath(
            uploadImages.getOrNull(index),
            normalizedImages.getOrNull(index),
            sourceImages.getOrNull(index),
            overlayImages.getOrNull(index),
            group.viewBUploadPath,
            group.viewBSourcePath,
            group.viewBOverlayPath
        )
    } else if (group.selectedImageRole == "A") {
        firstExistingValidPath(
            uploadImages.getOrNull(index),
            normalizedImages.getOrNull(index),
            sourceImages.getOrNull(index),
            overlayImages.getOrNull(index),
            group.viewAUploadPath,
            group.viewASourcePath,
            group.viewAOverlayPath
        )
    } else {
        null
    }
}

/**
 * Devuelve la primera ruta de la lista que apunte a un archivo existente y no vacio.
 *
 * Recorre los paths en orden; en cuanto encuentra uno cuyo archivo en disco tenga
 * longitud > 0, devuelve esa ruta limpiando el prefijo `file://`.
 * Si ninguno es valido, devuelve `null`.
 */
private fun firstExistingValidPath(vararg paths: String?): String? {
    return paths.firstOrNull { path ->
        val cleanPath = path?.replace("file://", "").orEmpty()
        cleanPath.isNotBlank() && File(cleanPath).exists() && File(cleanPath).length() > 0
    }?.replace("file://", "")
}

/**
 * Resuelve la imagen representativa para la prediccion en posicion [index] dentro de un [Lote].
 *
 * ## Prioridad
 * 1. Ruta seleccionada via metadatos de fusion ([selectedMetadataImagePath]).
 * 2. Fallback con la misma prioridad que [Lote.images]: overlay (resultado visual)
 *    -> normalized -> source -> upload, usando la imagen en el indice exacto si existe
 *    o la primera disponible de cada lista en caso contrario.
 *
 * @return Ruta absoluta del archivo mas prioritario, o `null` si ninguno esta disponible.
 */
private fun Lote.representativePdfImagePath(index: Int): String? {
    val prediction = calPredicts.getOrNull(index)
    val metadataPath = prediction?.let { selectedMetadataImagePath(it, index) }
    if (!metadataPath.isNullOrBlank()) {
        Log.d("PDF_IMAGE_RESOLVE", "lote=$id idx=$index selectedMetadataImagePath=$metadataPath")
        return metadataPath
    }

    // Misma prioridad que Lote.images: overlay (resultado visual) → normalized → source → upload
    val result = firstExistingValidPath(
        overlayImages.getOrNull(index),
        normalizedImages.getOrNull(index),
        sourceImages.getOrNull(index),
        uploadImages.getOrNull(index),
        overlayImages.firstOrNull(),
        normalizedImages.firstOrNull(),
        sourceImages.firstOrNull(),
        uploadImages.firstOrNull()
    )
    Log.d("PDF_IMAGE_RESOLVE", "lote=$id idx=$index fallback resolved=$result (overlay=${overlayImages.getOrNull(index)})")
    return result
}

/*──────────────────── Generacion de PDF ──────────────────────*/
/**
 * Genera un reporte PDF multi-pagina con los datos de los lotes proporcionados.
 *
 * ## Estructura del PDF
 * - **Portada**: titulo, fecha/hora de generacion, total de lotes y resumen por lote.
 * - **Paginas de lote**: una por cada [Lote], con tarjeta de cabecera (ID, empresa,
 *   buque, bloque, indicador de sincronizacion, fecha e imagenes), seguidas de una
 *   fila por cada [CalPredict] que incluye miniatura de imagen a la izquierda,
 *   histograma de calibres a la derecha y tabla de estadisticas debajo.
 *
 * Las dimensiones estan fijadas para A4 a 72 dpi (595x842 px).
 *
 * @param lotes   Lista de lotes a incluir en el reporte.
 * @param context Contexto de Android usado para el directorio de salida.
 * @param appName Nombre de la aplicacion que aparece en la portada.
 * @return Archivo PDF generado en el directorio de cache, o `null` si falla la escritura.
 */
fun createLotesReportPdf(
    lotes: List<Lote>,
    context: Context,
    appName: String = "Metrics Detection"
): File? {

    /* ─── 1. Configuración básica (A4 @72 dpi) ─── */
    val W = 595; val H = 842
    val margin = 32f
    val doc = PdfDocument()
    var pageNo = 1
    var page  = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
    var c     = page.canvas

    /* Paints */
    val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; isFakeBoldText = true; color = Color.parseColor("#1B5E20") }
    val headP  = Paint(titleP).apply { textSize = 13f; isFakeBoldText = true; color = Color.parseColor("#1F2933") }
    val bodyP  = Paint(headP ).apply { textSize = 11f; isFakeBoldText = false }
    val smallP = Paint(bodyP ).apply { textSize = 9f ; color = Color.parseColor("#616161") }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DDE5DD"); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FBFDFB") }

    /* ===========  PORTADA  =========== */
    var y = margin + 24f
    c.drawText(appName, margin, y, titleP); y += 32f
    val now = android.text.format.DateFormat.format("dd MMM yyyy • HH:mm", Date())
        c.drawText("Reporte generado: $now", margin, y, bodyP); y += 18f
        c.drawText("Total de lotes: ${lotes.size}", margin, y, bodyP); y += 26f
        c.drawText("Resumen:", margin, y, headP); y += 18f
        lotes.forEachIndexed { i, l ->
            val st = if (l.synced) "synced" else "local"
            c.drawText("${i + 1}. Lot #${l.id}  –  ${l.company}  [$st]", margin + 10f, y, smallP); y += 14f
        if (y > H - margin) { doc.finishPage(page); page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create()); c = page.canvas; y = margin }
    }
    doc.finishPage(page)

    /* ===========  PÁGINAS DE LOTES  =========== */
    fun newPage(): Canvas {
        page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
        return page.canvas
    }

    lotes.forEach { lote ->
        var canvas = newPage()
        var cy = margin + 20f
        val left = margin
        val right = W - margin

        /* ---- Tarjeta cabecera ---- */
        canvas.drawRoundRect(RectF(left, cy, right, cy + 110f), 10f, 10f, cardBg)
        canvas.drawRoundRect(RectF(left, cy, right, cy + 110f), 10f, 10f, border)

        var tx = left + 20f
        var ty = cy + 24f
        canvas.drawText("Lot #${lote.id}",         tx, ty, headP); ty += 16f
        canvas.drawText("Company : ${lote.company}",tx, ty, bodyP); ty += 14f
        canvas.drawText("Vessel  : ${lote.vessel}", tx, ty, bodyP); ty += 14f
        canvas.drawText("Block   : ${lote.block}",  tx, ty, bodyP); ty += 14f

        /* indicador cloud */
        val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (lote.synced) Color.parseColor("#2E7D32") else Color.parseColor("#C62828")
        }
        canvas.drawCircle(right - 30f, cy + 30f, 10f, cloudPaint)

        /* fecha/hora  +  imágenes */
        val date = Date(lote.predictedAt)
        val dStr = android.text.format.DateFormat.format("dd MMM yyyy  •  HH:mm", date).toString()
        canvas.drawText(dStr, right - 180f, cy + 90f, smallP)
        val imageCandidates = lote.overlayImages.ifEmpty { lote.normalizedImages.ifEmpty { lote.sourceImages.ifEmpty { lote.uploadImages } } }
        canvas.drawText("${imageCandidates.size} img", right - 80f, cy + 90f, smallP)

        cy += 130f

        /* ---- Fila (imagen + histograma) para CADA CalPredict ---- */
        /* ---------- CalPredicts con mini-img + histograma + stats ---------- */
        lote.calPredicts.forEachIndexed { idx, cp ->

            //   1. Si NO cabe otra fila completa ⇒ salto de página
            val rowH = 290f                    // alto estimado de una fila
            if (cy + rowH > H - margin) {
                doc.finishPage(page)
                page   = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
                canvas = page.canvas
                cy     = margin
            }

            /* ---- contenedor de la fila (card) ---- */
            val rowLeft  = left
            val rowRight = right
            val rowTop   = cy
            val rowBot   = cy + rowH
            canvas.drawRoundRect(RectF(rowLeft, rowTop, rowRight, rowBot), 8f, 8f, cardBg)
            canvas.drawRoundRect(RectF(rowLeft, rowTop, rowRight, rowBot), 8f, 8f, border)

            /* ---- cálculo de proporciones ---- */
            val colGap  = 12f
            val colWImg = (rowRight - rowLeft - 2 * 20f - colGap) * .25f      // 25 %
            val colWHst = (rowRight - rowLeft - 2 * 20f - colGap) * .75f      // 75 %

            /* ---- 2. Miniatura a la IZQUIERDA ---- */
            val imgLeft = rowLeft + 20f
            val imgTop  = rowTop  + 20f
            val imgRect = RectF(imgLeft, imgTop, imgLeft + colWImg, imgTop + colWImg)

            val group = cp.fusionGroupForIndex(idx)
            val isInvalidFused = group != null && (!cp.status || cp.qty <= 0)
            val selectedImagePath = lote.representativePdfImagePath(idx)
            Log.d(PDF_IMG_TAG, "Lote ${lote.id} idx=$idx selectedImagePath=$selectedImagePath")
            selectedImagePath?.let { path ->
                val exists = File(path.replace("file://", "")).exists()
                Log.d(PDF_IMG_TAG, "Lote ${lote.id} idx=$idx path=$path exists=$exists")
                safeDecode(path, reqPx = 360)?.also { bmp ->
                    Log.d(PDF_IMG_TAG, "Lote ${lote.id} idx=$idx bitmap OK ${bmp.width}x${bmp.height}")
                    canvas.drawBitmap(bmp, null, imgRect, null)
                    bmp.recycle()
                } ?: run {
                    Log.w(PDF_IMG_TAG, "Lote ${lote.id} idx=$idx decode FAILED path=$path exists=$exists")
                    canvas.drawText("Imagen no disponible.", imgRect.left, imgTop + 24f, bodyP)
                }
            } ?: run {
                Log.w(PDF_IMG_TAG, "Lote ${lote.id} idx=$idx sin path candidato")
                canvas.drawText("Imagen no disponible.", imgRect.left, imgTop + 24f, bodyP)
            }

            /* ---- 3. Histograma a la DERECHA ---- */
            if (cp.status && !isInvalidFused) {
                val histLeft = imgRect.right + colGap
                val histTop  = imgTop
                val histH    = colWImg                          // 1:1 aprox.
                drawModernHistogram(
                    canvas = canvas,
                    left   = histLeft,
                    top    = histTop,
                    width  = colWHst,
                    height = histH,
                    bins   = cp.bins,
                    pred   = cp.pred.map { max(0, it) },
                    paint  = smallP
                )
            } else if (isInvalidFused) {
                val errX = imgRect.right + colGap
                val errY = imgTop + 24f
                canvas.drawText("No se detectaron uvas válidas.", errX, errY, bodyP)
            } else {
                // Mensaje de error centrado donde iría el histograma
                val errX = imgRect.right + colGap
                val errY = imgTop + 24f
                canvas.drawText("Error: ${cp.error}", errX, errY, bodyP)
            }

            /* ---- 4. Stats DEBAJO de la fila completa ---- */
            val statsLeft = rowLeft + 20f
            val statsTop  = rowTop + colWImg + 34f
            bodyP.isFakeBoldText = true
            canvas.drawText("#${idx + 1}", statsLeft, statsTop, bodyP)
            bodyP.isFakeBoldText = false
            var sY = statsTop + 14f
            if (isInvalidFused) {
                // Mensaje ya mostrado en el área del histograma; stats area queda limpio
                sY += 14f
            } else {
                canvas.drawText("Variety : ${cp.bunchColor}", statsLeft, sY, bodyP); sY += 14f
                canvas.drawText("Mean    : ${cp.mean}",       statsLeft, sY, bodyP); sY += 14f
                canvas.drawText("Mode    : ${cp.mode}",       statsLeft, sY, bodyP); sY += 14f
                canvas.drawText("STD     : ${cp.std}",        statsLeft, sY, bodyP); sY += 14f
                canvas.drawText("QTY     : ${cp.qty}",        statsLeft, sY, bodyP)
            }
            group?.takeUnless { isInvalidFused }?.let {
                sY += 14f
                canvas.drawText("Resultado Frente/Reverso", statsLeft, sY, bodyP)
            }

            /* avanzar cursor Y a la siguiente fila */
            cy += rowH + 16f
        }
        doc.finishPage(page)
    }

    /* archivo con timestamp */
    val ts = android.text.format.DateFormat.format("yyyyMMdd_HHmmss", Date())
    val out = File(context.cacheDir, "report_$ts.pdf")
    return try {
        out.outputStream().use { doc.writeTo(it) }; out
    } catch (e: Exception) {
        null
    } finally { doc.close() }
}
