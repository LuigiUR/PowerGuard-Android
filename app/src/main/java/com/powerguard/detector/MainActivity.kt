package com.powerguard.detector

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var tvBatteryInfo: TextView
    private lateinit var appsContainer: LinearLayout
    private lateinit var btnPermission: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Costruzione interfaccia nativa scura
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
        }

        val title = TextView(this).apply {
            text = "🛡️ PowerGuard Real Monitor"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        rootLayout.addView(title)

        val subtitle = TextView(this).apply {
            text = "Diagnostica Hardware e Consumi Reali"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 30)
        }
        rootLayout.addView(subtitle)

        // Card Telemetria Batteria Reale
        tvBatteryInfo = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(35, 35, 35, 35)
        }
        rootLayout.addView(tvBatteryInfo)

        // Pulsante Permessi / Aggiorna
        btnPermission = Button(this).apply {
            text = "Concedi Permesso Dati Utilizzo"
            setBackgroundColor(Color.parseColor("#4F46E5"))
            setTextColor(Color.WHITE)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 30, 0, 30) }
            layoutParams = params
            setOnClickListener {
                if (!hasUsageStatsPermission()) {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } else {
                    scanRealConsumption()
                    Toast.makeText(this@MainActivity, "Scansione completata!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        rootLayout.addView(btnPermission)

        val listHeader = TextView(this).apply {
            text = "Analisi Attività App (Ultime 24 Ore)"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 10, 0, 20)
        }
        rootLayout.addView(listHeader)

        appsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        rootLayout.addView(appsContainer)

        scrollView.addView(rootLayout)
        setContentView(scrollView)
    }

    override fun onResume() {
        super.onResume()
        readRealBatteryTelemetry()
        if (hasUsageStatsPermission()) {
            btnPermission.text = "🔄 Aggiorna Scansione Consumi"
            scanRealConsumption()
        } else {
            btnPermission.text = "⚠️ Attiva Permesso Dati Utilizzo"
            appsContainer.removeAllViews()
            val warn = TextView(this).apply {
                text = "Per rilevare quali app consumano batteria e risorse in background, tocca il pulsante qui sopra e attiva PowerGuard nella lista."
                setTextColor(Color.parseColor("#FBBF24"))
                textSize = 14f
            }
            appsContainer.addView(warn)
        }
    }

    private fun readRealBatteryTelemetry() {
        val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else 0

        val tempTenths = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempCelsius = tempTenths / 10.0

        val voltageMv = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentMicroAmps = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa = currentMicroAmps / 1000

        val tempAlert = if (tempCelsius > 39.0) "⚠️ SURRISCALDAMENTO" else "✅ Normale"

        tvBatteryInfo.text = """
            🔋 Livello Batteria: $batteryPct%
            🌡️ Temperatura Reale: ${tempCelsius}°C ($tempAlert)
            ⚡ Corrente Istantanea: $currentMa mA
            🔌 Voltaggio: $voltageMv mV
        """.trimIndent()
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun scanRealConsumption() {
        appsContainer.removeAllViews()
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - TimeUnit.HOURS.toMillis(24)

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        if (stats.isNullOrEmpty()) return

        // Aggrega e ordina per tempo di attività
        val sortedStats = stats
            .filter { it.totalTimeInForeground > TimeUnit.MINUTES.toMillis(1) && it.packageName != packageName }
            .sortedByDescending { it.totalTimeInForeground }
            .take(15)

        val pm = packageManager

        for (usage in sortedStats) {
            val appName = try {
                val info = pm.getApplicationInfo(usage.packageName, 0)
                pm.getApplicationLabel(info).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                usage.packageName
            }

            val minutes = TimeUnit.MILLISECONDS.toMinutes(usage.totalTimeInForeground)
            val isAnomaly = minutes > 120 // Oltre 2 ore nelle ultime 24h segnalato come consumo elevato

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(if (isAnomaly) Color.parseColor("#3F1D2B") else Color.parseColor("#1E293B"))
                setPadding(30, 25, 30, 25)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 20) }
                layoutParams = params
            }

            val rowTop = TextView(this).apply {
                text = if (isAnomaly) "🚨 $appName (Consumo Elevato)" else "✅ $appName"
                setTextColor(if (isAnomaly) Color.parseColor("#FDA4AF") else Color.WHITE)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
            }

            val rowBottom = TextView(this).apply {
                text = "Tempo attivo (24h): ${minutes} min • Pkg: ${usage.packageName}"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setPadding(0, 8, 0, 0)
            }

            card.addView(rowTop)
            card.addView(rowBottom)
            appsContainer.addView(card)
        }
    }
}
