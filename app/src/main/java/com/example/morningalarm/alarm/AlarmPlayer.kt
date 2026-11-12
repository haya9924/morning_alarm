package com.example.morningalarm.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * Owns the ringing media for one AlarmRingActivity instance.
 *
 * MediaPlayer is used so the sound loops on API 26/27 too (Ringtone.isLooping is
 * API 28+). Ringtone is only a fallback when the default alarm sound is unusable.
 */
class AlarmPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var started = false

    fun start() {
        if (started) return
        started = true

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        if (uri != null) {
            val player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    // Keep the CPU/audio path alive while the device is otherwise idle.
                    runCatching { setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK) }
                    setDataSource(context, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            }.getOrNull()

            if (player != null) {
                mediaPlayer = player
            } else {
                Log.w(TAG, "MediaPlayer failed; falling back to Ringtone")
                runCatching {
                    ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                        audioAttributes = attributes
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            isLooping = true
                        }
                        play()
                    }
                }.onFailure { Log.e(TAG, "Ringtone fallback failed", it) }
            }
        } else {
            Log.w(TAG, "No default alarm/notification sound available")
        }

        val vib = context.getSystemService(Vibrator::class.java)
        if (vib != null && vib.hasVibrator()) {
            vibrator = vib
            runCatching {
                vib.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0L, 700L, 500L),
                        0,
                    ),
                )
            }.onFailure { Log.w(TAG, "Vibration failed", it) }
        }
    }

    fun stop() {
        started = false
        mediaPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
        mediaPlayer = null

        ringtone?.let { tone ->
            runCatching { if (tone.isPlaying) tone.stop() }
        }
        ringtone = null

        vibrator?.let { vib -> runCatching { vib.cancel() } }
        vibrator = null
    }

    private companion object {
        const val TAG = "AlarmPlayer"
    }
}
