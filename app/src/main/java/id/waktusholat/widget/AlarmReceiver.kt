package id.waktusholat.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val idx = intent.getIntExtra("idx", -1)
        val pending = goAsync()
        val app = context.applicationContext
        thread {
            try {
                if (idx in 0..4) Notifier.fire(app, idx, false)
            } catch (e: Exception) {
            }
            try {
                Scheduler.rescheduleAndUpdate(app)
            } catch (e: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
