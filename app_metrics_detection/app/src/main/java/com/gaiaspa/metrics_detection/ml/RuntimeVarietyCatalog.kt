package com.gaiaspa.metrics_detection.ml

import java.util.Locale

data class RuntimeVariety(val id: Int, val name: String)

object RuntimeVarietyCatalog {

    // Mantener este orden alineado con los IDs de variedad que recibe el modelo.
    private val CANONICAL_VARIETIES = listOf(
        "ALLISON",
        "AUTUMN CRISP",
        "CRIMSON",
        "IVORY",
        "MAGENTA",
        "RED GLOBE",
        "SCARLOTTA",
        "SUPERIOR",
        "SWEET GLOBE",
        "THOMPSON",
        "TIMCO",
        "TIMPSON"
    )

    private val ALIASES_TO_CANONICAL = mapOf(
        "AUTUM CRISP" to "AUTUMN CRISP",
        "RED GLOVE" to "RED GLOBE",
        "TINCO" to "TIMCO"
    )

    private val ID_BY_NAME: Map<String, Int> =
        CANONICAL_VARIETIES.withIndex().associate { (idx, name) -> name to idx }

    fun entries(): List<RuntimeVariety> =
        CANONICAL_VARIETIES.mapIndexed { index, name -> RuntimeVariety(index, name) }

    fun nameOrNull(id: Int): String? = CANONICAL_VARIETIES.getOrNull(id)

    fun idOrNull(raw: String?): Int? {
        val canonical = normalize(raw) ?: return null
        return ID_BY_NAME[canonical]
    }

    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        val normalized = raw
            .replace('_', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")
            .uppercase(Locale.US)

        return ALIASES_TO_CANONICAL[normalized] ?: normalized
    }

    fun toUiName(canonical: String): String {
        return canonical.lowercase(Locale.US)
            .split(" ")
            .joinToString(" ") { token ->
                token.replaceFirstChar { c -> c.titlecase(Locale.US) }
            }
    }
}
