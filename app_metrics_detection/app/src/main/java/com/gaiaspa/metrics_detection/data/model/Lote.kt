package com.gaiaspa.metrics_detection.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

/**
 * Entidad Room que representa un lote de análisis de calibre de uvas.
 *
 * Almacena tanto las imágenes en sus distintas etapas del pipeline (fuente,
 * normalizada, subida, overlay) como los resultados de predicción ([CalPredict])
 * y las banderas de sincronización con el backend.
 *
 * ## Ciclo de vida de imágenes
 * 1. `sourceImages` — captura cruda del dispositivo.
 * 2. `normalizedImages` — preprocesadas para inferencia.
 * 3. `uploadImages` — versiones limpias subidas al backend.
 * 4. `overlayImages` — overlay visual generado por C++ con óvalos de detección.
 * 5. `cloudImages` — URLs/imágenes devueltas por el backend tras sincronización.
 *
 * ## Propiedad dinámica `images`
 * La propiedad computada [images] resuelve en cascada qué conjunto mostrar
 * en la UI: prioriza `overlayImages` > `uploadImages` > `normalizedImages` >
 * `sourceImages`. De esta forma el usuario siempre ve las detecciones en el
 * historial incluso si el overlay aún no se ha generado.
 *
 * @property id Clave primaria autogenerada localmente.
 * @property userId Identificador del usuario propietario del lote.
 * @property cloudId Identificador asignado por el backend tras sincronización exitosa.
 * @property company Compañía a la que pertenece el lote.
 * @property vessel Nave/embarcación asociada al lote.
 * @property block Bloque de producción dentro de la nave.
 * @property varietyId Identificador canónico de la variedad según [RuntimeVarietyCatalog].
 * @property varietyName Nombre de la variedad para presentación en UI.
 * @property predictedAt Timestamp de la predicción (epoch millis).
 * @property createdAt Timestamp de creación local (epoch millis).
 * @property updatedAt Timestamp de última modificación local (epoch millis).
 * @property sourceImages Lista de rutas de imágenes fuente capturadas.
 * @property normalizedImages Lista de rutas de imágenes normalizadas para ML.
 * @property uploadImages Lista de rutas de imágenes limpias listas para subir al backend.
 * @property overlayImages Lista de rutas de imágenes con overlay de detección C++.
 * @property cloudImages Lista de rutas/URLs de imágenes provenientes del backend.
 * @property calPredicts Lista de predicciones de calibre asociadas al lote.
 * @property toDelete Bandera de eliminación lógica pendiente de sincronizar.
 * @property toUpdate Bandera de actualización pendiente de sincronizar.
 * @property synced `true` si el lote fue sincronizado exitosamente con el backend.
 * @property syncError Mensaje del último error de sincronización, o `null` si fue exitosa.
 */
@Entity(tableName = "lote")
data class Lote(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val userId: String,
    val cloudId: String = "",
    val company: String,
    val vessel: String,
    val block: String,

    val varietyId: Int = -1,
    val varietyName: String = "",

    val predictedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "source_images")
    val sourceImages: List<String> = emptyList(),

    @ColumnInfo(name = "normalized_images") 
    val normalizedImages: List<String> = emptyList(),

    @ColumnInfo(name = "upload_images") 
    val uploadImages: List<String> = emptyList(), 

    @ColumnInfo(name = "overlay_images")
    val overlayImages: List<String> = emptyList(),

    @ColumnInfo(name = "cloudImages")
    val cloudImages: List<String> = emptyList(),

    @ColumnInfo(name = "cal_predicts")
    val calPredicts: List<CalPredict> = emptyList(),

    @ColumnInfo(name = "toDelete")
    val toDelete: Boolean = false,

    @ColumnInfo(name = "toUpdate")
    val toUpdate: Boolean = false,

    @ColumnInfo(name = "synced")
    val synced: Boolean = false,

    @ColumnInfo(name = "syncError")
    val syncError: String? = null
) {
    /**
     * Propiedad dinámica para UI.
     * ✅ FIXED: Prioriza overlayImages (res_ con óvalos nativos)
     * para que el usuario siempre vea las detecciones en el historial.
     *
     * La resolución en cascada garantiza que siempre se devuelva un conjunto
     * de imágenes, descendiendo desde la más informativa (overlay) hasta la
     * cruda de captura.
     */
    @get:Ignore
    val images: List<String>
        get() = overlayImages.ifEmpty { uploadImages.ifEmpty { normalizedImages.ifEmpty { sourceImages } } }
}
