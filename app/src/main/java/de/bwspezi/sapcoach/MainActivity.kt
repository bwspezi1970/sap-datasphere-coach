package de.bwspezi.sapcoach

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** "SAP Coach Setup": Bedienungshilfe aktivieren, Ablauf anlernen, Autostart einstellen. */
class MainActivity : Activity() {

    companion object { private const val REQ_BT = 7 }

    private lateinit var statusView: TextView
    private lateinit var stepsView: TextView
    private lateinit var btView: TextView
    private lateinit var autoBox: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun header(t: String) = root.addView(TextView(this).apply {
            text = t; textSize = 18f; setPadding(0, pad, 0, pad / 3)
        })
        fun info(t: String) = root.addView(TextView(this).apply { text = t; textSize = 13f })
        fun button(t: String, onClick: () -> Unit) = root.addView(Button(this).apply {
            text = t; setOnClickListener { onClick() }
        })

        root.addView(TextView(this).apply { text = "SAP Coach"; textSize = 24f })
        info("Startet mit einem Befehl die Claude-App, öffnet dein Projekt SAP Datasphere und schaltet den Sprachmodus ein. Läuft über dein Claude-Pro-Abo, keine API nötig.")

        statusView = TextView(this).apply { setPadding(0, pad / 2, 0, 0); textSize = 14f }
        root.addView(statusView)

        header("1. Bedienungshilfe einschalten")
        info("Tippe auf „Einstellungen öffnen“ → Installierte Apps (bzw. Installierte Dienste) → SAP Coach → einschalten.\n\n" +
            "Ist der Schalter ausgegraut („Eingeschränkte Einstellung“)? Dann zuerst „App-Info öffnen“ → oben rechts ⋮ → „Eingeschränkte Einstellungen zulassen“, danach erneut versuchen.")
        button("Einstellungen öffnen") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("App-Info öffnen") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        header("2. Ablauf anlernen")
        info("SAP Coach öffnet Claude und legt eine leicht blaue Ebene darüber. Tippe ganz normal nacheinander: Menü → Projekte → SAP Datasphere → (neuer Chat, falls nötig) → Sprachmodus-Symbol. Jeder Tap wird gemerkt und an Claude weitergegeben. Zum Schluss in der Leiste „Fertig“.")
        button("Ablauf anlernen") {
            val s = CoachAccessibilityService.instance
            if (s == null) toast("Bitte zuerst Schritt 1 erledigen.") else s.startLearning()
        }
        stepsView = TextView(this)
        root.addView(stepsView)

        header("3. Testen")
        button("Jetzt starten (wie im Auto)") {
            val s = CoachAccessibilityService.instance
            if (s == null) toast("Bitte zuerst Schritt 1 erledigen.") else s.startReplay()
        }

        header("4. Starten im Auto")
        info("• Symbol „SAP Coach“ antippen, oder\n" +
            "• „Hey Google, öffne SAP Coach“ (Handy muss entsperrt sein), oder\n" +
            "• automatisch beim Verbinden mit dem Auto (unten einrichten). Ist das Handy gesperrt, startet SAP Coach direkt nach dem Entsperren.\n\n" +
            "Der Ton läuft über Bluetooth bzw. Android Auto. Das Handy bleibt dabei in der Halterung; Claude hört über das Handymikrofon.")
        autoBox = CheckBox(this).apply {
            text = "Automatisch starten, wenn sich das Handy mit dem Auto verbindet"
            setOnCheckedChangeListener { _, checked ->
                Store.setAutoStart(this@MainActivity, checked)
                if (checked && Store.btAddress(this@MainActivity) == null) chooseBluetooth()
            }
        }
        root.addView(autoBox)
        btView = TextView(this)
        root.addView(btView)
        button("Auto-Bluetooth auswählen") { chooseBluetooth() }

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val claudeInstalled = packageManager.getLaunchIntentForPackage(CoachAccessibilityService.CLAUDE) != null
        val serviceOn = CoachAccessibilityService.instance != null
        val steps = Store.steps(this)
        statusView.text = buildString {
            append(if (claudeInstalled) "✅ Claude-App gefunden\n" else "❌ Claude-App nicht installiert\n")
            append(if (serviceOn) "✅ Bedienungshilfe aktiv\n" else "❌ Bedienungshilfe aus (Schritt 1)\n")
            append(if (steps.isNotEmpty()) "✅ Ablauf angelernt (${steps.size} Schritte)" else "❌ Noch kein Ablauf angelernt (Schritt 2)")
        }
        stepsView.text = if (steps.isEmpty()) "" else
            "Angelernte Schritte:\n" + steps.mapIndexed { i, s -> "${i + 1}. ${s.describe()}" }.joinToString("\n")
        autoBox.isChecked = Store.autoStart(this)
        btView.text = "Auto-Bluetooth: " + (Store.btName(this) ?: "nicht ausgewählt")
    }

    @SuppressLint("MissingPermission")
    private fun chooseBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val devices = (try { adapter?.bondedDevices?.toList() } catch (_: SecurityException) { null }).orEmpty()
        if (devices.isEmpty()) { toast("Keine gekoppelten Bluetooth-Geräte gefunden."); return }
        val names = devices.map { d -> (try { d.name } catch (_: SecurityException) { null }) ?: d.address }
        AlertDialog.Builder(this)
            .setTitle("Welches Gerät ist dein Auto?")
            .setItems(names.toTypedArray()) { _, which ->
                Store.setBt(this, devices[which].address, names[which])
                refresh()
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) chooseBluetooth()
        else if (requestCode == REQ_BT) toast("Ohne Bluetooth-Berechtigung kein Autostart möglich.")
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
