package com.whoami.guard.setup

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.whoami.guard.MainActivity
import com.whoami.guard.R
import com.whoami.guard.prefs.AppPrefs

/**
 * First-run setup wizard. Walks the owner through every permission,
 * face enrollment, and wallpaper backup — one plain-English step at a time.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var stepTitle: TextView
    private lateinit var stepBody: TextView
    private lateinit var stepStatus: TextView
    private lateinit var nextButton: Button
    private lateinit var skipButton: Button

    private var step = 0

    private val runtimePermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).let {
        if (Build.VERSION.SDK_INT >= 33) it + Manifest.permission.POST_NOTIFICATIONS else it
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // Re-evaluate current step so status updates after the dialog closes.
            render()
        }

    private val enrollLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                prefs.faceEnrolled = true
            }
            render()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        prefs = AppPrefs(this)

        stepTitle = findViewById(R.id.setupTitle)
        stepBody = findViewById(R.id.setupBody)
        stepStatus = findViewById(R.id.setupStatus)
        nextButton = findViewById(R.id.setupNext)
        skipButton = findViewById(R.id.setupSkip)

        nextButton.setOnClickListener { onNext() }
        skipButton.setOnClickListener { onSkip() }

        render()
    }

    override fun onResume() {
        super.onResume()
        render() // permissions may have been granted in Settings
    }

    // --- wizard steps ----------------------------------------------------

    private fun onNext() {
        when (step) {
            STEP_WELCOME -> { step = STEP_PERMISSIONS; render() }
            STEP_PERMISSIONS -> {
                if (allRuntimeGranted()) { step = STEP_OVERLAY; render() }
                else permissionLauncher.launch(runtimePermissions)
            }
            STEP_OVERLAY -> {
                if (hasOverlay()) { step = STEP_USAGE; render() }
                else startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            STEP_USAGE -> {
                if (hasUsageAccess()) { step = STEP_BATTERY; render() }
                else startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            STEP_BATTERY -> {
                if (hasBatteryExemption()) { step = STEP_ENROLL; render() }
                else runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }.onFailure {
                    startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    )
                }
            }
            STEP_ENROLL -> {
                if (prefs.faceEnrolled) { step = STEP_WALLPAPER; render() }
                else enrollLauncher.launch(Intent(this, EnrollFaceActivity::class.java))
            }
            STEP_WALLPAPER -> {
                if (prefs.wallpaperBackedUp) { step = STEP_DONE; render() }
                else {
                    val ok = com.whoami.guard.media.WallpaperKeeper.backup(this)
                    prefs.wallpaperBackedUp = ok
                    render()
                }
            }
            STEP_DONE -> {
                prefs.setupComplete = true
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun onSkip() {
        // Only the battery step is skippable — everything else is load-bearing.
        when (step) {
            STEP_BATTERY -> { step = STEP_ENROLL; render() }
            else -> onNext()
        }
    }

    private fun render() {
        when (step) {
            STEP_WELCOME -> {
                stepTitle.text = "Welcome to WhoAmI"
                stepBody.text = "I'll watch for one thing: is the face at your phone actually YOU?\n\n" +
                    "If not — front photo, back photo, two short videos, and audio until you lock the screen.\n\n" +
                    "Nothing ever leaves your phone. Seven quick steps, about 3 minutes."
                stepStatus.text = ""
                nextButton.text = "Let's start"
                skipButton.visibility = android.view.View.GONE
            }
            STEP_PERMISSIONS -> {
                stepTitle.text = "1 · Camera & microphone"
                stepBody.text = "Needed to see who's holding the phone and to record the session.\n\n" +
                    "Tap Allow on the Android prompts that appear."
                stepStatus.text = statusLine(
                    "Camera", hasPermission(Manifest.permission.CAMERA),
                    "Microphone", hasPermission(Manifest.permission.RECORD_AUDIO)
                )
                nextButton.text = if (allRuntimeGranted()) "Next" : "Grant permissions"
                skipButton.visibility = android.view.View.GONE
            }
            STEP_OVERLAY -> {
                stepTitle.text = "2 · Display over other apps"
                stepBody.text = "This lets the quiet face-check appear the instant your phone unlocks — " +
                    "before anyone can open anything.\n\n" +
                    "Android will open a settings screen. Find WhoAmI in the list and turn it ON."
                stepStatus.text = if (hasOverlay()) "✓ Granted" else "○ Not granted yet"
                nextButton.text = if (hasOverlay()) "Next" : "Open settings"
                skipButton.visibility = android.view.View.GONE
            }
            STEP_USAGE -> {
                stepTitle.text = "3 · Usage access"
                stepBody.text = "This lets WhoAmI notice when WhatsApp is opened, so it can run the " +
                    "same face-check there too.\n\n" +
                    "Android will open a settings screen. Find WhoAmI and allow usage access."
                stepStatus.text = if (hasUsageAccess()) "✓ Granted" else "○ Not granted yet"
                nextButton.text = if (hasUsageAccess()) "Next" : "Open settings"
                skipButton.visibility = android.view.View.GONE
            }
            STEP_BATTERY -> {
                stepTitle.text = "4 · Keep WhoAmI awake"
                stepBody.text = "Motorola (and every Android) kills background apps to save battery. " +
                    "WhoAmI must never be killed or it can't watch.\n\n" +
                    "On the next screen, choose Unrestricted / Allow."
                stepStatus.text = if (hasBatteryExemption()) "✓ Unrestricted" else "○ Still restricted"
                nextButton.text = if (hasBatteryExemption()) "Next" : "Open battery settings"
                skipButton.text = "Skip for now"
                skipButton.visibility = android.view.View.VISIBLE
            }
            STEP_ENROLL -> {
                stepTitle.text = "5 · Enroll your face"
                stepBody.text = "Look at the front camera while it takes a few photos from slightly " +
                    "different angles.\n\n" +
                    "Good light helps. Sunglasses off, please."
                stepStatus.text = if (prefs.faceEnrolled)
                    "✓ Face enrolled (${prefs.enrolledSampleCount} samples)"
                else "○ Not enrolled yet"
                nextButton.text = if (prefs.faceEnrolled) "Next" else "Start enrollment"
                skipButton.visibility = android.view.View.GONE
            }
            STEP_WALLPAPER -> {
                stepTitle.text = "6 · Back up your wallpaper"
                stepBody.text = "When an intruder is caught, their photo becomes your wallpaper so " +
                    "you see it the moment you return.\n\n" +
                    "We'll save your current wallpaper now so we can put it back the second YOU " +
                    "unlock with your face."
                stepStatus.text = if (prefs.wallpaperBackedUp) "✓ Saved" else "○ Not saved yet"
                nextButton.text = if (prefs.wallpaperBackedUp) "Next" else "Back up now"
                skipButton.visibility = android.view.View.GONE
            }
            STEP_DONE -> {
                stepTitle.text = "You're protected"
                stepBody.text = "Protection is armed.\n\n" +
                    "Next unlock → quiet face check.\n" +
                    "Not you → full capture + wallpaper swap.\n" +
                    "You → wallpaper restored + notification with everything captured.\n\n" +
                    "Open the app any time to see the decision log and test your face."
                stepStatus.text = "✓ Setup complete"
                nextButton.text = "Enter WhoAmI"
                skipButton.visibility = android.view.View.GONE
            }
        }
    }

    private fun statusLine(l1: String, ok1: Boolean, l2: String, ok2: Boolean): String {
        val a = (if (ok1) "✓ " else "○ ") + l1
        val b = (if (ok2) "✓ " else "○ ") + l2
        return "$a    ·    $b"
    }

    // --- permission checks ------------------------------------------------

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun allRuntimeGranted() = runtimePermissions.all { hasPermission(it) }

    private fun hasOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasBatteryExemption(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    companion object {
        private const val STEP_WELCOME = 0
        private const val STEP_PERMISSIONS = 1
        private const val STEP_OVERLAY = 2
        private const val STEP_USAGE = 3
        private const val STEP_BATTERY = 4
        private const val STEP_ENROLL = 5
        private const val STEP_WALLPAPER = 6
        private const val STEP_DONE = 7
    }
}
