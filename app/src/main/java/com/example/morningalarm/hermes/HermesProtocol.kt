package com.example.morningalarm.hermes

import com.example.morningalarm.data.Alarm

/**
 * Wire protocol for the explicit, DUMP-permission-protected Hermes receiver.
 *
 * ADB example:
 *   adb shell am broadcast -n com.example.morningalarm/.hermes.HermesCommandReceiver \
 *     -a com.example.morningalarm.hermes.action.CREATE_ALARM \
 *     --ei hour 7 --ei minute 30 --es label "Wake up"
 *
 * Results are returned as ordered-broadcast result data/extras (JSON) and are also
 * written to logcat under the tag "HermesCommand". No command returns file contents
 * or accepts arbitrary file paths.
 */
object HermesProtocol {
    const val PROTOCOL_VERSION = 1
    const val RESULT_JSON_EXTRA = "hermes_result_json"

    object Action {
        const val PING = "com.example.morningalarm.hermes.action.PING"
        const val LIST_ALARMS = "com.example.morningalarm.hermes.action.LIST_ALARMS"
        const val CREATE_ALARM = "com.example.morningalarm.hermes.action.CREATE_ALARM"
        const val SET_ENABLED = "com.example.morningalarm.hermes.action.SET_ENABLED"
        const val DELETE_ALARM = "com.example.morningalarm.hermes.action.DELETE_ALARM"
    }

    object Extra {
        const val HOUR = "hour"
        const val MINUTE = "minute"
        const val LABEL = "label"
        const val ID = "id"
        const val ENABLED = "enabled"
    }

    data class Response(
        val ok: Boolean,
        val message: String,
        val data: Any? = null,
    )

    data class CreateRequest(
        val hour: Int,
        val minute: Int,
        val label: String,
    )

    object Args {
        fun create(hour: Int?, minute: Int?, label: String?): Result<CreateRequest> {
            if (hour == null || hour !in 0..23) {
                return Result.failure(IllegalArgumentException("hour must be 0..23"))
            }
            if (minute == null || minute !in 0..59) {
                return Result.failure(IllegalArgumentException("minute must be 0..59"))
            }
            return Result.success(
                CreateRequest(
                    hour = hour,
                    minute = minute,
                    label = Alarm.normalizeLabel(label.orEmpty()),
                ),
            )
        }

        fun alarmId(id: Long?): Result<Long> {
            if (id == null || id <= 0L) {
                return Result.failure(IllegalArgumentException("id must be a positive long"))
            }
            return Result.success(id)
        }

        /**
         * Strictly validates the enabled flag. Bundle#getBooleanExtra silently
         * coerces mismatched types, so the receiver passes the raw value here.
         */
        fun enabled(value: Any?): Result<Boolean> {
            if (value !is Boolean) {
                return Result.failure(
                    IllegalArgumentException("enabled must be a boolean (true/false)"),
                )
            }
            return Result.success(value)
        }
    }

    fun alarmToMap(alarm: Alarm): Map<String, Any> = linkedMapOf(
        "id" to alarm.id,
        "hour" to alarm.hour,
        "minute" to alarm.minute,
        "label" to alarm.label,
        "enabled" to alarm.enabled,
    )
}
