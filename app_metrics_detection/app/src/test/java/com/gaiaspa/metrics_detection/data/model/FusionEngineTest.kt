package com.gaiaspa.metrics_detection.data.model

import com.gaiaspa.metrics_detection.data.local.Converters
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionEngineTest {
    @Test
    fun fuse_withCompatibleHistograms_derivesFusedPrediction() {
        val a = CalPredict(
            status = true,
            bunchColor = "ALLISON",
            qty = 10,
            pred = listOf(2, 8),
            bins = listOf(10f, 20f),
            mean = 12f,
            std = 2f,
            mode = 10f
        )
        val b = CalPredict(
            status = true,
            bunchColor = "ALLISON",
            qty = 14,
            pred = listOf(6, 8),
            bins = listOf(10f, 20f),
            mean = 18f,
            std = 4f,
            mode = 20f
        )

        val result = FusionEngine.fuse(a, b)

        assertEquals(12, result.fused.qty)
        assertEquals(listOf(4, 8), result.fused.pred)
        assertEquals(12, result.fused.pred.sum())
        assertEquals(15f, result.fused.mean, 0.001f)
        assertEquals(15f, result.fused.mode, 0.001f)
        assertEquals(3f, result.fused.std, 0.001f)
        assertEquals(4f / 12f, result.disagreement, 0.001f)
    }

    @Test
    fun fuse_withMissingHistogram_keepsQtyAndReturnsWarning() {
        val a = CalPredict(status = true, qty = 6, mean = 12f, std = 2f, mode = 10f)
        val b = CalPredict(status = true, qty = 10, mean = 16f, std = 4f, mode = 20f)

        val result = FusionEngine.fuse(a, b)

        assertEquals(8, result.fused.qty)
        assertTrue(result.fused.pred.isEmpty())
        assertEquals(15f, result.fused.mode, 0.001f)
        assertTrue(result.warning?.contains("Histograma") == true)
    }

    @Test
    fun fusePairwise_withTwoPredictions_generatesOneFusedPrediction() {
        val imagePaths = readOnlySampleImages(2)
        assertEquals(2, imagePaths.size)

        val fused = FusionEngine.fusePairwise(samplePredictions().take(2)).map { it.prediction }

        assertEquals(1, fused.size)
        val group = fused.first().fusionMetadata?.groups?.first()
        assertNotNull(group)
        assertEquals(1, group?.racimoIndex)
        assertEquals(0, group?.viewAImageIndex)
        assertEquals(1, group?.viewBImageIndex)
        assertEquals(0, group?.fusedPredictionIndex)
    }

    @Test
    fun fusePairwise_withFourPredictions_serializesExpectedFusionMetadataJson() {
        val imagePaths = readOnlySampleImages(4)
        assertEquals(4, imagePaths.size)

        val fused = FusionEngine.fusePairwise(samplePredictions()).map { it.prediction }

        assertEquals(2, fused.size)
        fused.forEach { prediction ->
            assertNotNull(prediction.fusionMetadata)
            assertEquals("multiview_v1", prediction.fusionMetadata?.fusionVersion)
            assertEquals("pairwise_chronological", prediction.fusionMetadata?.groupingRule)
            assertEquals(2, prediction.fusionMetadata?.groups?.size)
        }

        val json = Converters().fromCalPredictList(fused)
        File("/tmp/multiview_calpredicts.json").writeText(json)

        val root = JsonParser.parseString(json).asJsonArray
        assertEquals(2, root.size())
        val groups = root[0].asJsonObject["fusionMetadata"].asJsonObject["groups"].asJsonArray
        assertEquals(2, groups.size())

        val group1 = groups[0].asJsonObject
        assertEquals(1, group1["racimoIndex"].asInt)
        assertEquals(0, group1["viewAImageIndex"].asInt)
        assertEquals(1, group1["viewBImageIndex"].asInt)
        assertEquals(0, group1["fusedPredictionIndex"].asInt)

        val group2 = groups[1].asJsonObject
        assertEquals(2, group2["racimoIndex"].asInt)
        assertEquals(2, group2["viewAImageIndex"].asInt)
        assertEquals(3, group2["viewBImageIndex"].asInt)
        assertEquals(1, group2["fusedPredictionIndex"].asInt)
    }

    @Test
    fun fusePairwise_withOddPredictions_blocksSaveRule() {
        try {
            FusionEngine.fusePairwise(samplePredictions().take(3))
            throw AssertionError("Expected odd image count to fail")
        } catch (e: IllegalArgumentException) {
            assertEquals("Cada racimo debe tener Foto A y Foto B", e.message)
        }
    }

    private fun samplePredictions(): List<CalPredict> = listOf(
        CalPredict(status = true, bunchColor = "RED_GLOBE", qty = 60, mean = 16f, mode = 16f, std = 2f, pred = listOf(10, 30, 20), bins = listOf(14f, 16f, 18f)),
        CalPredict(status = true, bunchColor = "RED_GLOBE", qty = 64, mean = 17f, mode = 18f, std = 2.5f, pred = listOf(8, 28, 28), bins = listOf(14f, 16f, 18f)),
        CalPredict(status = true, bunchColor = "RED_GLOBE", qty = 42, mean = 15f, mode = 14f, std = 1.5f, pred = listOf(18, 16, 8), bins = listOf(14f, 16f, 18f)),
        CalPredict(status = true, bunchColor = "RED_GLOBE", qty = 46, mean = 15.5f, mode = 16f, std = 1.8f, pred = listOf(14, 20, 12), bins = listOf(14f, 16f, 18f))
    )

    private fun readOnlySampleImages(count: Int): List<File> {
        val root = File("/home/maxi/Escritorio/modelo_nuevo/optimizacion_uvas/imagenesparatest")
        assertTrue("Sample image folder must exist: ${root.absolutePath}", root.exists())
        return root.walkTopDown()
            .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
            .take(count)
            .toList()
            .also { assertEquals(count, it.size) }
    }
}
