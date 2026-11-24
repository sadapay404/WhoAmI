package com.whoami.guard

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.whoami.guard.capture.CaptureService
import com.whoami.guard.guard.GuardService
import com.whoami.guard.health.HealthReport
import com.whoami.guard.health.PermissionChecks
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
    private lateinit var healthLine: TextView
    private lateinit var fixContainer: LinearLayout
    private lateinit var thresholdText: TextView
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
        healthLine = findViewById(R.id.healthLine)
        fixContainer = findViewById(R.id.fixContainer)
        thresholdText = findViewById(R.id.thresholdValue)
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

        // Phase 4 test hook: run the full capture pipeline without needing
        // a real face mismatch. Safe to use anytime; files land in the session list.
        findViewById<Button>(R.id.btnSimulate).setOnClickListener {
            DecisionLog.append(
                this, DecisionLog.Trigger.MANUAL, DecisionLog.Outcome.INFO,
                "SIMULATED intruder (manual test)"
            )
            runCatching {
                CaptureService.startSimulated(this, DecisionLog.Trigger.MANUAL)
            }.onFailure { e ->
                DecisionLog.append(
                    this, DecisionLog.Trigger.MANUAL, DecisionLog.Outcome.ERROR,
                    "simulate failed: ${e.message}"
                )
            }
            render()
        }

        findViewById<Button>(R.id.btnViewCaptures).setOnClickListener {
            startActivity(Intent(this, com.whoami.guard.gallery.GalleryActivity::class.java))
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

        // --- threshold tuning (Phase 6) ---
        findViewById<Button>(R.id.btnThresholdDown).setOnClickListener {
            prefs.matchThreshold = (prefs.matchThreshold - 0.05f).coerceIn(0.30f, 0.90f)
            logSetting("threshold → ${"%.2f".format(prefs.matchThreshold)}")
        }
        findViewById<Button>(R.id.btnThresholdUp).setOnClickListener {
            prefs.matchThreshold = (prefs.matchThreshold + 0.05f).coerceIn(0.30f, 0.90f)
            logSetting("threshold → ${"%.2f".format(prefs.matchThreshold)}")
        }
        thresholdText.text = "%.2f".format(prefs.matchThreshold)

        // Long-press headline for the Motorola battery explainer.
        healthLine.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("Keeping WhoAmI awake")
                .setMessage(HealthReport.motorolaBatteryGuide())
                .setPositiveButton("Open battery settings") { _, _ ->
                    val check = PermissionChecks.Check.BATTERY
                    if (!PermissionChecks.isOk(this, check)) {
                        runCatching { startActivity(PermissionChecks.fixIntent(this, check)) }
                    }
                }
                .setNegativeButton("Close", null)
                .show()
            true
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
        // Health report + one-tap fixes
        val report = HealthReport.build(this, prefs, GuardService.running)
        healthLine.text = report.headline + "\n" + report.lines.joinToString("\n")

        fixContainer.removeAllViews()
        for (check in PermissionChecks.Check.entries) {
            if (PermissionChecks.isOk(this, check)) continue
            val btn = Button(this).apply {
                text = check.fixedLabel
                textSize = 14f
                setOnClickListener {
                    runCatching { startActivity(PermissionChecks.fixIntent(this@MainActivity, check)) }
                }
            }
            fixContainer.addView(btn)
        }

        thresholdText.text = "%.2f".format(prefs.matchThreshold)

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
            "Score ≥ threshold = you; below = intruder → capture + wallpaper swap.\n\n" +
            "Too many false alarms? Raise the threshold. Missing yourself in the dark? " +
            "Lower it — or turn off strict mode."
    }
}
