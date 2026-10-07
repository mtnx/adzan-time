package id.waktusholat.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDateTime
import java.time.ZoneId

object Scheduler {
    const val ACTION_ALARM = "id.waktusholat.widget.ALARM"

    /** Blocking: panggil dari thread background. Jadwalkan alarm sholat berikutnya + update widget. */
    fun rescheduleAndUpdate(ctx: Context) {
        PrayerRepo.prefetch(ctx)
        val nxt = PrayerRepo.next(ctx, LocalDateTime.now())
        if (nxt == null) {
            setAlarm(ctx, System.currentTimeMillis() + 15 * 60 * 1000, -1)
        } else {
            val at = nxt.second.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            setAlarm(ctx, at, nxt.first)
        }
        try { PrayerWidget.update(ctx) } catch (e: Exception) { }
    }

    private fun setAlarm(ctx: Context, atMillis: Long, idx: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 1,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALARM).putExtra("idx", idx),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
    }
}
