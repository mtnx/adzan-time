package id.waktusholat.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDate

object Notifier {
    const val CHANNEL = "waktu_sholat"

    fun fire(ctx: Context, idx: Int, force: Boolean) {
        if (!force && !Settings.enabled(ctx, idx)) return
        val mode = Settings.mode(ctx)
        if (mode == 0) return
        if (mode == 2 && Settings.hasAdzan(ctx)) {
            try {
                ctx.startForegroundService(
                    Intent(ctx, AdzanService::class.java).putExtra("idx", idx)
                )
                return
            } catch (e: Exception) {
                // gagal start service, jatuh ke notifikasi biasa
            }
        }
        notifyNow(ctx, idx)
    }

    private fun notifyNow(ctx: Context, idx: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "Prayer time alerts", NotificationManager.IMPORTANCE_HIGH)
        ch.enableVibration(true)
        nm.createNotificationChannel(ch)

        val time = try {
            PrayerRepo.timesFor(ctx, LocalDate.now())?.get(idx)?.toString()
        } catch (e: Exception) {
            null
        } ?: ""
        val loc = PrayerRepo.getLoc(ctx).label
        val name = PrayerRepo.NAMES[idx]

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("$name time")
            .setContentText("It's time for $name ($time) in $loc")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        nm.notify(100 + idx, n)
    }
}
