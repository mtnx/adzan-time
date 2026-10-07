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
    private const val CODE_PRAYER = 1
    private const val CODE_GRACE_END = 3

    /** Blocking: panggil dari thread background. Jadwalkan alarm + update widget. */
    fun rescheduleAndUpdate(ctx: Context) {
        PrayerRepo.prefetch(ctx)
        val st = PrayerRepo.status(ctx, LocalDateTime.now())
        if (st == null) {
            setAlarm(ctx, System.currentTimeMillis() + 15 * 60 * 1000, -1, CODE_PRAYER)
            cancel(ctx, CODE_GRACE_END)
        } else {
            setAlarm(ctx, millis(st.nextTime), st.nextIdx, CODE_PRAYER)
            val ct = st.currentTime
            if (st.currentIdx != null && ct != null) {
                val end = ct.plusMinutes(Settings.grace(ctx).toLong())
                setAlarm(ctx, millis(end), -1, CODE_GRACE_END)
            } else {
                cancel(ctx, CODE_GRACE_END)
            }
        }
        try { PrayerWidget.update(ctx) } catch (e: Exception) { }
    }

    private fun millis(t: LocalDateTime) =
        t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun pending(ctx: Context, idx: Int, code: Int) = PendingIntent.getBroadcast(
        ctx, code,
        Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALARM).putExtra("idx", idx),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun cancel(ctx: Context, code: Int) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, -1, code))
    }

    private fun setAlarm(ctx: Context, atMillis: Long, idx: Int, code: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, idx, code)
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
    }
}
