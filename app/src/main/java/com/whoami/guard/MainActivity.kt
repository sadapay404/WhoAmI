package com.whoami.guard

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.whoami.guard.guard.GuardService
import com.whoami.guard.log.DecisionLog
import com.whoami.guard.media.WallpaperKeeper
import com.whoami.guard.prefs.AppPrefs
import com.whoami.guard.setup.EnrollFaceActivity
import com.whoami.guard.setup.SetupActivity
import com.whoami.guard.setup.TestCheckActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs

    private lateinit var statusLine: TextView
    private lateinit var homeStatus: TextView
    private lateinit var homeNote: TextView
    private lateinit var logView: TextView
    private lateinit var switchWhatsapp: MaterialSwitch
    private lateinit var switchUncertain: MaterialSwitch
    private lateinit var switchArmed: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = AppPrefs(this)

        // First run → wizard takes over.
        if (!prefs.setupComplete) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        statusLine = findViewById(R.id.statusLine)
        homeStatus = findViewById(R.id.homeStatus)
        homeNote = findViewById(R.id.homeNote)
        logView = findViewById(R.id.logView)
        switchWhatsapp = findViewById(R.id.switchWhatsapp)
        switchUncertain = findViewById(R.id.switchUncertain)
        switchArmed = findViewById(R.id.switchArmed)

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        homeStatus.text = "v$version · fully offline"

        // Guard runs for as long as protection is armed.
        if (prefs.protectionArmed) {
            GuardService.start(this)
        }

        // --- actions ---
        findViewById<Button>(R.id.btnTestCheck).setOnClickListener {
            startActivity(Intent(this, TestCheckActivity::class.java))
        }
        findViewById<Button>(R.id.btnReenroll).setOnClickListener {
            startActivity(Intent(this, EnrollFaceActivity::class.java))
        }
        findViewById<Button>(R.id.btnResetwork).setOnClickListener {
            val ok = WallpaperKeeper.backup(this)
            prefs.wallpaperBackedUp = ok
            render()
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            DecisionLog.clear(this)
            render()
        }

        // --- settings ---
        switchWhatsapp.isChecked = prefs.whatsappCheckEnabled
        switchWhatsapp.setOnCheckedChangeListener { _, checked ->
            prefs.whatsappCheckEnabled = checked
            logSetting("whatsapp check ${if (checked) "on" else "off"}")
        }

        switchUncertain.isChecked = prefs.uncertainPolicy == AppPrefs.POLICY_CAPTURE
        switchUncertain.setOnCheckedChangeListener { _, checked ->
            prefs.uncertainPolicy =
                if (checked) AppPrefs.POLICY_CAPTURE else AppPrefs.POLICY_ALLOW
            logSetting(
                "uncertain policy → ${if (checked) "capture" else "allow"}"
            )
        }

        switchArmed.isChecked = prefs.protectionArmed
        switchArmed.setOnCheckedChangeListener { _, checked ->
            prefs.protectionArmed = checked
            if (checked) GuardService.start(this) else GuardService.stop(this)
            logSetting("protection ${if (checked) "armed" else "disarmed"}")
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.setupComplete && ::statusLine.isInitialized) render()
    }

    companion object {
        const val EXTRA_SHOW_CAPTURES = "show_captures"
    }

    private fun logSetting(detail: String) {
        DecisionLog.append(
            this, DecisionLog.Trigger.MANUAL, DecisionLog.Outcome.INFO, detail
        )
        render()
    }

    private fun render() {
        val face = if (prefs.faceEnrolled)
            "✓ Face enrolled (${prefs.enrolledSampleCount} samples)" else "○ Face NOT enrolled"
        val wall = if (prefs.wallpaperBackedUp)
            "✓ Wallpaper backed up" else "○ Wallpaper NOT backed up"
        val guard = if (GuardService.running)
            "✓ Guard running — quiet checks active"
        else if (prefs.protectionArmed)
            "○ Guard starting… (open app once after install)"
        else "○ Guard DISARMED"
        val swapped = if (prefs.wallpaperSwapped)
            "\n⚠ Intruder wallpaper active — unlock as yourself to restore" else ""

        statusLine.text = "$guard\n$face\n$wall$swapped"

        val entries = DecisionLog.tail(this, 14)
        logView.text = if (entries.isEmpty()) {
            "No checks yet — lock and unlock your phone to see one."
        } else {
            entries.joinToString("\n")
        }

        homeNote.text = "Every unlock and WhatsApp open is checked on-device. " +
            "Scores ≥ threshold = you; below = intruder (capture pipeline lands in Phase 4).\n\n" +
            "Flip a switch or run a test to generate log lines."
    }
}
