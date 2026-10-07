package id.waktusholat.widget

import android.content.Context
import java.io.File

object Settings {
    /** 0 = mati, 1 = notifikasi, 2 = adzan (suara) */
    private fun p(ctx: Context) = ctx.getSharedPreferences("prayer", Context.MODE_PRIVATE)

    fun mode(ctx: Context) = p(ctx).getInt("mode", 1)
    fun setMode(ctx: Context, m: Int) = p(ctx).edit().putInt("mode", m).apply()

    fun enabled(ctx: Context, i: Int) = p(ctx).getBoolean("en$i", true)
    fun setEnabled(ctx: Context, i: Int, v: Boolean) = p(ctx).edit().putBoolean("en$i", v).apply()

    fun offset(ctx: Context, i: Int) = p(ctx).getInt("off$i", 0)
    fun setOffset(ctx: Context, i: Int, v: Int) = p(ctx).edit().putInt("off$i", v).apply()

    /** Menit sholat yang baru masuk tetap ditampilkan sebelum pindah ke sholat berikutnya. */
    fun grace(ctx: Context) = p(ctx).getInt("grace", 30)
    fun setGrace(ctx: Context, v: Int) = p(ctx).edit().putInt("grace", v).apply()

    fun adzanFile(ctx: Context) = File(ctx.filesDir, "adzan.audio")
    fun hasAdzan(ctx: Context) = adzanFile(ctx).let { it.exists() && it.length() > 0 }
    fun adzanName(ctx: Context) = p(ctx).getString("adzan_name", "adzan") ?: "adzan"
    fun setAdzanName(ctx: Context, n: String) = p(ctx).edit().putString("adzan_name", n).apply()
}
