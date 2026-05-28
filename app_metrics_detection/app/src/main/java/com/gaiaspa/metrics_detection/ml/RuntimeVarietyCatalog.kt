package com.gaiaspa.metrics_detection.ml

import java.util.Locale

/**
 * Represents a recognised grape variety with its canonical name and zero-based
 * numeric ID. The ID corresponds to the index within [RuntimeVarietyCatalog.CANONICAL_VARIETIES]
 * and is used as input to the native ML pipeline for variety-specific inference.
 */
data class RuntimeVariety(val id: Int, val name: String)

/**
 * Singleton catalogue that maps grape-variety strings to the numeric identifiers
 * expected by the ML model.
 *
 * Responsibilities:
 * - Maintains the canonical list of supported varieties (ID order must match the
 *   model's training-time label encoding).
 * - Normalises user-provided strings (uppercase, underscore-to-space, whitespace
 *   collapse) and resolves known misspelling aliases.
 * - Provides lookups in both directions: name → ID and ID → name.
 * - Formats canonical names for display (title-case).
 *
 * The order and spelling of [CANONICAL_VARIETIES] is a hard contract with the
 * native inference engine; changing it without retraining will produce incorrect results.
 */
object RuntimeVarietyCatalog {

    // The order must match exactly the model IDs.
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

    /**
     * Maps common misspellings and abbreviations to their canonical form.
     * Used by [normalize] before ID lookup to increase robustness.
     */
    private val ALIASES_TO_CANONICAL = mapOf(
        "AUTUM CRISP" to "AUTUMN CRISP",
        "RED GLOVE" to "RED GLOBE",
        "TINCO" to "TIMCO"
    )

    /** Lazily-computed reverse index: canonical name → zero-based numeric ID. */
    private val ID_BY_NAME: Map<String, Int> =
        CANONICAL_VARIETIES.withIndex().associate { (idx, name) -> name to idx }

    /**
     * Returns the full list of recognised varieties as [RuntimeVariety] entries,
     * ordered by their numeric ID.
     */
    fun entries(): List<RuntimeVariety> =
        CANONICAL_VARIETIES.mapIndexed { index, name -> RuntimeVariety(index, name) }

    /**
     * Looks up the canonical variety name for the given numeric ID.
     *
     * @param id Zero-based index into [CANONICAL_VARIETIES].
     * @return The canonical name or null if the ID is out of range.
     */
    fun nameOrNull(id: Int): String? = CANONICAL_VARIETIES.getOrNull(id)

    /**
     * Resolves a raw variety string to its numeric model ID.
     *
     * Normalises the input via [normalize] and then looks up the canonical name.
     *
     * @param raw User-provided or file-path-derived variety string (may be null/blank).
     * @return The zero-based variety ID or null if the input cannot be resolved.
     */
    fun idOrNull(raw: String?): Int? {
        val canonical = normalize(raw) ?: return null
        return ID_BY_NAME[canonical]
    }

    /**
     * Normalises a raw variety string for lookup.
     *
     * Steps:
     * 1. Replaces underscores with spaces.
     * 2. Collapses repeated whitespace.
     * 3. Converts to uppercase (US locale for deterministic behaviour).
     * 4. Resolves known aliases via [ALIASES_TO_CANONICAL].
     *
     * @param raw Raw variety string (may be null or blank).
     * @return The canonical uppercase name, or null if the input is blank.
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        val normalized = raw
            .replace('_', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")
            .uppercase(Locale.US)

        return ALIASES_TO_CANONICAL[normalized] ?: normalized
    }

    /**
     * Converts a canonical uppercase variety name to title-case for UI display.
     *
     * Example: "AUTUMN CRISP" → "Autumn Crisp".
     *
     * @param canonical A canonical name from [CANONICAL_VARIETIES].
     * @return A human-readable title-case string.
     */
    fun toUiName(canonical: String): String {
        return canonical.lowercase(Locale.US)
            .split(" ")
            .joinToString(" ") { token ->
                token.replaceFirstChar { c -> c.titlecase(Locale.US) }
            }
    }
}
