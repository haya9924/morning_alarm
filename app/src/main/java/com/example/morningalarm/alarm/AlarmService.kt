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

            ACTION_STOP -> {
                val requestedId = intent.getLongExtra(EXTRA_ALARM_ID, -1L).takeIf { it > 0L }
                // A stale notification must not stop a newer alarm's service.
                if (stopRinging(requestedId)) {
                    stopSelf(startId)
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
        stopRinging(null)
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

        currentAlarmId = alarmId
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

    companion object {
        private const val TAG = "AlarmService"

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
