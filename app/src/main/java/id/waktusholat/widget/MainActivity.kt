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
    private lateinit var heroLabel: TextView
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

    private var modeChangeAt = Long.MAX_VALUE
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

    private fun fmtOff(v: Int) = if (v > 0) "+$v min" else "$v min"

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

    private fun stepper(
        title: String,
        value: (() -> Int)?,
        format: (Int) -> String,
        onDelta: (Int) -> Unit
    ): LinearLayout {
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
            vt.text = if (value == null) "all" else format(value())
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
        root.addView(tv("🌙  Prayer Times", 26f, cText, true))
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
        heroLabel = tv("Next prayer", 13f, Color.parseColor("#CFE6E8"), false)
        hero.addView(heroLabel)
        heroName = tv("—", 36f, cAccent, true)
        hero.addView(heroName)
        chrono = Chronometer(this).apply {
            textSize = 52f
            setTextColor(cText)
            setTypeface(typeface, Typeface.BOLD)
            setCountDown(true)
            setOnChronometerTickListener {
                if (SystemClock.elapsedRealtime() > modeChangeAt + 1500 && !busy) refresh()
            }
        }
        hero.addView(chrono)
        heroSub = tv("", 14f, Color.parseColor("#CFE6E8"), false)
        hero.addView(heroSub)
        root.addView(hero)

        // Jadwal
        val sched = card("TODAY'S SCHEDULE")
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
        sched.addView(tv("Tap the bell to turn the alert on/off for each prayer.", 11f, cMuted, false)
            .apply { setPadding(dp(12), dp(6), 0, 0) })
        root.addView(sched)

        // Pengingat
        val remind = card("ALERTS")
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(Color.parseColor("#12292D"), 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val labels = listOf("Off", "Notification", "Adhan")
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
        remind.addView(fullWidth(pill("Choose adhan audio file", false) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            startActivityForResult(i, 100)
        }))
        remind.addView(fullWidth(pill("Test alert now", true) {
            if (Settings.mode(this) == 0) {
                Toast.makeText(this, "Alerts are turned off", Toast.LENGTH_SHORT).show()
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
        val corr = card("MINUTE ADJUSTMENT")
        corr.addView(stepper("All prayers", null, ::fmtOff) { d ->
            for (i in 0..4) Settings.setOffset(this, i, (Settings.offset(this, i) + d).coerceIn(-30, 30))
        })
        for (i in 0..4) {
            corr.addView(stepper(PrayerRepo.NAMES[i], { Settings.offset(this, i) }, ::fmtOff) { d ->
                Settings.setOffset(this, i, (Settings.offset(this, i) + d).coerceIn(-30, 30))
            })
        }
        corr.addView(fullWidth(pill("Reset adjustments", false) {
            for (i in 0..4) Settings.setOffset(this, i, 0)
            stepperUpdaters.forEach { it() }
            scheduleRefresh()
        }, 8))
        corr.addView(tv("Use this to match your local mosque or Kemenag schedule (e.g. +2 minutes).", 11f, cMuted, false)
            .apply { setPadding(0, dp(8), 0, 0) })
        root.addView(corr)

        // Tampilan: grace period
        val disp = card("PRAYER DISPLAY")
        disp.addView(stepper("Stay on current prayer", { Settings.grace(this) }, { "$it min" }) { d ->
            Settings.setGrace(this, (Settings.grace(this) + d * 5).coerceIn(0, 60))
        })
        disp.addView(tv(
            "After a prayer starts, the app and widget keep showing it (timer counts up) for this long " +
                "before switching to the next prayer. Set 0 to switch immediately.",
            11f, cMuted, false
        ).apply { setPadding(0, dp(8), 0, 0) })
        root.addView(disp)

        // Lokasi
        val locCard = card("LOCATION")
        val locRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        locRow.addView(pill("📍 GPS", false) { useGps() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(6) }
        })
        locRow.addView(pill("Choose city", false) { pickCity() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(6) }
        })
        locCard.addView(locRow)
        root.addView(locCard)

        // Widget & info
        root.addView(fullWidth(pill("Add widget to home screen", true) { pinWidget() }))
        status = tv("", 12f, cMuted, false).apply { setPadding(0, dp(14), 0, 0) }
        root.addView(status)
        root.addView(tv(
            "Xiaomi/other phones: enable Autostart and set Battery to \"No restrictions\" for this app " +
                "so the adhan fires on time. Adhan volume follows the alarm volume.",
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
        status.text = "Loading schedule..."
        thread {
            var times: List<LocalTime>? = null
            var st: PrayerRepo.Status? = null
            try {
                Scheduler.rescheduleAndUpdate(app)
                times = PrayerRepo.timesFor(app, LocalDate.now())
                st = PrayerRepo.status(app, LocalDateTime.now())
            } catch (e: Exception) {
            }
            runOnUiThread {
                busy = false
                render(times, st)
            }
        }
    }

    private fun render(times: List<LocalTime>?, st: PrayerRepo.Status?) {
        if (times == null || st == null) {
            heroLabel.text = "Next prayer"
            heroName.text = "—"
            heroSub.text = "No data"
            chrono.stop()
            chrono.text = "--:--"
            modeChangeAt = Long.MAX_VALUE
            status.text = "Failed to load. Check your connection and try again."
            return
        }
        val now = LocalDateTime.now()
        val cur = st.currentIdx
        val ct = st.currentTime
        val tomorrow = st.nextTime.toLocalDate() != LocalDate.now()
        nextIdx = cur ?: st.nextIdx
        val hlIdx = cur ?: (if (tomorrow) -1 else st.nextIdx)
        for (i in 0..4) {
            val hl = i == hlIdx
            nameViews[i].setTextColor(if (hl) cAccent else cText)
            timeViews[i].setTextColor(if (hl) cAccent else cText)
            timeViews[i].text = times[i].toString()
            rowBoxes[i].background = if (hl) shape(cRowActive, 14) else null
        }
        if (cur != null && ct != null) {
            val elapsed = Duration.between(ct, now).toMillis()
            val graceMs = Settings.grace(this) * 60_000L
            heroLabel.text = "Prayer time now"
            heroName.text = PrayerRepo.NAMES[cur]
            heroSub.text = "since ${ct.toLocalTime()} • next: ${PrayerRepo.NAMES[st.nextIdx]} ${st.nextTime.toLocalTime()}"
            chrono.setCountDown(false)
            chrono.format = "+%s"
            chrono.base = SystemClock.elapsedRealtime() - elapsed
            modeChangeAt = SystemClock.elapsedRealtime() + (graceMs - elapsed)
        } else {
            val remaining = Duration.between(now, st.nextTime).toMillis()
            val base = SystemClock.elapsedRealtime() + remaining
            heroLabel.text = "Next prayer"
            heroName.text = PrayerRepo.NAMES[st.nextIdx]
            heroSub.text = "at ${st.nextTime.toLocalTime()}" + if (tomorrow) " (tomorrow)" else ""
            chrono.setCountDown(true)
            chrono.format = null
            chrono.base = base
            modeChangeAt = base
        }
        chrono.start()
        status.text = "Source: Aladhan API • Kemenag RI method"
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
            "Adhan audio: ${Settings.adzanName(this)}"
        } else {
            "No adhan file yet. Choose an audio file (mp3/m4a) from your phone. Without a file, Adhan mode falls back to a normal notification."
        }
    }

    // ---------- file adzan ----------
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val app = applicationContext
        Toast.makeText(this, "Copying file...", Toast.LENGTH_SHORT).show()
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
                    if (ok) "Adhan file saved" else "Could not read file",
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
                "Your launcher does not support this. Add it from the Widgets menu on the home screen.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun pickCity() {
        AlertDialog.Builder(this)
            .setTitle("Choose city")
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
            Toast.makeText(this, "Please turn on location services first", Toast.LENGTH_LONG).show()
            return
        }
        status.text = "Finding location..."
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, mainExecutor) { l ->
                    if (l != null) applyLocation(l)
                    else Toast.makeText(this, "Location not found", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) useGps()
        else Toast.makeText(this, "Location permission denied, please choose a city manually", Toast.LENGTH_LONG).show()
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
