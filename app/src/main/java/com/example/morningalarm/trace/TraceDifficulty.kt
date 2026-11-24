package com.example.morningalarm.trace

/**
 * Trace difficulty.
 *
 * [storageKey] is the stable identifier persisted in settings; [tolerance] is the
 * maximum distance from the reference path for an input point to count.
 *
 * Besides the [EASY] and [LEGACY] presets any tolerance between [MIN_TOLERANCE] and
 * [MAX_TOLERANCE] can be used, created through [fromTolerance]. Instances are
 * immutable value objects: two difficulties are equal when their [tolerance]
 * matches, so a custom value is interchangeable with an identical preset.
 */
class TraceDifficulty private constructor(
    val storageKey: String,
    val tolerance: Float,
) {
    override fun equals(other: Any?): Boolean =
        other is TraceDifficulty && other.tolerance == tolerance

    override fun hashCode(): Int = tolerance.hashCode()

    override fun toString(): String =
        "TraceDifficulty(storageKey=$storageKey, tolerance=$tolerance)"

    companion object {
        /** Smallest custom tolerance still considered valid. */
        const val MIN_TOLERANCE: Float = 0.030f

        /** Largest custom tolerance still considered valid. */
        const val MAX_TOLERANCE: Float = 0.065f

        /** Prefix used by the storage key of a non-preset difficulty. */
        private const val TOLERANCE_PREFIX = "tolerance:"

        /** Relaxed tracing for the default experience. */
        val EASY: TraceDifficulty = TraceDifficulty("easy", 0.055f)

        /** Original behaviour, kept selectable and for restoring old settings. */
        val LEGACY: TraceDifficulty = TraceDifficulty("legacy", 0.045f)

        /** Preset used when no explicit difficulty is supplied. */
        val DEFAULT: TraceDifficulty = EASY

        /** Selectable presets; custom tolerances are not listed here. */
        val entries: List<TraceDifficulty> = listOf(EASY, LEGACY)

        /** Maps a persisted [storageKey] to a difficulty, falling back to [DEFAULT]. */
        fun fromStorageKey(key: String?): TraceDifficulty {
            if (key == null) return DEFAULT
            entries.firstOrNull { it.storageKey == key }?.let { return it }
            return customFromStorageKey(key) ?: DEFAULT
        }

        /**
         * Returns the preset matching [tolerance] exactly, otherwise a custom
         * difficulty carrying [tolerance].
         *
         * @throws IllegalArgumentException when [tolerance] is not finite or lies
         * outside [MIN_TOLERANCE]..[MAX_TOLERANCE].
         */
        fun fromTolerance(tolerance: Float): TraceDifficulty {
            require(tolerance.isFinite()) { "tolerance must be finite: $tolerance" }
            require(tolerance in MIN_TOLERANCE..MAX_TOLERANCE) {
                "tolerance must be between $MIN_TOLERANCE and $MAX_TOLERANCE: $tolerance"
            }
            entries.firstOrNull { it.tolerance == tolerance }?.let { return it }
            return TraceDifficulty("$TOLERANCE_PREFIX$tolerance", tolerance)
        }

        private fun customFromStorageKey(key: String): TraceDifficulty? {
            if (!key.startsWith(TOLERANCE_PREFIX)) return null
            val parsed = key.removePrefix(TOLERANCE_PREFIX).toFloatOrNull() ?: return null
            if (!parsed.isFinite()) return null
            if (parsed < MIN_TOLERANCE || parsed > MAX_TOLERANCE) return null
            return fromTolerance(parsed)
        }
    }
}
