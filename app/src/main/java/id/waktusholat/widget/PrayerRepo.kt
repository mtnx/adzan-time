package id.waktusholat.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

object PrayerRepo {
    val NAMES = listOf("Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya")
    private val KEYS = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
    private val DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    data class Loc(val lat: Double, val lon: Double, val label: String)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("prayer", Context.MODE_PRIVATE)

    fun getLoc(ctx: Context): Loc {
        val p = prefs(ctx)
        return Loc(
            p.getString("lat", "-6.2088")!!.toDouble(),
            p.getString("lon", "106.8456")!!.toDouble(),
            p.getString("label", "Jakarta")!!
        )
    }

    fun setLoc(ctx: Context, lat: Double, lon: Double, label: String) {
        prefs(ctx).edit()
            .putString("lat", lat.toString())
            .putString("lon", lon.toString())
            .putString("label", label)
            .apply()
    }

    private fun fmt(d: Double, digits: Int) = String.format(Locale.US, "%." + digits + "f", d)

    /** Unduh jadwal 1 bulan dari Aladhan. Metode 20 = Kemenag RI. */
    private fun download(loc: Loc, ym: YearMonth): String? {
        val tz = URLEncoder.encode(TimeZone.getDefault().id, "UTF-8")
        val url = URL(
            "https://api.aladhan.com/v1/calendar/${ym.year}/${ym.monthValue}" +
                "?latitude=${fmt(loc.lat, 4)}&longitude=${fmt(loc.lon, 4)}" +
                "&method=20&timezonestring=$tz"
        )
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            if (c.responseCode != 200) return null
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(body).getJSONArray("data")
            if (data.length() == 0) return null
            return data.toString()
        } finally {
            c.disconnect()
        }
    }

    private fun parse(raw: String): Map<LocalDate, List<LocalTime>> {
        val arr = JSONArray(raw)
        val out = HashMap<LocalDate, List<LocalTime>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val d = LocalDate.parse(
                o.getJSONObject("date").getJSONObject("gregorian").getString("date"),
                DATE_FMT
            )
            val t = o.getJSONObject("timings")
            out[d] = KEYS.map { LocalTime.parse(t.getString(it).substring(0, 5)) }
        }
        return out
    }

    private fun loadMonth(ctx: Context, ym: YearMonth): Map<LocalDate, List<LocalTime>>? {
        val loc = getLoc(ctx)
        val key = "m_${fmt(loc.lat, 2)}_${fmt(loc.lon, 2)}_$ym"
        val p = prefs(ctx)
        var raw = p.getString(key, null)
        if (raw == null) {
            raw = try { download(loc, ym) } catch (e: Exception) { null }
            if (raw != null) p.edit().putString(key, raw).apply()
        }
        return try { raw?.let { parse(it) } } catch (e: Exception) { null }
    }

    /** Pastikan bulan ini (dan bulan depan di akhir bulan) sudah tersimpan untuk mode offline. */
    fun prefetch(ctx: Context) {
        val today = LocalDate.now()
        loadMonth(ctx, YearMonth.from(today))
        if (today.dayOfMonth >= 25) loadMonth(ctx, YearMonth.from(today.plusMonths(1)))
    }

    fun timesFor(ctx: Context, date: LocalDate): List<LocalTime>? =
        loadMonth(ctx, YearMonth.from(date))?.get(date)

    /** Index sholat berikutnya + waktunya. */
    fun next(ctx: Context, now: LocalDateTime): Pair<Int, LocalDateTime>? {
        val today = now.toLocalDate()
        val t = timesFor(ctx, today) ?: return null
        for (i in 0..4) {
            val dt = today.atTime(t[i])
            if (dt.isAfter(now)) return i to dt
        }
        val tomorrow = today.plusDays(1)
        val tm = timesFor(ctx, tomorrow) ?: return null
        return 0 to tomorrow.atTime(tm[0])
    }
}
