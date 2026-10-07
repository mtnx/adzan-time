package id.waktusholat.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

class BootReceiver : BroadcastReceiver() {
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
}
