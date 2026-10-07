package id.waktusholat.widget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val cTop = Color.parseColor("#0A1D21")
    private val cBottom = Color.parseColor("#103A41")
    private val cCard = Color.parseColor("#1A3A40")
    private val cBtn = Color.parseColor("#2A5057")
    private val cAccent = Color.parseColor("#FFC857")
    private val cMuted = Color.parseColor("#8FB0B5")
    private val cText = Color.WHITE
    private val cRowActive = Color.parseColor("#33FFC857")

    private lateinit var locText: TextView
    private lateinit var heroName: TextView
    private lateinit var heroSub: TextView
    private lateinit var chrono: Chronometer
    private lateinit var status: TextView
    private lateinit var adzanText: TextView
    private val nameViews = ArrayList<TextView>()
    private val timeViews = ArrayList<TextView>()
    private val bellViews = ArrayList<TextView>()
    private val rowBoxes = ArrayList<LinearLayout>()
    private val modeViews = ArrayList<TextView>()
    private val stepperUpdaters = ArrayList<() -> Unit>()

    private var chronoBase = 0L
    private var nextIdx = 0
    @Volatile private var busy = false
    private val handler = Handler(Looper.getMainLooper())
    private val delayedRefresh = Runnable { refresh() }

    private val cities = listOf(
        Triple("Jakarta", -6.2088, 106.8456),
        Triple("Bandung", -6.9175, 107.6191),
        Triple("Semarang", -6.9667, 110.4167),
        Triple("Yogyakarta", -7.7956, 110.3695),
        Triple("Surakarta (Solo)", -7.5666, 110.8283),
        Triple("Sragen", -7.4296, 111.0227),
        Triple("Surabaya", -7.2575, 112.7521),
        Triple("Medan", 3.5952, 98.6722),
        Triple("Palembang", -2.9761, 104.7754),
        Triple("Balikpapan", -1.2379, 116.8529),
        Triple("Makassar", -5.1477, 119.4327),
        Triple("Denpasar", -8.6705, 115.2126),
        Triple("Jayapura", -2.5337, 140.7181)
    )

    // ---------- helpers UI ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun tv(t: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 0, 0, dp(14)) }

    private fun card(title: String?): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = shape(cCard, 22)
            layoutParams = cardParams()
        }
        if (title != null) {
            c.addView(tv(title, 12f, cMuted, true).apply {
                letterSpacing = 0.1f
                setPadding(0, 0, 0, dp(8))
            })
        }
        return c
    }

    private fun pill(label: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(11), dp(14), dp(11))
        setTextColor(if (filled) cTop else cText)
        setTypeface(typeface, Typeface.BOLD)
        background = shape(if (filled) cAccent else cBtn, 14)
        setOnClickListener { onClick() }
    }

    private fun fullWidth(v: TextView, topDp: Int = 0): TextView {
        v.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topDp) }
        return v
    }

    private fun fmtOff(v: Int) = if (v > 0) "+$v mnt" else "$v mnt"

    private fun smallBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(cText)
        setTypeface(typeface, Typeface.BOLD)
        background = shape(cBtn, 12)
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(38))
        setOnClickListener { onClick() }
    }

    private fun stepper(title: String, value: (() -> Int)?, onDelta: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(tv(title, 15f, cText, false).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val vt = tv("", 14f, cAccent, true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(76)
        }
        fun upd() {
            vt.text = if (value == null) "semua" else fmtOff(value())
        }
        upd()
        stepperUpdaters.add { upd() }
        row.addView(smallBtn("−") {
            onDelta(-1)
            stepperUpdaters.forEach { it() }
            scheduleRefresh()
        })
        row.addView(vt)
        row.addView(smallBtn("+") {
            onDelta(1)
            stepperUpdaters.forEach { it() }
            scheduleRefresh()
        })
        return row
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(delayedRefresh)
        handler.postDelayed(delayedRefresh, 500)
    }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cTop
        window.navigationBarColor = cBottom

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(28))
        }

        // Header
        root.addView(tv("🌙  Waktu Sholat", 26f, cText, true))
        locText = tv("", 14f, cMuted, false).apply { setPadding(0, dp(4), 0, dp(16)) }
        root.addView(locText)

        // Hero
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#2A8791"), Color.parseColor("#12454B"))
            ).apply { cornerRadius = dp(28).toFloat() }
            layoutParams = cardParams()
        }
        hero.addView(tv("Sholat berikutnya", 13f, Color.parseColor("#CFE6E8"), false))
        heroName = tv("—", 36f, cAccent, true)
        hero.addView(heroName)
        chrono = Chronometer(this).apply {
            textSize = 52f
            setTextColor(cText)
            setTypeface(typeface, Typeface.BOLD)
            setCountDown(true)
            setOnChronometerTickListener {
                if (SystemClock.elapsedRealtime() > chronoBase + 1500 && !busy) refresh()
            }
        }
        hero.addView(chrono)
        heroSub = tv("", 14f, Color.parseColor("#CFE6E8"), false)
        hero.addView(heroSub)
        root.addView(hero)

        // Jadwal
        val sched = card("JADWAL HARI INI")
        for (i in 0..4) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(8), dp(10))
            }
            val n = tv(PrayerRepo.NAMES[i], 17f, cText, false).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val t = tv("--:--", 20f, cText, true)
            val bell = tv(if (Settings.enabled(this, i)) "🔔" else "🔕", 18f, cText, false).apply {
                setPadding(dp(14), dp(4), dp(8), dp(4))
            }
            bell.setOnClickListener {
                val on = !Settings.enabled(this, i)
                Settings.setEnabled(this, i, on)
                bell.text = if (on) "🔔" else "🔕"
            }
            row.addView(n)
            row.addView(t)
            row.addView(bell)
            sched.addView(row)
            nameViews.add(n)
            timeViews.add(t)
            bellViews.add(bell)
            rowBoxes.add(row)
        }
        sched.addView(tv("Ketuk lonceng untuk menyalakan/mematikan pengingat tiap waktu.", 11f, cMuted, false)
            .apply { setPadding(dp(12), dp(6), 0, 0) })
        root.addView(sched)

        // Pengingat
        val remind = card("PENGINGAT")
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(Color.parseColor("#12292D"), 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val labels = listOf("Mati", "Notifikasi", "Adzan")
        for (m in 0..2) {
            val v = tv(labels[m], 14f, cMuted, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            v.setOnClickListener {
                Settings.setMode(this, m)
                updateModeUi()
            }
            seg.addView(v)
            modeViews.add(v)
        }
        remind.addView(seg)
        adzanText = tv("", 13f, cMuted, false).apply { setPadding(0, dp(12), 0, dp(8)) }
        remind.addView(adzanText)
        remind.addView(fullWidth(pill("Pilih file suara adzan", false) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            startActivityForResult(i, 100)
        }))
        remind.addView(fullWidth(pill("Tes pengingat sekarang", true) {
            if (Settings.mode(this) == 0) {
                Toast.makeText(this, "Pengingat sedang Mati", Toast.LENGTH_SHORT).show()
            } else {
                val app = applicationContext
                val idx = nextIdx
                thread { Notifier.fire(app, idx, true) }
            }
        }, 8))
        root.addView(remind)
        updateModeUi()
        updateAdzanText()

        // Koreksi menit
        val corr = card("KOREKSI MENIT")
        corr.addView(stepper("Semua waktu", null) { d ->
            for (i in 0..4) Settings.setOffset(this, i, (Settings.offset(this, i) + d).coerceIn(-30, 30))
        })
        for (i in 0..4) {
            corr.addView(stepper(PrayerRepo.NAMES[i], { Settings.offset(this, i) }) { d ->
                Settings.setOffset(this, i, (Settings.offset(this, i) + d).coerceIn(-30, 30))
            })
        }
        corr.addView(fullWidth(pill("Reset koreksi", false) {
            for (i in 0..4) Settings.setOffset(this, i, 0)
            stepperUpdaters.forEach { it() }
            scheduleRefresh()
        }, 8))
        corr.addView(tv("Dipakai untuk menyamakan dengan jadwal masjid / Kemenag (misalnya +2 menit).", 11f, cMuted, false)
            .apply { setPadding(0, dp(8), 0, 0) })
        root.addView(corr)

        // Lokasi
        val locCard = card("LOKASI")
        val locRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        locRow.addView(pill("📍 GPS", false) { useGps() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(6) }
        })
        locRow.addView(pill("Pilih kota", false) { pickCity() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(6) }
        })
        locCard.addView(locRow)
        root.addView(locCard)

        // Widget & info
        root.addView(fullWidth(pill("Pasang widget ke home screen", true) { pinWidget() }))
        status = tv("", 12f, cMuted, false).apply { setPadding(0, dp(14), 0, 0) }
        root.addView(status)
        root.addView(tv(
            "Tips Xiaomi/HP lain: aktifkan Autostart dan set Baterai ke \"Tanpa batasan\" untuk app ini " +
                "supaya adzan tetap tepat waktu. Volume adzan mengikuti volume alarm.",
            12f, cMuted, false
        ).apply { setPadding(0, dp(8), 0, 0) })

        setContentView(ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(cTop, cBottom))
            isFillViewport = true
            addView(root)
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        handler.removeCallbacks(delayedRefresh)
        super.onPause()
    }

    // ---------- data ----------
    private fun refresh() {
        if (busy) return
        busy = true
        val app = applicationContext
        locText.text = "📍 " + PrayerRepo.getLoc(app).label
        status.text = "Memuat jadwal..."
        thread {
            var times: List<LocalTime>? = null
            var nxt: Pair<Int, LocalDateTime>? = null
            try {
                Scheduler.rescheduleAndUpdate(app)
                times = PrayerRepo.timesFor(app, LocalDate.now())
                nxt = PrayerRepo.next(app, LocalDateTime.now())
            } catch (e: Exception) {
            }
            runOnUiThread {
                busy = false
                render(times, nxt)
            }
        }
    }

    private fun render(times: List<LocalTime>?, nxt: Pair<Int, LocalDateTime>?) {
        if (times == null || nxt == null) {
            heroName.text = "—"
            heroSub.text = "Tidak ada data"
            chrono.stop()
            chrono.text = "--:--"
            status.text = "Gagal memuat. Cek internet lalu coba lagi."
            return
        }
        nextIdx = nxt.first
        val tomorrow = nxt.second.toLocalDate() != LocalDate.now()
        for (i in 0..4) {
            val hl = i == nxt.first && !tomorrow
            nameViews[i].setTextColor(if (hl) cAccent else cText)
            timeViews[i].setTextColor(if (hl) cAccent else cText)
            timeViews[i].text = times[i].toString()
            rowBoxes[i].background = if (hl) shape(cRowActive, 14) else null
        }
        heroName.text = PrayerRepo.NAMES[nxt.first]
        heroSub.text = "pukul ${nxt.second.toLocalTime()}" + if (tomorrow) " (besok)" else ""
        val remaining = Duration.between(LocalDateTime.now(), nxt.second).toMillis()
        chronoBase = SystemClock.elapsedRealtime() + remaining
        chrono.base = chronoBase
        chrono.start()
        status.text = "Sumber: Aladhan API • metode Kemenag RI"
    }

    private fun updateModeUi() {
        val cur = Settings.mode(this)
        for (m in 0..2) {
            val sel = m == cur
            modeViews[m].setTextColor(if (sel) cTop else cMuted)
            modeViews[m].background = if (sel) shape(cAccent, 11) else null
        }
    }

    private fun updateAdzanText() {
        adzanText.text = if (Settings.hasAdzan(this)) {
            "Suara adzan: ${Settings.adzanName(this)}"
        } else {
            "Belum ada file adzan. Pilih file audio (mp3/m4a) dari HP. Tanpa file, mode Adzan memakai notifikasi biasa."
        }
    }

    // ---------- file adzan ----------
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val app = applicationContext
        Toast.makeText(this, "Menyalin file...", Toast.LENGTH_SHORT).show()
        thread {
            val ok = try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    Settings.adzanFile(app).outputStream().use { out -> input.copyTo(out) }
                }
                true
            } catch (e: Exception) {
                false
            }
            if (ok) {
                val name = try {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                } catch (e: Exception) {
                    null
                } ?: "adzan"
                Settings.setAdzanName(app, name)
            }
            runOnUiThread {
                updateAdzanText()
                Toast.makeText(
                    this,
                    if (ok) "File adzan disimpan" else "Gagal membaca file",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ---------- lokasi ----------
    private fun pinWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, PrayerWidget::class.java), null, null)
        } else {
            Toast.makeText(
                this,
                "Launcher tidak mendukung. Tambahkan lewat menu Widget di home screen.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun pickCity() {
        AlertDialog.Builder(this)
            .setTitle("Pilih kota")
            .setItems(cities.map { it.first }.toTypedArray()) { _, i ->
                val c = cities[i]
                PrayerRepo.setLoc(this, c.second, c.third, c.first)
                refresh()
            }
            .show()
    }

    private fun useGps() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1
            )
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        val providers = lm.getProviders(true)
        var best: Location? = null
        for (p in providers) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null }
            if (l != null && (best == null || l.time > best.time)) best = l
        }
        if (best != null) {
            applyLocation(best)
            return
        }
        val provider = providers.firstOrNull()
        if (provider == null) {
            Toast.makeText(this, "Aktifkan layanan lokasi dulu", Toast.LENGTH_LONG).show()
            return
        }
        status.text = "Mencari lokasi..."
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, mainExecutor) { l ->
                    if (l != null) applyLocation(l)
                    else Toast.makeText(this, "Lokasi tidak ditemukan", Toast.LENGTH_LONG).show()
                }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, object : LocationListener {
                    override fun onLocationChanged(location: Location) = applyLocation(location)
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Izin lokasi ditolak", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) useGps()
        else Toast.makeText(this, "Izin lokasi ditolak, pilih kota manual saja", Toast.LENGTH_LONG).show()
    }

    private fun applyLocation(l: Location) {
        val app = applicationContext
        thread {
            val name = try {
                @Suppress("DEPRECATION")
                val a = Geocoder(app, Locale("id", "ID"))
                    .getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
                a?.subAdminArea ?: a?.locality ?: a?.subLocality
            } catch (e: Exception) {
                null
            }
            val label = name ?: String.format(Locale.US, "%.3f, %.3f", l.latitude, l.longitude)
            PrayerRepo.setLoc(app, l.latitude, l.longitude, label)
            runOnUiThread { refresh() }
        }
    }
}
