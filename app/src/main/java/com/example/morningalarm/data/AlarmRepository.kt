package com.example.morningalarm.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for alarms. Mutating calls persist synchronously and then
 * publish the new immutable list through [alarms].
 *
 * Scheduling is intentionally not done here; the UI and Hermes receiver call
 * [com.example.morningalarm.alarm.AlarmScheduler] so this class stays testable.
 *
 * One-shot adjustments ("skip next", "change next time only") are stored on the
 * alarm itself so they survive a reboot, and carry an explicit target date. They are
 * consumed by [consumeAfterFire] once they have been used. [now] is injectable for
 * deterministic tests.
 */
class AlarmRepository(
    private val storage: AlarmStorage,
    private val idGenerator: ((List<Alarm>) -> Long)? = null,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val onPersisted: () -> Unit = {},
) {
    private val lock = Any()

    private val initial: List<Alarm> = storage.load()
        .filter { it.isValid }
        .map { it.withNormalizedRepeatDays() }
        .map { it.withoutStaleAdjustments(now().toLocalDate()) }
        .distinctBy { it.id }
        .sortedWith(Alarm.ORDERING)

    private val idCounter = AtomicLong(initial.maxOfOrNull { it.id } ?: 0L)

    private val _alarms = MutableStateFlow(initial)
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    fun get(id: Long): Alarm? = synchronized(lock) {
        _alarms.value.firstOrNull { it.id == id }
    }

    fun add(
        hour: Int,
        minute: Int,
        label: String = "",
        repeatDays: Set<DayOfWeek> = RepeatDays.EVERY_DAY,
    ): Alarm = synchronized(lock) {
        require(hour in 0..23) { "hour must be 0..23" }
        require(minute in 0..59) { "minute must be 0..59" }

        val current = _alarms.value
        val id = newId(current)
        val alarm = Alarm(
            id = id,
            hour = hour,
            minute = minute,
            label = Alarm.normalizeLabel(label),
            enabled = true,
            repeatDays = RepeatDays.normalize(repeatDays),
        )
        persistLocked(current + alarm)
        alarm
    }

    fun setEnabled(id: Long, enabled: Boolean): Alarm? = mutate(id) { it.copy(enabled = enabled) }

    fun setLabel(id: Long, label: String): Alarm? =
        mutate(id) { it.copy(label = Alarm.normalizeLabel(label)) }

    /**
     * Applies an edit of the time, label and repeat days in one persisted change.
     * A changed time or repeat set invalidates any pending one-shot adjustment,
     * because its explicit target date belongs to the previous schedule.
     */
    fun update(
        id: Long,
        hour: Int,
        minute: Int,
        label: String,
        repeatDays: Set<DayOfWeek>,
    ): Alarm? {
        require(hour in 0..23) { "hour must be 0..23" }
        require(minute in 0..59) { "minute must be 0..59" }
        val normalizedDays = RepeatDays.normalize(repeatDays)
        return mutate(id) { alarm ->
            val rescheduled = hour != alarm.hour ||
                minute != alarm.minute ||
                normalizedDays != alarm.repeat
            alarm.copy(
                hour = hour,
                minute = minute,
                label = Alarm.normalizeLabel(label),
                repeatDays = normalizedDays,
            ).let { if (rescheduled) it.withClearedAdjustments() else it }
        }
    }

    /**
     * Replaces the repeat days. An empty set means "every day" (legacy behavior).
     * Any pending one-shot adjustment is cleared because its explicit target date
     * belongs to the old schedule.
     */
    fun setRepeatDays(id: Long, repeatDays: Set<DayOfWeek>): Alarm? = mutate(id) {
        val normalized = RepeatDays.normalize(repeatDays)
        if (normalized == it.repeat) {
            it
        } else {
            it.copy(repeatDays = normalized).withClearedAdjustments()
        }
    }

    /**
     * Skips the next upcoming occurrence only. The target date is derived from the
     * regular schedule (a stored skip is ignored) and stored explicitly, so a reboot
     * or reschedule keeps honoring exactly that date.
     *
     * Only one occurrence can be skipped at a time: a second call before the skipped
     * date fires recomputes the same date and is a no-op, instead of moving the skip
     * forward and silently un-skipping the first occurrence.
     */
    fun skipNext(id: Long): Alarm? = synchronized(lock) {
        val existing = _alarms.value.firstOrNull { it.id == id } ?: return null
        val regular = existing.withoutStaleAdjustments(now().toLocalDate())
            .copy(skipNextDate = null, nextTimeOverrideAt = null)
        val target = NextOccurrence.nextDateAfter(
            alarm = regular,
            afterDate = existing.lastFiredDate,
            now = now(),
        ) ?: return existing
        if (existing.skipNextDate == target) return existing
        persistReplacing(existing.id, existing.copy(skipNextDate = target))
    }

    fun clearSkipNext(id: Long): Alarm? = mutate(id) { it.copy(skipNextDate = null) }

    /**
     * Changes the time of the next occurrence only and stores the explicit target
     * date, so later reschedules cannot move it.
     *
     * @param time new wall-clock time, or null to clear the override.
     * @param date target date pinned by the caller (e.g. from Hermes). When null the
     *   next occurrence after any skip is used.
     */
    fun setNextTimeOverride(
        id: Long,
        time: LocalTime?,
        date: LocalDate? = null,
    ): Alarm? = synchronized(lock) {
        val existing = _alarms.value.firstOrNull { it.id == id } ?: return null
        if (time == null) {
            return mutate(id) { it.copy(nextTimeOverrideAt = null) }
        }

        val base = existing.withoutStaleAdjustments(now().toLocalDate())
            .copy(nextTimeOverrideAt = null)
        val allowedDate = date
            ?.takeIf { it != existing.lastFiredDate && it >= now().toLocalDate() }
            ?: NextOccurrence.nextDateAfter(
                alarm = base,
                afterDate = existing.lastFiredDate,
                now = now(),
            )
            ?: return existing
        val target = LocalDateTime.of(allowedDate, time)
        // An override that is already past for that date would never fire; move it
        // to the next matching date instead of storing a dead value.
        val resolved = if (target.atZone(now().zone).isAfter(now())) {
            target
        } else {
            val moved = NextOccurrence.nextDateAfter(
                alarm = base,
                afterDate = allowedDate,
                now = now(),
            ) ?: return existing
            LocalDateTime.of(moved, time)
        }
        return mutate(id) { it.copy(nextTimeOverrideAt = resolved) }
    }

    /**
     * Consumes one-shot adjustments after an occurrence fired and decides whether the
     * occurrence should actually ring.
     *
     * The fired date is recorded as [Alarm.lastFiredDate] so no second occurrence can
     * ring on the same date – including a duplicate broadcast delivery or a stale
     * regular-time PendingIntent left over from before the next time was moved
     * earlier. Adjustments that are no longer usable are dropped: a skip date that is
     * not in the future, and an override whose target date has been reached.
     *
     * The decision and the persisted mutation happen under the same lock, so two
     * deliveries of the same occurrence cannot both ring.
     */
    fun consumeAfterFire(
        id: Long,
        firedDate: LocalDate,
        usedTimeOverride: Boolean,
    ): FireConsumption? = synchronized(lock) {
        val existing = _alarms.value.firstOrNull { it.id == id } ?: return null
        val alreadyFired = existing.lastFiredDate?.let { !firedDate.isAfter(it) } == true
        if (alreadyFired) {
            // Duplicate delivery or a stale regular-time intent: keep the stored state
            // untouched and suppress the ring.
            return FireConsumption(alarm = existing, shouldRing = false)
        }
        val skip = existing.skipNextDate?.takeIf { it.isAfter(firedDate) }
        val override = existing.nextTimeOverrideAt
            ?.takeIf { it.toLocalDate().isAfter(firedDate) && !usedTimeOverride }
        val updated = existing.copy(
            skipNextDate = skip,
            nextTimeOverrideAt = override,
            lastFiredDate = maxOfNotNull(existing.lastFiredDate, firedDate),
        )
        val alarm = if (updated == existing) existing else persistReplacing(id, updated)
        FireConsumption(alarm = alarm, shouldRing = true)
    }

    /** Removes both one-shot adjustments (the next regular occurrence runs as configured). */
    fun clearNextAdjustment(id: Long): Alarm? = mutate(id) { it.withClearedAdjustments() }

    fun delete(id: Long): Boolean = synchronized(lock) {
        val current = _alarms.value
        if (current.none { it.id == id }) return false
        persistLocked(current.filterNot { it.id == id })
        true
    }

    private fun mutate(id: Long, transform: (Alarm) -> Alarm): Alarm? = synchronized(lock) {
        val existing = _alarms.value.firstOrNull { it.id == id } ?: return null
        val updated = transform(existing)
        if (updated == existing) return existing
        persistReplacing(id, updated)
    }

    private fun persistReplacing(id: Long, updated: Alarm): Alarm {
        persistLocked(_alarms.value.map { if (it.id == id) updated else it })
        return updated
    }

    private fun newId(current: List<Alarm>): Long {
        val candidate = idGenerator?.invoke(current) ?: idCounter.incrementAndGet()
        check(candidate > 0) { "generated alarm id must be positive" }
        check(current.none { it.id == candidate }) { "duplicate alarm id: $candidate" }
        return candidate
    }

    private fun persistLocked(alarms: List<Alarm>) {
        val ordered = alarms.sortedWith(Alarm.ORDERING)
        check(storage.save(ordered)) {
            "Failed to persist alarms; the change was not applied"
        }
        _alarms.value = ordered
        // A display refresh failure must never undo a successfully saved alarm.
        runCatching { onPersisted() }
    }

    /** Result of [consumeAfterFire]: the updated alarm and whether to ring now. */
    data class FireConsumption(
        val alarm: Alarm,
        val shouldRing: Boolean,
    )
}
