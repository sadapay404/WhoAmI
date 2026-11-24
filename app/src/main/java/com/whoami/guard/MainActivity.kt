package com.whoami.guard

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        homeStatus.text = "v$version · fully offline"

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
    }

    override fun onResume() {
        super.onResume()
        if (prefs.setupComplete && ::statusLine.isInitialized) render()
    }

    private fun render() {
        val face = if (prefs.faceEnrolled)
            "✓ Face enrolled (${prefs.enrolledSampleCount} samples)" else "○ Face NOT enrolled"
        val wall = if (prefs.wallpaperBackedUp)
            "✓ Wallpaper backed up" else "○ Wallpaper NOT backed up"
        val protect = if (prefs.faceEnrolled && prefs.wallpaperBackedUp)
            "✓ Ready — protection armed on next Phase 3 build" else "○ Setup incomplete"

        statusLine.text = "$protect\n$face\n$wall"
        homeNote.text = "Phase 2 complete: setup wizard, face enrollment, wallpaper backup.\n\n" +
            "Detection (quiet checks on unlock/WhatsApp) arrives in Phase 3, " +
            "capture in Phase 4, restore + gallery in Phase 5."
    }
}
