package com.gaiaspa.metrics_detection.ui.home

import com.gaiaspa.metrics_detection.data.model.CalPredict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RacimoFusionMapperTest {

    @Test
    fun legacyPayload_flagOff_keepsOneImageOnePrediction() {
        val payload = RacimoFusionMapper.buildLegacySavePayload(
            listOf(input("one", prediction(qty = 12)))
        )

        assertEquals(1, payload.sourceImages.size)
        assertEquals(1, payload.uploadImages.size)
        assertEquals(1, payload.overlayImages.size)
        assertEquals(1, payload.calPredicts.size)
        assertEquals(12, payload.calPredicts.first().qty)
    }

    @Test
    fun onePhoto_flagOn_buildsIncompleteRacimoAndBlocksSave() {
        val models = RacimoFusionMapper.buildUiModels(
            listOf(input("a", prediction(qty = 10)))
        )

        assertEquals(1, models.size)
        assertEquals(1, models.first().racimoIndex)
        assertEquals(false, models.first().isComplete)
        assertNull(models.first().fusedPrediction)

        try {
            RacimoFusionMapper.buildFusedSavePayload(listOf(input("a", prediction(qty = 10))))
            throw AssertionError("Expected incomplete racimo to fail")
        } catch (e: IllegalArgumentException) {
            assertEquals("Each bunch must have both Front and Back", e.message)
        }
    }

    @Test
    fun twoPhotos_flagOn_buildsOneFusedRacimo() {
        val models = RacimoFusionMapper.buildUiModels(
            listOf(input("a", prediction(qty = 10)), input("b", prediction(qty = 14)))
        )

        assertEquals(1, models.size)
        assertTrue(models.first().isComplete)
        assertNotNull(models.first().fusedPrediction)
        assertEquals(12, models.first().fusedPrediction?.qty)
    }

    @Test
    fun fourPhotos_flagOn_buildsTwoFusedRacimos() {
        val models = RacimoFusionMapper.buildUiModels(
            listOf(
                input("a1", prediction(qty = 10)),
                input("b1", prediction(qty = 14)),
                input("a2", prediction(qty = 40)),
                input("b2", prediction(qty = 48))
            )
        )

        assertEquals(2, models.size)
        assertEquals(listOf(12, 44), models.map { it.fusedPrediction?.qty })
    }

    @Test
    fun selectedImageRole_usesPredictionClosestToFusedQty() {
        assertEquals("A", RacimoFusionMapper.chooseRepresentativeRole(qtyA = 10, qtyB = 14, qtyFinal = 12))
        assertEquals("B", RacimoFusionMapper.chooseRepresentativeRole(qtyA = 10, qtyB = 15, qtyFinal = 13))
    }

    @Test
    fun fusedSavePayload_keepsBackendOneToOneAndOnlyFusedPredictions() {
        val payload = RacimoFusionMapper.buildFusedSavePayload(
            listOf(
                input("a1", prediction(qty = 10)),
                input("b1", prediction(qty = 14)),
                input("a2", prediction(qty = 40)),
                input("b2", prediction(qty = 48))
            )
        )

        assertEquals(2, payload.sourceImages.size)
        assertEquals(2, payload.uploadImages.size)
        assertEquals(2, payload.overlayImages.size)
        assertEquals(2, payload.calPredicts.size)
        assertEquals(listOf(12, 44), payload.calPredicts.map { it.qty })
    }

    @Test
    fun fusedMetadata_preservesAbQuantitiesDisagreementAndPaths() {
        val payload = RacimoFusionMapper.buildFusedSavePayload(
            listOf(input("a", prediction(qty = 10)), input("b", prediction(qty = 14)))
        )

        val group = payload.calPredicts.first().fusionMetadata?.groups?.first()
        assertNotNull(group)
        assertEquals(10, group?.qtyA)
        assertEquals(14, group?.qtyB)
        assertEquals(12, group?.qtyFinal)
        assertEquals(4f / 12f, group?.disagreement ?: 0f, 0.001f)
        assertEquals("A", group?.selectedImageRole)
        assertEquals("/tmp/a_normalized.jpg", group?.viewASourcePath)
        assertEquals("/tmp/b_upload.jpg", group?.viewBUploadPath)
        assertEquals("/tmp/a_overlay.jpg", payload.overlayImages.first())
    }

    @Test
    fun corruptOrUnavailablePrediction_doesNotCreateFusedCard() {
        val models = RacimoFusionMapper.buildUiModels(
            listOf(
                input("a", CalPredict(status = false, error = "bad")),
                input("b", prediction(qty = 14))
            )
        )

        assertEquals(1, models.size)
        assertTrue(models.first().isComplete)
        assertNull(models.first().fusedPrediction)
    }

    @Test
    fun erroredState_neverTreatsExistingFusedAsDisplayable() {
        val model = RacimoUiModel(
            racimoIndex = 1,
            isComplete = true,
            hasAnyPhoto = true,
            imageAPath = "/tmp/a.jpg",
            imageBPath = "/tmp/b.jpg",
            overlayAPath = null,
            overlayBPath = null,
            predA = CalPredict(status = false, error = "bad"),
            predB = prediction(qty = 14),
            fusedPrediction = prediction(qty = 12),
            fusionWarning = null,
            selectedImageRole = "A",
            selectedImagePath = "/tmp/a.jpg",
            state = RacimoUiModel.State.FRONT_ERROR,
            imageAState = RacimoUiModel.ImageState.FAILED,
            imageBState = RacimoUiModel.ImageState.VALID
        )

        assertFalse(model.hasValidFusedPrediction)
        assertFalse(model.isProcessed)
    }

    @Test
    fun invalidRacimo_hasReviewStateAndCannotBeSaved() {
        val models = RacimoFusionMapper.buildUiModels(
            listOf(input("a", prediction(qty = 0)), input("b", prediction(qty = 0)))
        )

        assertEquals(1, models.size)
        assertTrue(models.first().isComplete)
        assertEquals(0, models.first().fusedPrediction?.qty)
        assertNull(models.first().selectedImageRole)
        assertNull(models.first().selectedImagePath)

        try {
            RacimoFusionMapper.buildFusedSavePayload(
                listOf(input("a", prediction(qty = 0)), input("b", prediction(qty = 0)))
            )
            throw AssertionError("Expected invalid racimo to fail")
        } catch (e: IllegalArgumentException) {
            assertEquals("Bunch 1 has no valid detection", e.message)
        }
    }

    @Test
    fun userFacingUiSources_doNotRenderDisagreementOrConsistency() {
        val adapter = projectFile("src/main/java/com/gaiaspa/metrics_detection/ui/home/ImagePredictionAdapter.kt").readText()
        val layout = projectFile("src/main/res/layout/item_image_prediction.xml").readText()
        val strings = projectFile("src/main/res/values/strings.xml").readText()
        val stringsEn = projectFile("src/main/res/values-en/strings.xml").readText()

        assertFalse(adapter.contains("Disagreement"))
        assertFalse(adapter.contains("disagreementUi"))
        assertFalse(adapter.contains("Consistencia"))
        assertFalse(layout.contains("Consistencia"))
        assertTrue(adapter.contains("R.string.no_valid_grapes"))
        assertTrue(strings.contains("Review capture"))
        assertTrue(stringsEn.contains("Review capture"))
        assertTrue(strings.contains("No valid bunch detected."))
        assertTrue(stringsEn.contains("No valid bunch was detected."))
        assertFalse(adapter.contains("QTY Final: 0"))
    }

    @Test
    fun pdfSource_doesNotRenderDisagreement() {
        val pdf = projectFile("src/main/java/com/gaiaspa/metrics_detection/pdf_utils/createPDF.kt").readText()

        assertFalse(pdf.contains("Disagreement"))
        assertFalse(pdf.contains("disagreementUi"))
        assertTrue(pdf.contains("Front/Back Result"))
        assertTrue(pdf.contains("No valid grapes detected."))
        assertTrue(pdf.contains("Same priority as Lote.images"))
        assertTrue(pdf.contains("group.viewBUploadPath"))
        assertTrue(pdf.contains("group.viewAUploadPath"))
        assertTrue(pdf.contains("overlayImages.getOrNull(index)"))
    }

    private fun input(name: String, predict: CalPredict): RacimoFusionMapper.ImageInput {
        return RacimoFusionMapper.ImageInput(
            sourcePath = "/tmp/${name}_source.jpg",
            normalizedPath = "/tmp/${name}_normalized.jpg",
            uploadPath = "/tmp/${name}_upload.jpg",
            overlayPath = "/tmp/${name}_overlay.jpg",
            prediction = predict
        )
    }

    private fun prediction(qty: Int): CalPredict {
        return CalPredict(
            status = true,
            bunchColor = "RED_GLOBE",
            qty = qty,
            mean = 16f,
            mode = 16f,
            std = 2f,
            pred = listOf(qty / 2, qty - qty / 2),
            bins = listOf(14f, 18f)
        )
    }

    private fun projectFile(pathFromApp: String): File {
        return listOf(
            File(pathFromApp),
            File("app/$pathFromApp")
        ).firstOrNull { it.exists() } ?: File(pathFromApp)
    }
}
