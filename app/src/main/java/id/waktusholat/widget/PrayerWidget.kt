package id.waktusholat.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.concurrent.thread

class PrayerWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread {
            try {
                Scheduler.rescheduleAndUpdate(app)
            } catch (e: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val NAME_IDS = intArrayOf(R.id.n0, R.id.n1, R.id.n2, R.id.n3, R.id.n4)
        private val TIME_IDS = intArrayOf(R.id.t0, R.id.t1, R.id.t2, R.id.t3, R.id.t4)
        private val CHIP_IDS = intArrayOf(R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4)
        private const val ACCENT = 0xFFFFC857.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val MUTED = 0xFF9FBFC4.toInt()

        /** Hanya menggambar ulang widget (tanpa alarm). Boleh dari thread background. */
        fun update(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PrayerWidget::class.java))
            if (ids.isEmpty()) return

            val now = LocalDateTime.now()
            val today = PrayerRepo.timesFor(ctx, LocalDate.now())
            val st = PrayerRepo.status(ctx, now)
            val loc = PrayerRepo.getLoc(ctx)
            val rv = RemoteViews(ctx.packageName, R.layout.widget)

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.root, open)
            rv.setTextViewText(R.id.loc, loc.label)

            if (today == null || st == null) {
                rv.setTextViewText(R.id.label, "🌙  Next prayer")
                rv.setTextViewText(R.id.next_name, "No data")
                rv.setViewVisibility(R.id.countdown, View.GONE)
                rv.setTextViewText(R.id.next_sub, "Connect to the internet, then tap the widget")
                for (i in 0..4) {
                    rv.setTextViewText(NAME_IDS[i], PrayerRepo.NAMES[i])
                    rv.setTextViewText(TIME_IDS[i], "--:--")
                    rv.setInt(CHIP_IDS[i], "setBackgroundResource", 0)
                }
                mgr.updateAppWidget(ids, rv)
                return
            }

            val cur = st.currentIdx
            val ct = st.currentTime
            val tomorrow = st.nextTime.toLocalDate() != LocalDate.now()
            val hlIdx = cur ?: (if (tomorrow) -1 else st.nextIdx)

            rv.setViewVisibility(R.id.countdown, View.VISIBLE)
            if (cur != null && ct != null) {
                // Masih dalam masa grace: tampilkan sholat yang baru masuk, timer menghitung maju
                val elapsed = Duration.between(ct, now).toMillis()
                rv.setTextViewText(R.id.label, "🌙  Prayer time now")
                rv.setTextViewText(R.id.next_name, PrayerRepo.NAMES[cur])
                rv.setChronometerCountDown(R.id.countdown, false)
                rv.setChronometer(R.id.countdown, SystemClock.elapsedRealtime() - elapsed, "+%s", true)
                rv.setTextViewText(
                    R.id.next_sub,
                    "since ${ct.toLocalTime()} • next: ${PrayerRepo.NAMES[st.nextIdx]} ${st.nextTime.toLocalTime()}"
                )
            } else {
                val remaining = Duration.between(now, st.nextTime).toMillis()
                rv.setTextViewText(R.id.label, "🌙  Next prayer")
                rv.setTextViewText(R.id.next_name, PrayerRepo.NAMES[st.nextIdx])
                rv.setChronometerCountDown(R.id.countdown, true)
                rv.setChronometer(R.id.countdown, SystemClock.elapsedRealtime() + remaining, null, true)
                rv.setTextViewText(
                    R.id.next_sub,
                    "at ${st.nextTime.toLocalTime()}" + if (tomorrow) " (tomorrow)" else ""
                )
            }

            for (i in 0..4) {
                rv.setTextViewText(NAME_IDS[i], PrayerRepo.NAMES[i])
                rv.setTextViewText(TIME_IDS[i], today[i].toString())
                val hl = i == hlIdx
                rv.setTextColor(TIME_IDS[i], if (hl) ACCENT else WHITE)
                rv.setTextColor(NAME_IDS[i], if (hl) ACCENT else MUTED)
                rv.setInt(CHIP_IDS[i], "setBackgroundResource", if (hl) R.drawable.chip_active else 0)
            }
            mgr.updateAppWidget(ids, rv)
        }
    }
}
