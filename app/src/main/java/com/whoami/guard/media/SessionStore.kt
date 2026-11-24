package com.whoami.guard.media

import android.content.Context
import android.graphics.Bitmap
import com.whoami.guard.log.DecisionLog
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Session folders under filesDir/sessions/ — app-private, invisible to the
 * gallery and to other apps.
 *
 * sessions/2026-11-24_14-30-00/
 *   front.jpg  back.jpg  front_video.mp4  back_video.mp4
 *   session_audio.m4a  [session_audio2.m4a]  meta.json
 */
object SessionStore {

    private const val DIR_NAME = "sessions"
    private const val KEEP_LAST = 50
    private const val TS_FORMAT = "yyyy-MM-dd_HH-mm-ss"

    fun sessionsDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** Create an empty timestamped session folder; returns it. */
    fun createSession(context: Context, trigger: DecisionLog.Trigger): File {
        val ts = SimpleDateFormat(TS_FORMAT, Locale.US).format(Date())
        var dir = File(sessionsDir(context), ts)
        var n = 1
        while (dir.exists()) dir = File(sessionsDir(context), "${ts}_$n")
        dir.mkdirs()
        File(dir, "meta.json").writeText(
            JSONObject()
                .put("trigger", trigger.label)
                .put("started_at", System.currentTimeMillis())
                .toString()
        )
        enforceLimit(context)
        return dir
    }

    /** Most recent sessions, newest first. */
    fun listSessions(context: Context): List<File> =
        runCatching {
            sessionsDir(context)
                .listFiles { f -> f.isDirectory }
                ?.sortedByDescending { it.name }
                ?: emptyList()
        }.getOrDefault(emptyList())

    fun saveJpeg(dir: File, name: String, bitmap: Bitmap, quality: Int = 92): Boolean =
        runCatching {
            val out = File(dir, name)
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            true
        }.getOrDefault(false)

    fun sessionFile(dir: File, name: String): File = File(dir, name)

    fun hasAnythingCaptured(dir: File): Boolean =
        dir.listFiles()?.any { it.name != "meta.json" && it.length() > 0 } == true

    /** Stamp session end + item list into meta.json. */
    fun finalizeSession(dir: File, extra: Map<String, Any> = emptyMap()) {
        runCatching {
            val metaFile = File(dir, "meta.json")
            val meta = if (metaFile.exists()) JSONObject(metaFile.readText()) else JSONObject()
            meta.put("finished_at", System.currentTimeMillis())
            meta.put(
                "items",
                dir.listFiles()?.filter { it.name != "meta.json" && it.length() > 0 }
                    ?.joinToString(",") { it.name } ?: ""
            )
            extra.forEach { (k, v) -> meta.put(k, v) }
            metaFile.writeText(meta.toString())
        }
    }

    /** Keep only the newest [KEEP_LAST] sessions. */
    fun enforceLimit(context: Context) {
        runCatching {
            val dirs = sessionsDir(context)
                .listFiles { f -> f.isDirectory }
                ?.sortedByDescending { it.name } ?: return
            if (dirs.size > KEEP_LAST) {
                dirs.drop(KEEP_LAST).forEach { dead ->
                    dead.deleteRecursively()
                    DecisionLog.append(
                        context, DecisionLog.Trigger.MANUAL,
                        DecisionLog.Outcome.INFO,
                        "old session pruned: ${dead.name}"
                    )
                }
            }
        }
    }

    fun totalBytes(context: Context): Long = runCatching {
        sessionsDir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)
}
