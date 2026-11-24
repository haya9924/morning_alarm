package com.example.morningalarm.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.morningalarm.MorningAlarmApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.morningalarm.trace.Point
import com.example.morningalarm.trace.TraceValidator
import java.util.UUID

/**
 * Owns alarm playback independently of any Activity.
 *
 * The service is started by [AlarmReceiver] when an exact alarm fires, so the alarm
 * still rings when the app is in the background, the device is locked, or the ring
 * UI cannot be shown. While ringing it runs as a foreground service with an alarm
 * notification; the notification's stop action sends [ACTION_STOP] here instead of
 * merely opening the UI.
 *
 * The ring Activity is only a view/controller. It never owns the media lifecycle.
 */
class AlarmService : Service() {
    private val repository by lazy { (application as MorningAlarmApp).repository }
    private var player: AlarmPlayer? = null
    private var currentAlarmId: Long? = null
    private var sessionToken: String = ""

    override fun onCreate() {
        super.onCreate()
        AlarmNotifier.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopRinging(null)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
                if (alarmId <= 0L) {
                    Log.w(TAG, "Invalid alarm id=$alarmId")
                    // Do not tear down a different, currently ringing alarm for a
                    // malformed request. If nothing is active, release the FGS start.
                    if (currentAlarmId == null) stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (!startRinging(alarmId) && currentAlarmId == null) {
                    // startForegroundService() was used but no foreground state was
                    // entered. Stop now instead of hitting the FGS timeout.
                    stopSelf(startId)
                }
            }

            ACTION_STOP -> Unit // Legacy notification commands cannot bypass tracing.

            ACTION_SNOOZE -> {
                if (matchesSession(intent) && currentAlarmId != null) {
                    val pending = SnoozeScheduler.Pending(currentAlarmId!!, sessionToken,
                        System.currentTimeMillis() + SnoozeScheduler.DURATION_MS)
                    runCatching {
                        SnoozeScheduler.saveAndSchedule(this, pending)
                        _snoozedUntil.value = pending.dueAt
                        val snoozedAlarm = repository.get(currentAlarmId!!)
                        stopRinging(currentAlarmId)
                        snoozedAlarm?.let { AlarmNotifier.showSnoozed(this, it) }
                        stopSelf(startId)
                    }.onFailure {
                        Log.e(TAG, "Snooze failed", it)
                        android.widget.Toast.makeText(this, it.message ?: "スヌーズできませんでした", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }

            ACTION_RESUME -> {
                val pending = SnoozeScheduler.read(this)
                if (pending != null && intent.getStringExtra(EXTRA_TOKEN) == pending.token &&
                    System.currentTimeMillis() >= pending.dueAt) {
                    if (startRinging(pending.alarmId)) {
                        SnoozeScheduler.clear(this)
                        _snoozedUntil.value = null
                    } else if (currentAlarmId == null) stopSelf(startId)
                } else if (currentAlarmId == null) stopSelf(startId)
            }

            ACTION_TRACE_DISMISS -> {
                val coordinates = intent.getFloatArrayExtra(EXTRA_POINTS)
                val pending = SnoozeScheduler.read(this)
                val pendingMatches = currentAlarmId == null && pending != null &&
                    pending.alarmId == intent.getLongExtra(EXTRA_ALARM_ID, -1L) &&
                    pending.token == intent.getStringExtra(EXTRA_TOKEN)
                if ((matchesSession(intent) || pendingMatches) && coordinates != null && coordinates.size % 2 == 0 &&
                    coordinates.size <= TraceValidator.MAX_INPUT_POINTS * 2) {
                    val points = coordinates.toList().chunked(2).map { Point(it[0], it[1]) }
                    if (TraceValidator.validate(points, (application as MorningAlarmApp).traceSettings.difficulty.value).success) {
                        val id = currentAlarmId ?: pending?.alarmId
                        val dismissedSession = if (pendingMatches) pending!!.token else sessionToken
                        SnoozeScheduler.clear(this)
                        _snoozedUntil.value = null
                        _dismissedToken.value = dismissedSession
                        stopRinging(id)
                        stopSelf(startId)
                    }
                }
            }

            else -> {
                Log.w(TAG, "Unknown action=${intent.action}")
                if (currentAlarmId == null) stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (currentAlarmId != null) stopRinging(null)
        super.onDestroy()
    }

    /** @return true when the alarm is now ringing (or was already ringing). */
    private fun startRinging(alarmId: Long): Boolean {
        if (currentAlarmId == alarmId && player != null) {
            // Already ringing this alarm; do not restart playback or re-post.
            return true
        }

        val alarm = repository.get(alarmId)
        if (alarm == null || !alarm.enabled) {
            Log.w(TAG, "Alarm id=$alarmId is missing or disabled")
            when (currentAlarmId) {
                null -> stopRinging(null)
                alarmId -> stopRinging(alarmId)
                // A stale start request must not interrupt a different ringing alarm.
                else -> Unit
            }
            return false
        }

        if (currentAlarmId != null && currentAlarmId != alarmId) {
            stopRinging(currentAlarmId)
        }

        SnoozeScheduler.clear(this)
        _snoozedUntil.value = null
        currentAlarmId = alarmId
        sessionToken = UUID.randomUUID().toString()
        _activeToken.value = sessionToken
        _ringingAlarmId.value = alarmId

        val notification = AlarmNotifier.buildRingingNotification(this, alarm)
        val notificationId = AlarmNotifier.notificationId(alarm.id)
        // Enter the foreground state before MediaPlayer.prepare() so a slow sound
        // source cannot exceed the startForegroundService() deadline.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(notificationId, notification)
        }

        player?.stop()
        player = AlarmPlayer(this).also { it.start() }
        Log.i(TAG, "Ringing alarm id=$alarmId")
        return true
    }

    /** @return false only when a stale stop request was ignored. */
    private fun stopRinging(requestedId: Long?): Boolean {
        val activeId = currentAlarmId
        if (activeId != null && requestedId != null && requestedId != activeId) {
            // A stale notification asked to stop a different alarm; keep ringing.
            return false
        }

        val stoppedId = activeId ?: requestedId
        player?.stop()
        player = null

        if (stoppedId != null) {
            AlarmNotifier.cancel(this, stoppedId)
        }
        if (activeId != null) {
            currentAlarmId = null
            _ringingAlarmId.value = null
        }

        stopForeground(STOP_FOREGROUND_REMOVE)

        if (stoppedId != null) {
            Log.i(TAG, "Stopped ringing alarm id=$stoppedId")
            sendBroadcast(
                Intent(ACTION_RINGING_STOPPED)
                    .setPackage(packageName)
                    .putExtra(EXTRA_ALARM_ID, stoppedId),
            )
        }
        return true
    }

    private fun matchesSession(intent: Intent): Boolean =
        currentAlarmId != null && currentAlarmId == intent.getLongExtra(EXTRA_ALARM_ID, -1L) &&
            sessionToken.isNotEmpty() && sessionToken == intent.getStringExtra(EXTRA_TOKEN)

    companion object {
        private const val TAG = "AlarmService"

        const val ACTION_SNOOZE = "com.example.morningalarm.action.SNOOZE"
        const val ACTION_RESUME = "com.example.morningalarm.action.RESUME_SNOOZE"
        const val ACTION_TRACE_DISMISS = "com.example.morningalarm.action.TRACE_DISMISS"
        const val EXTRA_TOKEN = "session_token"
        const val EXTRA_POINTS = "trace_points"
        private val _activeToken = MutableStateFlow("")
        val activeToken: StateFlow<String> = _activeToken.asStateFlow()
        private val _dismissedToken = MutableStateFlow("")
        val dismissedToken: StateFlow<String> = _dismissedToken.asStateFlow()
        private val _snoozedUntil = MutableStateFlow<Long?>(null)
        val snoozedUntil: StateFlow<Long?> = _snoozedUntil.asStateFlow()

        fun snooze(context: Context, id: Long, token: String) {
            context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_SNOOZE)
                .putExtra(EXTRA_ALARM_ID, id).putExtra(EXTRA_TOKEN, token))
        }
        fun dismissWithTrace(context: Context, id: Long, token: String, points: List<Point>) {
            context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_TRACE_DISMISS)
                .putExtra(EXTRA_ALARM_ID, id).putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_POINTS, points.flatMap { listOf(it.x, it.y) }.toFloatArray()))
        }
        fun resume(context: Context, pending: SnoozeScheduler.Pending) {
            ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java)
                .setAction(ACTION_RESUME).putExtra(EXTRA_TOKEN, pending.token))
        }

        const val ACTION_START = "com.example.morningalarm.action.START_RINGING"
        const val ACTION_STOP = "com.example.morningalarm.action.STOP_RINGING"
        const val ACTION_RINGING_STOPPED = "com.example.morningalarm.action.RINGING_STOPPED"
        const val EXTRA_ALARM_ID = "alarm_id"

        private val _ringingAlarmId = MutableStateFlow<Long?>(null)

        /** The alarm currently owned by the service, or null when silent. */
        val ringingAlarmId: StateFlow<Long?> = _ringingAlarmId.asStateFlow()

        fun start(context: Context, alarmId: Long) {
            val intent = Intent(context, AlarmService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ALARM_ID, alarmId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context, alarmId: Long) {
            val intent = Intent(context, AlarmService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_ALARM_ID, alarmId)
            // Starting a service only to stop it is allowed from a PendingIntent or
            // while the app is visible. It is a no-op if nothing is ringing.
            context.startService(intent)
        }
    }
}
