package de.bwspezi.sapcoach

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Bedienungshilfe, die
 *  1. beim Anlernen deine Taps in der Claude-App aufzeichnet (über eine durchsichtige Ebene) und
 *  2. sie später wiedergibt: Claude-App öffnen → Projekt → Sprachmodus.
 */
class CoachAccessibilityService : AccessibilityService() {

    companion object {
        const val CLAUDE = "com.anthropic.claude"
        private const val STEP_TIMEOUT_MS = 12_000L
        private const val UNLOCK_WAIT_MS = 10 * 60_000L

        @Volatile var instance: CoachAccessibilityService? = null
            private set
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    // Anlernen
    private var learning = false
    private var overlay: View? = null
    private var overlayInfo: TextView? = null
    private val recorded = mutableListOf<Step>()

    // Wiedergabe
    private var replaying = false
    private var steps: List<Step> = emptyList()
    private var idx = 0
    private var stepStart = 0L

    // Autostart
    private var waitForUnlockUntil = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WindowManager::class.java)
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, f)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        hideOverlay()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ================= Autostart beim Verbinden mit dem Auto =================

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (!Store.autoStart(ctx)) return
                    val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val addr = try { dev?.address } catch (_: SecurityException) { null }
                    if (addr == null || addr != Store.btAddress(ctx)) return
                    // Android Auto etwas Zeit zum Verbinden lassen
                    h.postDelayed({ startWhenUnlocked() }, 6_000)
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (SystemClock.elapsedRealtime() < waitForUnlockUntil) {
                        waitForUnlockUntil = 0
                        h.postDelayed({ startReplay() }, 1_000)
                    }
                }
            }
        }
    }

    private fun startWhenUnlocked() {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            waitForUnlockUntil = SystemClock.elapsedRealtime() + UNLOCK_WAIT_MS
            toast("SAP Coach: Entsperre das Handy, dann starte ich den Sprachmodus.")
        } else startReplay()
    }

    // ================= Claude-App starten =================

    private fun launchClaude(): Boolean {
        val i = packageManager.getLaunchIntentForPackage(CLAUDE) ?: run {
            toast("Claude-App nicht gefunden. Bitte aus dem Play Store installieren.")
            return false
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(i)
        return true
    }

    // ================= Anlernen =================

    fun startLearning() {
        stopReplay()
        recorded.clear()
        learning = true
        if (!launchClaude()) { learning = false; return }
        h.postDelayed({ if (learning) showOverlay() }, 1_800)
    }

    private fun showOverlay() {
        if (overlay != null) return
        val d = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(0x1A0A6ED1) }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6202020.toInt())
            isClickable = true // Taps auf die Leiste nicht als Schritt aufzeichnen
            val p = (12 * d).toInt()
            setPadding(p, p, p, p)
        }
        overlayInfo = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        bar.addView(overlayInfo)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Fertig"
            setOnClickListener { finishLearning(save = true) }
        })
        buttons.addView(Button(this).apply {
            text = "Letzten Schritt löschen"
            setOnClickListener { if (recorded.isNotEmpty()) recorded.removeAt(recorded.lastIndex); updateOverlayInfo() }
        })
        buttons.addView(Button(this).apply {
            text = "Abbrechen"
            setOnClickListener { finishLearning(save = false) }
        })
        bar.addView(buttons)
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL
        ))

        // Taps außerhalb der Leiste werden aufgezeichnet und an Claude durchgereicht.
        root.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) onLearnTap(ev.rawX, ev.rawY)
            true
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(root, lp)
        overlay = root
        updateOverlayInfo()
    }

    private fun updateOverlayInfo() {
        val sb = StringBuilder("SAP Coach lernt mit. Tippe in Claude nacheinander: Menü → Projekte → SAP Datasphere → (neuer Chat, falls nötig) → Sprachmodus-Symbol. Danach „Fertig“.\n")
        if (recorded.isEmpty()) sb.append("Noch keine Schritte.")
        else recorded.forEachIndexed { i, s -> sb.append("\n${i + 1}. ${s.describe()}") }
        overlayInfo?.text = sb.toString()
    }

    private fun hideOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
        overlayInfo = null
    }

    private fun onLearnTap(x: Float, y: Float) {
        recorded.add(describeAt(x, y))
        hideOverlay()
        // Kurz warten, bis die Ebene weg ist, dann den Tap an Claude weitergeben.
        h.postDelayed({
            tap(x, y) { h.postDelayed({ if (learning) showOverlay() }, 1_000) }
        }, 150)
    }

    private fun finishLearning(save: Boolean) {
        learning = false
        hideOverlay()
        if (save) {
            Store.setSteps(this, recorded.toList())
            toast("${recorded.size} Schritte gespeichert.")
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun describeAt(x: Float, y: Float): Step {
        val root = claudeRoot() ?: return Step(null, null, x, y)
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val r = Rect()
        forEachNode(root) { n ->
            n.getBoundsInScreen(r)
            if (r.contains(x.toInt(), y.toInt()) && n.isVisibleToUser) {
                val area = r.width().toLong() * r.height()
                if (area in 1 until bestArea) { best = n; bestArea = area }
            }
        }
        val target = best?.let { clickableAncestor(it) ?: it }
        return Step(target?.let { labelOf(it) }, target?.viewIdResourceName, x, y)
    }

    // ================= Wiedergabe =================

    fun startReplay() {
        if (learning) return
        steps = Store.steps(this)
        if (steps.isEmpty()) {
            toast("Noch kein Ablauf angelernt. Öffne „SAP Coach Setup“.")
            return
        }
        if (!launchClaude()) return
        replaying = true
        idx = 0
        stepStart = SystemClock.elapsedRealtime()
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ tick() }, 2_000)
    }

    private fun stopReplay() {
        replaying = false
    }

    private fun tick() {
        if (!replaying) return
        if (idx >= steps.size) {
            replaying = false
            toast("SAP Coach: Sprachmodus gestartet. Gute Fahrt!")
            return
        }
        val s = steps[idx]
        val elapsed = SystemClock.elapsedRealtime() - stepStart
        val node = if (s.label != null) claudeRoot()?.let { findMatch(it, s) } else null
        when {
            node != null -> {
                val r = Rect(); node.getBoundsInScreen(r)
                tap(r.exactCenterX(), r.exactCenterY(), null)
                next()
            }
            s.label == null && elapsed > 1_500 -> {
                tap(s.x, s.y, null)
                next()
            }
            elapsed > STEP_TIMEOUT_MS -> {
                replaying = false
                toast("SAP Coach: Schritt ${idx + 1} („${s.describe()}“) nicht gefunden. Bitte Ablauf neu anlernen.")
            }
            else -> h.postDelayed({ tick() }, 400)
        }
    }

    private fun next() {
        idx++
        stepStart = SystemClock.elapsedRealtime()
        h.postDelayed({ tick() }, 1_300)
    }

    private fun findMatch(root: AccessibilityNodeInfo, s: Step): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        forEachNode(root) { n ->
            if (!n.isVisibleToUser) return@forEachNode
            val own = ownLabel(n)
            val match = (own == s.label) || (n.isClickable && labelOf(n) == s.label)
            if (!match) return@forEachNode
            val target = clickableAncestor(n) ?: n
            if (s.viewId != null && target.viewIdResourceName != null && target.viewIdResourceName != s.viewId) return@forEachNode
            candidates.add(target)
        }
        if (candidates.isEmpty()) return null
        val r = Rect()
        return candidates.minByOrNull { c ->
            c.getBoundsInScreen(r)
            val dx = r.exactCenterX() - s.x
            val dy = r.exactCenterY() - s.y
            dx * dx + dy * dy
        }
    }

    // ================= Hilfsfunktionen =================

    private fun claudeRoot(): AccessibilityNodeInfo? {
        windows.forEach { w ->
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val r = w.root
                if (r != null && r.packageName == CLAUDE) return r
            }
        }
        return rootInActiveWindow?.takeIf { it.packageName == CLAUDE }
    }

    private fun forEachNode(n: AccessibilityNodeInfo, depth: Int = 0, f: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 60) return
        f(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { forEachNode(it, depth + 1, f) }
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 8) {
            if (cur.isClickable) return cur
            cur = cur.parent
            hops++
        }
        return null
    }

    private fun ownLabel(n: AccessibilityNodeInfo): String? =
        (n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: n.text?.toString()?.takeIf { it.isNotBlank() })?.trim()?.take(80)

    private fun labelOf(n: AccessibilityNodeInfo, depth: Int = 0): String? {
        ownLabel(n)?.let { return it }
        if (depth >= 3) return null
        for (i in 0 until n.childCount) {
            n.getChild(i)?.let { c -> labelOf(c, depth + 1)?.let { return it } }
        }
        return null
    }

    private fun tap(x: Float, y: Float, done: (() -> Unit)?) {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done?.invoke() }
            override fun onCancelled(gestureDescription: GestureDescription?) { done?.invoke() }
        }, null)
        if (!ok) done?.invoke()
    }

    private fun toast(t: String) = h.post { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }
}
