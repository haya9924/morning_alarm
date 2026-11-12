package com.example.morningalarm.data

/**
 * A daily alarm at a fixed local time.
 *
 * IDs are positive and stable for the lifetime of the alarm. They are used as the
 * PendingIntent request code basis and as the key for Hermes commands.
 */
data class Alarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val enabled: Boolean = true,
) {
    val isValid: Boolean
        get() = id > 0 && hour in 0..23 && minute in 0..59

    companion object {
        const val MAX_LABEL_LENGTH = 80

        fun normalizeLabel(value: String): String =
            value.trim().take(MAX_LABEL_LENGTH)

        val ORDERING: Comparator<Alarm> =
            compareBy<Alarm>({ it.hour }, { it.minute }, { it.id })
    }
}
