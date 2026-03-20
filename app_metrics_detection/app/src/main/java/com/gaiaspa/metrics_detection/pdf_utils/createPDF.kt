@file:JvmName("CreatePDF")

package com.gaiaspa.metrics_detection.pdf_utils

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.BitmapFactory
import com.gaiaspa.metrics_detection.data.model.Lote
import java.io.File
import java.util.*
import kotlin.math.max

/*──────────────────── Utilidades generales ───────────────────*/

/** Potencia de 2 más pequeña para que ambos lados ≤ reqPx. */
private fun calcSample(w: Int, h: Int, reqPx: Int = 300): Int {
    var s = 1
    while (w / s > reqPx || h / s > reqPx) s = s shl 1
    return s
}

/** Decodifica un bitmap compacto y seguro; puede devolver `null`. */
private fun safeDecode(path: String, reqPx: Int = 300): Bitmap? {
    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, probe)
    val opts = BitmapFactory.Options().apply {
        inSampleSize       = calcSample(probe.outWidth, probe.outHeight, reqPx)
        inPreferredConfig  = Bitmap.Config.RGB_565   // 2 B/px
        inDither           = false
    }
    return BitmapFactory.decodeFile(path, opts)
}

/*──────────────────── Generación de PDF ──────────────────────*/
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
    val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; isFakeBoldText = true }
    val headP  = Paint(titleP).apply { textSize = 13f; isFakeBoldText = true }
    val bodyP  = Paint(headP ).apply { textSize = 11f; isFakeBoldText = false }
    val smallP = Paint(bodyP ).apply { textSize = 9f ; color = Color.parseColor("#616161") }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0"); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /* ===========  PORTADA  =========== */
    var y = margin + 24f
    c.drawText(appName, margin, y, titleP); y += 32f
    val now = android.text.format.DateFormat.format("dd MMM yyyy • HH:mm", Date())
    c.drawText("Reporte generado: $now", margin, y, bodyP); y += 18f
    c.drawText("Total de lotes: ${lotes.size}", margin, y, bodyP); y += 26f
    c.drawText("Resumen:", margin, y, headP); y += 18f
    lotes.forEachIndexed { i, l ->
        val st = if (l.synced) "cloud" else "local"
        c.drawText("${i + 1}. ID ${l.id}  –  ${l.company}  [$st]", margin + 10f, y, smallP); y += 14f
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
        canvas.drawText("ID ${lote.id}",           tx, ty, headP); ty += 16f
        canvas.drawText("Company : ${lote.company}",tx, ty, bodyP); ty += 14f
        canvas.drawText("Vessel  : ${lote.vessel}", tx, ty, bodyP); ty += 14f
        canvas.drawText("Block   : ${lote.block}",  tx, ty, bodyP); ty += 14f
        canvas.drawText("CloudId : ${lote.cloudId}",tx, ty, bodyP); ty += 14f
        canvas.drawText("UserId  : ${lote.userId}", tx, ty, bodyP)

        /* indicador cloud */
        val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (lote.synced) Color.parseColor("#2E7D32") else Color.parseColor("#C62828")
        }
        canvas.drawCircle(right - 30f, cy + 30f, 10f, cloudPaint)

        /* fecha/hora  +  imágenes */
        val date = Date(lote.predictedAt)
        val dStr = android.text.format.DateFormat.format("dd MMM yyyy  •  HH:mm", date).toString()
        canvas.drawText(dStr, right - 180f, cy + 90f, smallP)
        canvas.drawText("${lote.images.size} img", right - 80f, cy + 90f, smallP)

        cy += 130f

        /* ---- Fila (imagen + histograma) para CADA CalPredict ---- */
        /* ---------- CalPredicts con mini-img + histograma + stats ---------- */
        lote.calPredicts.forEachIndexed { idx, cp ->

            //   1. Si NO cabe otra fila completa ⇒ salto de página
            val rowH = 250f                    // alto estimado de una fila
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

            lote.images.getOrNull(idx)?.let { path ->
                BitmapFactory.decodeFile(path)?.also { bmp ->
                    canvas.drawBitmap(bmp, null, imgRect, null)
                    bmp.recycle()
                }
            }

            /* ---- 3. Histograma a la DERECHA ---- */
            if (cp.status) {
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
            canvas.drawText("Color: ${cp.bunchColor}", statsLeft, sY, bodyP); sY += 14f
            canvas.drawText("Mean : ${cp.mean}",       statsLeft, sY, bodyP); sY += 14f
            canvas.drawText("Mode : ${cp.mode}",       statsLeft, sY, bodyP); sY += 14f
            canvas.drawText("STD  : ${cp.std}",        statsLeft, sY, bodyP); sY += 14f
            canvas.drawText("Qty  : ${cp.qty}",        statsLeft, sY, bodyP)

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
        e.printStackTrace(); null
    } finally { doc.close() }
}
