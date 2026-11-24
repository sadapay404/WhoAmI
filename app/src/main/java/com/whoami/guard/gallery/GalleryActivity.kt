package com.whoami.guard.gallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.whoami.guard.R
import com.whoami.guard.log.DecisionLog
import com.whoami.guard.media.SessionStore
import com.whoami.guard.media.WallpaperKeeper
import com.whoami.guard.prefs.AppPrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists captured sessions (newest first). Tapping a session expands its items;
 * each media item opens with the system viewer/player via FileProvider.
 *
 * Also hosts the "restore my wallpaper NOW" escape hatch.
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var storageText: TextView
    private lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        prefs = AppPrefs(this)
        container = findViewById(R.id.sessionList)
        emptyText = findViewById(R.id.galleryEmpty)
        storageText = findViewById(R.id.galleryStorage)

        findViewById<Button>(R.id.galleryBack).setOnClickListener { finish() }

        val restoreBtn = findViewById<Button>(R.id.galleryRestore)
        if (prefs.wallpaperSwapped) {
            restoreBtn.visibility = View.VISIBLE
            restoreBtn.setOnClickListener {
                val ok = WallpaperKeeper.restore(this)
                prefs.wallpaperSwapped = false
                Toast.makeText(
                    this,
                    if (ok) "Wallpaper restored" else "Couldn't restore wallpaper",
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) {
                    DecisionLog.append(
                        this, DecisionLog.Trigger.MANUAL,
                        DecisionLog.Outcome.INFO, "wallpaper restored manually"
                    )
                }
                finish()
            }
        } else {
            restoreBtn.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        val sessions = SessionStore.listSessions(this)

        emptyText.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE

        val mb = SessionStore.totalBytes(this) / (1024.0 * 1024.0)
        storageText.text = String.format(
            Locale.US, "%d session(s) · %.1f MB · keeps last 50",
            sessions.size, mb
        )

        val tsFmt = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
        for (dir in sessions) {
            container.addView(sessionRow(dir, tsFmt))
        }
    }

    private fun sessionRow(dir: File, tsFmt: SimpleDateFormat): View {
        val layout = layoutInflater.inflate(R.layout.item_session, container, false)

        val title = layout.findViewById<TextView>(R.id.sessionTitle)
        val meta = layout.findViewById<TextView>(R.id.sessionMeta)
        val items = layout.findViewById<LinearLayout>(R.id.sessionItems)

        val started = dir.lastModified()
        title.text = tsFmt.format(Date(started))

        val files = dir.listFiles { f -> f.isFile && f.name != "meta.json" }
            ?.sortedBy { it.name } ?: emptyList()

        val trigger = readMetaField(dir, "trigger")
        val audio = files.count { it.name.startsWith("session_audio") }
        meta.text = String.format(
            Locale.US, "%s · %d file(s)%s",
            trigger, files.size,
            if (audio > 0) " · audio ✓" else ""
        )

        // Expand items on tap
        items.visibility = View.GONE
        layout.findViewById<TextView>(R.id.sessionChevron).setOnClickListener {
            items.visibility = if (items.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        title.setOnClickListener {
            items.visibility = if (items.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        for (f in files) {
            items.addView(mediaRow(f))
        }
        return layout
    }

    private fun mediaRow(file: File): View {
        val row = layoutInflater.inflate(R.layout.item_media, container, false)
        val nameView = row.findViewById<TextView>(R.id.mediaName)
        val openBtn = row.findViewById<Button>(R.id.mediaOpen)

        val kind = when {
            file.name.endsWith(".jpg") -> "📷"
            file.name.endsWith(".mp4") -> "🎬"
            file.name.endsWith(".m4a") -> "🎙"
            else -> "📄"
        }
        val kb = file.length() / 1024
        nameView.text = "$kind  ${file.name}  (${kb} KB)"

        openBtn.setOnClickListener { openMedia(file) }
        return row
    }

    private fun openMedia(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val mime = when {
                file.name.endsWith(".jpg") -> "image/jpeg"
                file.name.endsWith(".mp4") -> "video/mp4"
                file.name.endsWith(".m4a") -> "audio/mp4"
                else -> "*/*"
            }
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(view)
        } catch (e: Exception) {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun readMetaField(dir: File, key: String): String = runCatching {
        val f = File(dir, "meta.json")
        if (!f.exists()) return "session"
        org.json.JSONObject(f.readText()).optString(key, "session")
    }.getOrDefault("session")

    companion object {
        const val EXTRA_FROM_NOTIFICATION = "from_notification"
    }
}
