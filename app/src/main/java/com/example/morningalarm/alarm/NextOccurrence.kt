package com.example.morningalarm.alarm

import java.time.ZonedDateTime

/**
 * Computes the next strictly-future occurrence of a local wall-clock time.
 *
 * DST gaps are handled by java.time (the time is shifted forward), and repeated
 * hours resolve to the earlier offset. Alarms are daily, so no weekday logic lives
 * here yet.
 */
object NextOccurrence {
    fun after(hour: Int, minute: Int, now: ZonedDateTime): ZonedDateTime {
        require(hour in 0..23) { "hour must be 0..23" }
        require(minute in 0..59) { "minute must be 0..59" }

        val zone = now.zone
        var candidate = now.toLocalDate()
            .atTime(hour, minute)
            .atZone(zone)

        if (!candidate.isAfter(now)) {
            candidate = now.toLocalDate()
                .plusDays(1)
                .atTime(hour, minute)
                .atZone(zone)
        }
        return candidate
    }
}
