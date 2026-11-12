package com.example.morningalarm.alarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.morningalarm.R
import com.example.morningalarm.data.Alarm
import com.example.morningalarm.ui.AlarmRingActivity

/**
 * High-priority alarm notification with a full-screen intent.
 *
 * On Android 10+ a background broadcast cannot reliably start an activity directly.
 * The full-screen intent is the supported path. Playback itself is owned by
 * [AlarmService], so the notification and the ring UI control the same sound.
 */
object AlarmNotifier {
    private const val TAG = "AlarmNotifier"
    const val CHANNEL_ID = "alarm_ringing"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.alarm_channel_description)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    // canPostNotifications() performs the runtime check; notify() can still throw
    // SecurityException if the grant is revoked concurrently, and is wrapped below.
    @SuppressLint("MissingPermission")
    fun show(context: Context, alarm: Alarm) {
        if (!canPostNotifications(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS is not granted; alarm notification cannot be shown")
            return
        }

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(notificationId(alarm.id), buildRingingNotification(context, alarm))
        }.onFailure {
            Log.e(TAG, "Failed to post alarm notification", it)
        }
    }

    /**
     * Builds the foreground-service notification. The stop action sends an actual
     * stop command to [AlarmService]; the content/full-screen intent opens the ring UI.
     */
    fun buildRingingNotification(context: Context, alarm: Alarm): Notification {
        val fullScreenIntent = ringPendingIntent(context, alarm.id)
        val stopIntent = Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_STOP)
            .putExtra(AlarmService.EXTRA_ALARM_ID, alarm.id)
        val stopPendingIntent = PendingIntent.getService(
            context,
            AlarmScheduler.requestCodeFor(alarm.id),
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = alarm.label.ifBlank { context.getString(R.string.alarm_notification_title) }
        val time = String.format(java.util.Locale.JAPAN, "%02d:%02d", alarm.hour, alarm.minute)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("$time  ${context.getString(R.string.alarm_notification_text)}")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setShowWhen(true)
            .setSound(null)
            .addAction(0, context.getString(R.string.alarm_stop), stopPendingIntent)
            .build()
    }

    fun cancel(context: Context, alarmId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(alarmId))
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.canUseFullScreenIntent()
    }

    private fun ringPendingIntent(context: Context, alarmId: Long): PendingIntent {
        val intent = Intent(context, AlarmRingActivity::class.java)
            .putExtra(AlarmRingActivity.EXTRA_ALARM_ID, alarmId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            AlarmScheduler.requestCodeFor(alarmId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun notificationId(alarmId: Long): Int {
        val id = AlarmScheduler.requestCodeFor(alarmId) and 0x7fffffff
        return if (id == 0) 1 else id
    }
}
