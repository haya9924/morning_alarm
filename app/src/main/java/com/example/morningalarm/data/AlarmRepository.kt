package com.example.morningalarm.data

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
 */
class AlarmRepository(
    private val storage: AlarmStorage,
    private val idGenerator: ((List<Alarm>) -> Long)? = null,
) {
    private val lock = Any()

    private val initial: List<Alarm> = storage.load()
        .filter { it.isValid }
        .distinctBy { it.id }
        .sortedWith(Alarm.ORDERING)

    private val idCounter = AtomicLong(initial.maxOfOrNull { it.id } ?: 0L)

    private val _alarms = MutableStateFlow(initial)
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    fun get(id: Long): Alarm? = synchronized(lock) {
        _alarms.value.firstOrNull { it.id == id }
    }

    fun add(hour: Int, minute: Int, label: String = ""): Alarm = synchronized(lock) {
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
        )
        persistLocked(current + alarm)
        alarm
    }

    fun setEnabled(id: Long, enabled: Boolean): Alarm? = synchronized(lock) {
        val current = _alarms.value
        val existing = current.firstOrNull { it.id == id } ?: return null
        val updated = existing.copy(enabled = enabled)
        persistLocked(current.map { if (it.id == id) updated else it })
        updated
    }

    fun delete(id: Long): Boolean = synchronized(lock) {
        val current = _alarms.value
        if (current.none { it.id == id }) return false
        persistLocked(current.filterNot { it.id == id })
        true
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
    }
}
