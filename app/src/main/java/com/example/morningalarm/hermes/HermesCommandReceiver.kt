package com.example.morningalarm.hermes

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.example.morningalarm.MorningAlarmApp
import com.example.morningalarm.data.Alarm
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.time.ZonedDateTime

/**
 * Explicit automation entry point for Hermes.
 *
 * The manifest requires the sender to hold android.permission.DUMP, which is a
 * signature|privileged|development permission normally available to adb shell,
 * root, and system components but not to ordinary third-party apps. There is no
 * intent filter, so only explicit `-n` broadcasts reach it.
 *
 * Known limitation: whether a particular OEM's adb shell holds DUMP (and therefore
 * whether these commands work over ADB) has not been device-verified here. Do not
 * treat this as a general network API.
 */
class HermesCommandReceiver : BroadcastReceiver() {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MorningAlarmApp
        if (app == null) {
            respond(ok = false, message = "application not initialized")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Defense-in-depth logging only. The manifest permission is the gate.
            Log.d(TAG, "Command sender uid=${sentFromUid}")
        }

        val action = intent.action
        try {
            when (action) {
                HermesProtocol.Action.PING -> respond(
                    ok = true,
                    message = "pong",
                    data = linkedMapOf(
                        "package" to context.packageName,
                        "protocolVersion" to HermesProtocol.PROTOCOL_VERSION,
                    ),
                )

                HermesProtocol.Action.LIST_ALARMS -> respond(
                    ok = true,
                    message = "alarms",
                    data = app.repository.alarms.value.map {
                        HermesProtocol.alarmWithScheduleToMap(it, ZonedDateTime.now())
                    },
                )

                HermesProtocol.Action.CREATE_ALARM -> {
                    val hour = intent.optionalInt(HermesProtocol.Extra.HOUR)
                    val minute = intent.optionalInt(HermesProtocol.Extra.MINUTE)
                    val label = intent.getStringExtra(HermesProtocol.Extra.LABEL)
                    val request = HermesProtocol.Args.create(
                        hour = hour,
                        minute = minute,
                        label = label,
                        days = intent.getStringExtra(HermesProtocol.Extra.DAYS),
                    )
                        .getOrElse { return respond(ok = false, message = it.message ?: "invalid request") }

                    val alarm = app.repository.add(
                        hour = request.hour,
                        minute = request.minute,
                        label = request.label,
                        repeatDays = request.repeatDays,
                    )
                    app.scheduler.schedule(alarm)
                    respond(
                        ok = true,
                        message = "created",
                        data = HermesProtocol.alarmToMap(alarm),
                    )
                }

                HermesProtocol.Action.SET_ENABLED -> {
                    val id = HermesProtocol.Args.alarmId(intent.optionalLong(HermesProtocol.Extra.ID))
                        .getOrElse { return respond(ok = false, message = it.message ?: "invalid id") }
                    if (!intent.hasExtra(HermesProtocol.Extra.ENABLED)) {
                        return respond(ok = false, message = "enabled is required")
                    }
                    val enabled = HermesProtocol.Args.enabled(
                        intent.extras?.get(HermesProtocol.Extra.ENABLED),
                    ).getOrElse { return respond(ok = false, message = it.message ?: "invalid enabled") }
                    val updated = app.repository.setEnabled(id, enabled)
                        ?: return respond(ok = false, message = "alarm not found: $id")

                    if (updated.enabled) {
                        app.scheduler.schedule(updated)
                    } else {
                        app.scheduler.cancel(updated.id)
                    }
                    respond(
                        ok = true,
                        message = "updated",
                        data = HermesProtocol.alarmToMap(updated),
                    )
                }

                HermesProtocol.Action.DELETE_ALARM -> {
                    val id = HermesProtocol.Args.alarmId(intent.optionalLong(HermesProtocol.Extra.ID))
                        .getOrElse { return respond(ok = false, message = it.message ?: "invalid id") }
                    if (!app.repository.delete(id)) {
                        return respond(ok = false, message = "alarm not found: $id")
                    }
                    app.scheduler.cancel(id)
                    respond(ok = true, message = "deleted", data = linkedMapOf("id" to id))
                }

                HermesProtocol.Action.SET_REPEAT_DAYS -> {
                    val id = HermesProtocol.Args.alarmId(intent.optionalLong(HermesProtocol.Extra.ID))
                        .getOrElse { return respond(ok = false, message = it.message ?: "invalid id") }
                    val days = HermesProtocol.Args
                        .repeatDays(intent.getStringExtra(HermesProtocol.Extra.DAYS))
                        .getOrElse { return respond(ok = false, message = it.message ?: "invalid days") }
                    val updated = app.repository.setRepeatDays(id, days)
                        ?: return respond(ok = false, message = "alarm not found: $id")
                    reschedule(app, updated)
                    respond(ok = true, message = "updated", data = HermesProtocol.alarmToMap(updated))
                }

                HermesProtocol.Action.SKIP_NEXT -> {
                    val id = HermesProtocol.Args.alarmId(intent.optionalLong(HermesProtocol.Extra.ID))
                        .getOrElse { return respond(ok = false, message = it.message ?: "invalid id") }
                    val updated = app.repository.skipNext(id)
                        ?: return respond(ok = false, message = "alarm not found: $id")
                    reschedule(app, updated)
                    respond(ok = true, message = "skipped", data = HermesProtocol.alarmToMap(updated))
                }

                HermesProtocol.Action.SET_NEXT_TIME -> {
                    val id = HermesProtocol.Args.alarmId(intent.optionalLong(HermesProtocol.Extra.ID))
                        .getOrElse { return respond(ok = false, message = it.message ?: "invalid id") }
                    val time = HermesProtocol.Args
                        .nextTimeOverride(intent.getStringExtra(HermesProtocol.Extra.NEXT_TIME))
                        .getOrElse { return respond(ok = false, message = it.message ?: "invalid nextTime") }
                    val updated = app.repository.setNextTimeOverride(
                        id = id,
                        time = time?.toLocalTime(),
                        date = time?.toLocalDate(),
                    )
                        ?: return respond(ok = false, message = "alarm not found: $id")
                    reschedule(app, updated)
                    respond(ok = true, message = "updated", data = HermesProtocol.alarmToMap(updated))
                }

                HermesProtocol.Action.CLEAR_NEXT_ADJUSTMENT -> {
                    val id = HermesProtocol.Args.alarmId(intent.optionalLong(HermesProtocol.Extra.ID))
                        .getOrElse { return respond(ok = false, message = it.message ?: "invalid id") }
                    val updated = app.repository.clearNextAdjustment(id)
                        ?: return respond(ok = false, message = "alarm not found: $id")
                    reschedule(app, updated)
                    respond(ok = true, message = "cleared", data = HermesProtocol.alarmToMap(updated))
                }

                else -> respond(
                    ok = false,
                    message = "unknown or missing action; supported: ${SUPPORTED_ACTIONS.joinToString()}",
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Hermes command failed", t)
            respond(ok = false, message = t.message ?: t.javaClass.simpleName)
        }
    }

    private fun respond(ok: Boolean, message: String, data: Any? = null) {
        val json = gson.toJson(HermesProtocol.Response(ok = ok, message = message, data = data))
        setResultCode(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        setResultData(json)
        setResultExtras(Bundle().apply { putString(HermesProtocol.RESULT_JSON_EXTRA, json) })
        Log.i(TAG, json)
    }

    /** Re-registers (or cancels) the AlarmManager entry after a mutation. */
    private fun reschedule(app: MorningAlarmApp, alarm: Alarm) {
        if (alarm.enabled) {
            app.scheduler.schedule(alarm)
        } else {
            app.scheduler.cancel(alarm.id)
        }
    }

    private fun Intent.optionalInt(name: String): Int? = extras?.get(name) as? Int

    private fun Intent.optionalLong(name: String): Long? = extras?.get(name) as? Long

    private companion object {
        const val TAG = "HermesCommand"

        val SUPPORTED_ACTIONS = listOf(
            HermesProtocol.Action.PING,
            HermesProtocol.Action.LIST_ALARMS,
            HermesProtocol.Action.CREATE_ALARM,
            HermesProtocol.Action.SET_ENABLED,
            HermesProtocol.Action.DELETE_ALARM,
            HermesProtocol.Action.SET_REPEAT_DAYS,
            HermesProtocol.Action.SKIP_NEXT,
            HermesProtocol.Action.SET_NEXT_TIME,
            HermesProtocol.Action.CLEAR_NEXT_ADJUSTMENT,
        )
    }
}
