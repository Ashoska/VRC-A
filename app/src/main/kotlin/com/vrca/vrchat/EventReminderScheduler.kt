package com.vrca.vrchat

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vrca.R
import org.json.JSONObject

/**
 * Local reminders for events the user SIGNED UP for (Added to Calendar). VRChat's
 * own "Add to Calendar" follows the event server-side but doesn't reliably ping the
 * phone; this schedules a local alarm [LEAD_MS] before the event starts so the user
 * actually gets nudged in time to hop in.
 *
 * Alarms are inexact ([AlarmManager.setAndAllowWhileIdle]) so no exact-alarm
 * permission is needed — a few minutes of Doze slop is fine for a ~10-min-ahead
 * heads-up. Reminders are persisted so they can be re-scheduled after a reboot
 * (AlarmManager clears alarms on reboot). Cancelled when the user removes the event
 * from their calendar.
 */
object EventReminderScheduler {
    private const val TAG = "EventReminder"
    private const val PREFS = "vrca_event_reminders"
    private const val KEY = "reminders_json"
    private const val LEAD_MS = 10L * 60 * 1000  // fire 10 min before start
    private const val CHANNEL = "vrca_groups"    // shared with group-event alerts

    const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_URL = "url"
    const val EXTRA_STARTS_AT = "starts_at"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun requestCode(eventId: String) = ("evtremind_$eventId").hashCode()

    private fun alarmManager(ctx: Context) =
        ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private fun pendingIntent(ctx: Context, eventId: String, title: String, url: String?, startsAtMs: Long): PendingIntent {
        val intent = Intent(ctx, EventReminderReceiver::class.java).apply {
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_STARTS_AT, startsAtMs)
        }
        return PendingIntent.getBroadcast(
            ctx, requestCode(eventId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Schedule (or reschedule) a reminder for [eventId]. No-op when the fire time
     * ([startsAtMs] - lead) is already in the past (the event is imminent/live —
     * a reminder would be pointless) or the start time is unknown.
     */
    fun schedule(ctx: Context, eventId: String, title: String, startsAtMs: Long, url: String?) {
        if (eventId.isBlank() || startsAtMs <= 0L) return
        val fireAt = startsAtMs - LEAD_MS
        if (fireAt <= System.currentTimeMillis()) {
            Log.i(TAG, "Reminder for $eventId not scheduled — start is imminent/past")
            persistRemove(ctx, eventId)
            return
        }
        val am = alarmManager(ctx) ?: return
        try {
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, fireAt,
                pendingIntent(ctx, eventId, title, url, startsAtMs)
            )
            persistAdd(ctx, eventId, title, url, startsAtMs)
            Log.i(TAG, "Reminder scheduled for $eventId at $fireAt")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule reminder for $eventId", e)
        }
    }

    fun cancel(ctx: Context, eventId: String) {
        if (eventId.isBlank()) return
        alarmManager(ctx)?.cancel(pendingIntent(ctx, eventId, "", null, 0L))
        persistRemove(ctx, eventId)
    }

    /** Re-schedule all persisted reminders (call from BootReceiver — reboot clears
     *  AlarmManager). Drops any whose fire time has passed. */
    fun rescheduleAll(ctx: Context) {
        val obj = readAll(ctx)
        val keys = obj.keys().asSequence().toList()
        for (eventId in keys) {
            val r = obj.optJSONObject(eventId) ?: continue
            val startsAt = r.optLong("startsAt", 0L)
            if (startsAt - LEAD_MS <= System.currentTimeMillis()) {
                persistRemove(ctx, eventId)
                continue
            }
            schedule(ctx, eventId, r.optString("title"), startsAt, r.optString("url").ifBlank { null })
        }
    }

    private fun readAll(ctx: Context): JSONObject =
        try { JSONObject(prefs(ctx).getString(KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    private fun persistAdd(ctx: Context, eventId: String, title: String, url: String?, startsAtMs: Long) {
        val obj = readAll(ctx)
        obj.put(eventId, JSONObject().apply {
            put("title", title)
            put("url", url ?: "")
            put("startsAt", startsAtMs)
        })
        prefs(ctx).edit().putString(KEY, obj.toString()).apply()
    }

    private fun persistRemove(ctx: Context, eventId: String) {
        val obj = readAll(ctx)
        if (obj.has(eventId)) {
            obj.remove(eventId)
            prefs(ctx).edit().putString(KEY, obj.toString()).apply()
        }
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        NotificationChannel(CHANNEL, "Group activity", NotificationManager.IMPORTANCE_DEFAULT)
            .also { nm.createNotificationChannel(it) }
    }

    fun fireNotification(ctx: Context, eventId: String, title: String, url: String?, startsAtMs: Long) {
        ensureChannel(ctx)
        val minutes = ((startsAtMs - System.currentTimeMillis()) / 60000L).coerceAtLeast(0L)
        val text = if (minutes > 0) "\"$title\" starts in about $minutes min"
            else "\"$title\" is starting now"
        val tap = if (!url.isNullOrBlank()) {
            PendingIntent.getActivity(
                ctx, requestCode(eventId),
                Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null
        val notif = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Event starting soon")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(("evtremind_$eventId").hashCode(), notif)
        // One-shot — drop from the persisted set now that it's fired.
        persistRemove(ctx, eventId)
    }
}

/** Fires the local event reminder notification when its alarm goes off. */
class EventReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(EventReminderScheduler.EXTRA_EVENT_ID) ?: return
        val title = intent.getStringExtra(EventReminderScheduler.EXTRA_TITLE) ?: "Your event"
        val url = intent.getStringExtra(EventReminderScheduler.EXTRA_URL)
        val startsAt = intent.getLongExtra(EventReminderScheduler.EXTRA_STARTS_AT, 0L)
        EventReminderScheduler.fireNotification(context, eventId, title, url, startsAt)
    }
}
