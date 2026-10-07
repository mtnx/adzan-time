package id.waktusholat.widget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var locText: TextView
    private lateinit var timesText: TextView
    private lateinit var status: TextView

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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Waktu Sholat"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })

        locText = TextView(this).apply { textSize = 16f; setPadding(0, dp(8), 0, dp(16)) }
        root.addView(locText)

        timesText = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.3f)
        }
        root.addView(timesText)

        status = TextView(this).apply { textSize = 13f; setPadding(0, dp(12), 0, dp(16)) }
        root.addView(status)

        root.addView(button("Pakai lokasi GPS") { useGps() })
        root.addView(button("Pilih kota manual") { pickCity() })
        root.addView(button("Muat ulang data") { refresh() })

        root.addView(TextView(this).apply {
            text = "Cara pasang widget: tahan layar home, pilih Widget, lalu cari \"Waktu Sholat\"."
            textSize = 13f
            setPadding(0, dp(20), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setOnClickListener { onClick() }
    }

    private fun refresh() {
        val loc = PrayerRepo.getLoc(this)
        locText.text = "Lokasi: ${loc.label}"
        status.text = "Memuat jadwal..."
        val app = applicationContext
        thread {
            PrayerRepo.prefetch(app)
            val times = PrayerRepo.timesFor(app, LocalDate.now())
            try { PrayerWidget.update(app) } catch (e: Exception) { }
            runOnUiThread {
                if (times == null) {
                    timesText.text = ""
                    status.text = "Gagal memuat. Cek internet lalu coba lagi."
                } else {
                    timesText.text = PrayerRepo.NAMES.indices.joinToString("\n") {
                        PrayerRepo.NAMES[it].padEnd(10) + times[it]
                    }
                    status.text = "Sumber: Aladhan API (metode Kemenag RI)"
                }
            }
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
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ), 1
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
