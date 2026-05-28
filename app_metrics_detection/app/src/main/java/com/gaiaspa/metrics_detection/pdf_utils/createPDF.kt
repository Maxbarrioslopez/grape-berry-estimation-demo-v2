/**
 * Multi-page PDF report generation from [Lote] domain objects.
 *
 * ## Architectural role
 * This file contains the public function [createLotesReportPdf] and private
 * image decoding and path resolution utilities needed to build each report page.
 * The resulting PDF includes:
 * - A cover page with a batch summary.
 * - One page per batch with a header (card), image thumbnail, calibre histogram
 *   (delegated to [drawModernHistogram]) and a statistics table.
 * - Support for front/back fusion using [CalPredict] metadata.
 *
 * Images are decoded at low resolution (adaptive sampling) with [safeDecode]
 * to keep the PDF lightweight.
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

/*──────────────────── General utilities ───────────────────*/

/** Smallest power of 2 so that both sides ≤ reqPx. */
private fun calcSample(w: Int, h: Int, reqPx: Int = 300): Int {
    var s = 1
    while (w / s > reqPx || h / s > reqPx) s = s shl 1
    return s
}

/** Decodes a compact and safe bitmap; may return `null`. */
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
 * Finds the fusion group corresponding to index [index] within the fusion metadata
 * of this [CalPredict].
 *
 * First searches by [FusionGroup.fusedPredictionIndex]; if not found, returns the
 * group at position [index] as a positional fallback.
 */
private fun CalPredict.fusionGroupForIndex(index: Int) =
    fusionMetadata?.groups?.firstOrNull { it.fusedPredictionIndex == index }
        ?: fusionMetadata?.groups?.getOrNull(index)

/**
 * Resolves the preferred image path for a [CalPredict] at the given index,
 * guided by [FusionGroup.selectedImageRole] ("A" or "B").
 *
 * The search priority is: upload > normalized > source > overlay, plus the
 * specific view A or B paths registered in the fusion group.
 *
 * @return Absolute path of the highest-priority file that exists on disk, or `null`.
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
 * Returns the first path in the list that points to an existing and non-empty file.
 *
 * Iterates through the paths in order; as soon as it finds one whose file on disk has
 * length > 0, returns that path after cleaning the `file://` prefix.
 * If none is valid, returns `null`.
 */
private fun firstExistingValidPath(vararg paths: String?): String? {
    return paths.firstOrNull { path ->
        val cleanPath = path?.replace("file://", "").orEmpty()
        cleanPath.isNotBlank() && File(cleanPath).exists() && File(cleanPath).length() > 0
    }?.replace("file://", "")
}

/**
 * Resolves the representative image for the prediction at position [index] within a [Lote].
 *
 * ## Priority
 * 1. Path selected via fusion metadata ([selectedMetadataImagePath]).
 * 2. Fallback with the same priority as [Lote.images]: overlay (visual result)
 *    -> normalized -> source -> upload, using the image at the exact index if it exists
 *    or the first available from each list otherwise.
 *
 * @return Absolute path of the highest-priority file, or `null` if none is available.
 */
private fun Lote.representativePdfImagePath(index: Int): String? {
    val prediction = calPredicts.getOrNull(index)
    val metadataPath = prediction?.let { selectedMetadataImagePath(it, index) }
    if (!metadataPath.isNullOrBlank()) {
        Log.d("PDF_IMAGE_RESOLVE", "lote=$id idx=$index selectedMetadataImagePath=$metadataPath")
        return metadataPath
    }

    // Same priority as Lote.images: overlay (visual result) → normalized → source → upload
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

/*──────────────────── PDF generation ──────────────────────*/
/**
 * Generates a multi-page PDF report with the data from the provided batches.
 *
 * ## PDF structure
 * - **Cover page**: title, date/time of generation, total batches and summary per batch.
 * - **Batch pages**: one per [Lote], with a header card (ID, company, vessel, block,
 *   sync indicator, date and images), followed by a row per [CalPredict] that includes
 *   an image thumbnail on the left, a calibre histogram on the right,
 *   and a statistics table below.
 *
 * Dimensions are fixed for A4 at 72 dpi (595x842 px).
 *
 * @param lotes   List of batches to include in the report.
 * @param context Android Context used for the output directory.
 * @param appName Application name shown on the cover page.
 * @return PDF file generated in the cache directory, or `null` if writing fails.
 */
fun createLotesReportPdf(
    lotes: List<Lote>,
    context: Context,
    appName: String = "Metrics Detection"
): File? {

    /* ─── 1. Basic config (A4 @72 dpi) ─── */
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

    /* ===========  COVER  =========== */
    var y = margin + 24f
    c.drawText(appName, margin, y, titleP); y += 32f
    val now = android.text.format.DateFormat.format("dd MMM yyyy • HH:mm", Date())
        c.drawText("Report generated: $now", margin, y, bodyP); y += 18f
        c.drawText("Total batches: ${lotes.size}", margin, y, bodyP); y += 26f
        c.drawText("Summary:", margin, y, headP); y += 18f
        lotes.forEachIndexed { i, l ->
            val st = if (l.synced) "synced" else "local"
            c.drawText("${i + 1}. Lot #${l.id}  –  ${l.company}  [$st]", margin + 10f, y, smallP); y += 14f
        if (y > H - margin) { doc.finishPage(page); page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create()); c = page.canvas; y = margin }
    }
    doc.finishPage(page)

    /* ===========  BATCH PAGES  =========== */
    fun newPage(): Canvas {
        page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
        return page.canvas
    }

    lotes.forEach { lote ->
        var canvas = newPage()
        var cy = margin + 20f
        val left = margin
        val right = W - margin

        /* ---- Header card ---- */
        canvas.drawRoundRect(RectF(left, cy, right, cy + 110f), 10f, 10f, cardBg)
        canvas.drawRoundRect(RectF(left, cy, right, cy + 110f), 10f, 10f, border)

        var tx = left + 20f
        var ty = cy + 24f
        canvas.drawText("Lot #${lote.id}",         tx, ty, headP); ty += 16f
        canvas.drawText("Company : ${lote.company}",tx, ty, bodyP); ty += 14f
        canvas.drawText("Vessel  : ${lote.vessel}", tx, ty, bodyP); ty += 14f
        canvas.drawText("Block   : ${lote.block}",  tx, ty, bodyP); ty += 14f

        /* cloud indicator */
        val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (lote.synced) Color.parseColor("#2E7D32") else Color.parseColor("#C62828")
        }
        canvas.drawCircle(right - 30f, cy + 30f, 10f, cloudPaint)

        /* date/time + images */
        val date = Date(lote.predictedAt)
        val dStr = android.text.format.DateFormat.format("dd MMM yyyy  •  HH:mm", date).toString()
        canvas.drawText(dStr, right - 180f, cy + 90f, smallP)
        val imageCandidates = lote.overlayImages.ifEmpty { lote.normalizedImages.ifEmpty { lote.sourceImages.ifEmpty { lote.uploadImages } } }
        canvas.drawText("${imageCandidates.size} img", right - 80f, cy + 90f, smallP)

        cy += 130f

        /* ---- Row (image + histogram) for EACH CalPredict ---- */
        /* ---------- CalPredicts with mini-img + histogram + stats ---------- */
        lote.calPredicts.forEachIndexed { idx, cp ->

            //   1. If a complete row does NOT fit => page break
            val rowH = 290f                    // estimated row height
            if (cy + rowH > H - margin) {
                doc.finishPage(page)
                page   = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
                canvas = page.canvas
                cy     = margin
            }

            /* ---- row container (card) ---- */
            val rowLeft  = left
            val rowRight = right
            val rowTop   = cy
            val rowBot   = cy + rowH
            canvas.drawRoundRect(RectF(rowLeft, rowTop, rowRight, rowBot), 8f, 8f, cardBg)
            canvas.drawRoundRect(RectF(rowLeft, rowTop, rowRight, rowBot), 8f, 8f, border)

            /* ---- proportion calculation ---- */
            val colGap  = 12f
            val colWImg = (rowRight - rowLeft - 2 * 20f - colGap) * .25f      // 25 %
            val colWHst = (rowRight - rowLeft - 2 * 20f - colGap) * .75f      // 75 %

            /* ---- 2. Thumbnail on the LEFT ---- */
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
                    canvas.drawText("Image not available.", imgRect.left, imgTop + 24f, bodyP)
                }
            } ?: run {
                Log.w(PDF_IMG_TAG, "Lote ${lote.id} idx=$idx no candidate path")
                canvas.drawText("Image not available.", imgRect.left, imgTop + 24f, bodyP)
            }

            /* ---- 3. Histogram on the RIGHT ---- */
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
                canvas.drawText("No valid grapes detected.", errX, errY, bodyP)
            } else {
                // Error message centered where the histogram would go
                val errX = imgRect.right + colGap
                val errY = imgTop + 24f
                canvas.drawText("Error: ${cp.error}", errX, errY, bodyP)
            }

            /* ---- 4. Stats BELOW the full row ---- */
            val statsLeft = rowLeft + 20f
            val statsTop  = rowTop + colWImg + 34f
            bodyP.isFakeBoldText = true
            canvas.drawText("#${idx + 1}", statsLeft, statsTop, bodyP)
            bodyP.isFakeBoldText = false
            var sY = statsTop + 14f
            if (isInvalidFused) {
                // Message already shown in the histogram area; stats area stays clean
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
                canvas.drawText("Front/Back Result", statsLeft, sY, bodyP)
            }

            /* advance cursor Y to the next row */
            cy += rowH + 16f
        }
        doc.finishPage(page)
    }

    /* file with timestamp */
    val ts = android.text.format.DateFormat.format("yyyyMMdd_HHmmss", Date())
    val out = File(context.cacheDir, "report_$ts.pdf")
    return try {
        out.outputStream().use { doc.writeTo(it) }; out
    } catch (e: Exception) {
        null
    } finally { doc.close() }
}
