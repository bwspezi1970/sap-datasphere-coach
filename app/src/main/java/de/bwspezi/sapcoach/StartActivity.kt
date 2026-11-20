package de.bwspezi.sapcoach

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Unsichtbarer Starter: "SAP Coach" antippen oder "Hey Google, öffne SAP Coach". */
class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = CoachAccessibilityService.instance
        if (service == null || Store.steps(this).isEmpty()) {
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            service.startReplay()
        }
        finish()
    }
}
