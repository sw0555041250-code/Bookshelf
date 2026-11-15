package com.chdv.madaf

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.load(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Reminders.CHANNEL, "תזכורות לימוד", NotificationManager.IMPORTANCE_HIGH)
        )
        Reminders.schedule(this)
    }
}

/**
 * One alarm at a time: the next reminder time out of all items' times and the general times.
 * When it fires, we look at what is still not done and remind about exactly that.
 */
object Reminders {
    const val CHANNEL = "reminders"
    private const val NOTIF_ID = 42
    const val EXTRA_AT = "at"
    const val ACTION_FIRE = "com.chdv.madaf.FIRE"
    const val ACTION_SNOOZE = "com.chdv.madaf.SNOOZE"

    private fun allTimes(): Set<Int> {
        val s = Store.settings
        return (Store.items.filter { !it.archived }.flatMap { it.times } + s.generalTimes).toSet()
    }

    /** Shabbat: quiet from Friday's cutoff until Motzei Shabbat. */
    fun quiet(t: LocalDateTime): Boolean {
        val s = Store.settings
        if (!s.shabbatQuiet) return false
        val m = t.hour * 60 + t.minute
        return (t.dayOfWeek == DayOfWeek.FRIDAY && m >= s.fridayCutoff) ||
            (t.dayOfWeek == DayOfWeek.SATURDAY && m < s.saturdayResume)
    }

    fun nextFire(from: LocalDateTime): LocalDateTime? {
        val times = allTimes().sorted()
        if (times.isEmpty()) return null
        var day = from.toLocalDate()
        repeat(9) {
            for (m in times) {
                val t = day.atTime(m / 60, m % 60)
                if (t.isAfter(from) && !quiet(t)) return t
            }
            day = day.plusDays(1)
        }
        return null
    }

    fun schedule(ctx: Context) {
        Store.load(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = firePending(ctx, -1)
        am.cancel(pi)
        val next = nextFire(LocalDateTime.now()) ?: return
        val minute = next.hour * 60 + next.minute
        val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, firePending(ctx, minute))
    }

    private fun firePending(ctx: Context, minute: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, 1,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_AT, minute),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun snooze(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + Store.settings.snoozeMinutes * 60_000L
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at,
            PendingIntent.getBroadcast(ctx, 2, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_SNOOZE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        cancel(ctx)
    }

    /** Which items to mention for a reminder at [minute] (-2 = everything pending, e.g. after snooze). */
    fun due(minute: Int): List<Item> {
        val today = Store.today()
        val general = minute == -2 || minute in Store.settings.generalTimes
        return Store.pending(today).filter { general || minute in it.times }
    }

    fun fire(ctx: Context, minute: Int) {
        Store.load(ctx)
        Store.rollover()
        val items = due(minute)
        if (items.isNotEmpty() && !quiet(LocalDateTime.now())) notify(ctx, items)
        schedule(ctx)
    }

    fun notify(ctx: Context, items: List<Item>) {
        val name = Store.settings.name.ifBlank { null }
        val title = if (name != null) "$name, עדיין לא למדת היום" else "עדיין לא למדת היום"
        val body = items.joinToString(" · ") { describe(it) }
        val open = PendingIntent.getActivity(ctx, 3,
            Intent(ctx, ReminderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snooze = PendingIntent.getBroadcast(ctx, 4,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_SNOOZE + ".now"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(items.joinToString("\n") { "▫ " + describe(it) + (if (it.note.isNotBlank()) " – ${it.note}" else "") }))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .addAction(Notification.Action.Builder(null, "פתיחה", open).build())
            .addAction(Notification.Action.Builder(null, "עוד ${Store.settings.snoozeMinutes} דק׳", snooze).build())
        if (Store.settings.fullScreen) b.setFullScreenIntent(open, true)
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, b.build())
    }

    /** "רמב\"ם – הלכות תפילה ה׳–ז׳" */
    fun describe(item: Item): String {
        val t = item.track
        if (item.isTrack && t != null) {
            val p = t.portionLabel()
            return if (p.isBlank()) item.title else "${item.title}: $p"
        }
        return item.title
    }

    fun cancel(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.load(ctx)
        when (intent.action) {
            Reminders.ACTION_FIRE -> Reminders.fire(ctx, intent.getIntExtra(Reminders.EXTRA_AT, -2))
            Reminders.ACTION_SNOOZE -> Reminders.fire(ctx, -2)
            Reminders.ACTION_SNOOZE + ".now" -> Reminders.snooze(ctx)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Reminders.schedule(ctx)
}
