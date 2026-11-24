package com.whoami.guard.media

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Backs up the owner's current wallpaper so we can restore it the instant
 * they unlock with their own face again.
 *
 * Everything heavy runs on a background executor ([backupAsync]): reading a
 * 4K wallpaper and PNG-compressing it on the UI thread was what froze setup
 * step 6. Now: JPEG (fast), downscaled to ≤4096px, with stream fallback for
 * drawables that can't be copied directly, plus a live-wallpaper-safe error
 * result instead of a silent failure.
 *
 * Files: filesDir/wallpaper_backup.jpg
 */
object WallpaperKeeper {

    private const val TAG = "WallpaperKeeper"
    private const val FILE_NAME = "wallpaper_backup.jpg"
    private const val LEGACY_PNG = "wallpaper_backup.png"
    private const val MAX_EDGE = 4096
    private const val JPEG_QUALITY = 92

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class BackupResult(val ok: Boolean, val message: String)

    /** Runs off the main thread; callback arrives on the main thread. */
    fun backupAsync(context: Context, callback: (BackupResult) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val result = runCatching { backupBlocking(app) }
                .getOrElse { t ->
                    Log.e(TAG, "backup failed", t)
                    BackupResult(false, "${t.javaClass.simpleName}: ${t.message}")
                }
            mainHandler.post { callback(result) }
        }
    }

    /** Blocking version — call from a background thread only. */
    fun backupBlocking(context: Context): BackupResult {
        val wm = WallpaperManager.getInstance(context)

        // Path 1: the standard drawable (works for static wallpapers).
        var source: Bitmap? = null
        val drawable = try { wm.drawable } catch (t: Throwable) { null }
        if (drawable != null) {
            source = drawable.toBitmapSafely()
        }

        // Path 2: raw wallpaper file stream (some OEMs / edge cases).
        if (source == null) {
            source = try {
                val pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
                pfd?.use { fd ->
                    fd.fileDescriptor.let { fis ->
                        java.io.FileInputStream(fis).use { input ->
                            BitmapFactory.decodeStream(input)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "wallpaper file stream failed", t)
                null
            }
        }

        if (source == null) {
            // Live/animated wallpaper with no static copy — we can't snapshot it.
            return BackupResult(
                false,
                "couldn't copy this wallpaper (live wallpaper?). " +
                    "Skip — after an intrusion we'll reset to the system default."
            )
        }

        val srcW = source.width
        val srcH = source.height
        val bitmap = downscale(source, MAX_EDGE)
        val out = File(context.filesDir, FILE_NAME)
        val ok = try {
            FileOutputStream(out).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
            }
            out.length() > 0
        } finally {
            if (bitmap !== source) bitmap.recycle()
            // Recycle only bitmaps we created (drawable-owned bitmaps stay alive).
            if (source !== bitmap && drawable == null) source.recycle()
            if (drawable != null && drawable !is BitmapDrawable) source.recycle()
        }

        // Clean up the old slow PNG backup if it exists.
        File(context.filesDir, LEGACY_PNG).delete()

        return if (ok) {
            Log.i(TAG, "Wallpaper backed up: ${out.length()} bytes (${srcW}x${srcH})")
            BackupResult(true, "saved (${srcW}×${srcH})")
        } else {
            BackupResult(false, "couldn't write the backup file")
        }
    }

    fun hasBackup(context: Context): Boolean =
        File(context.filesDir, FILE_NAME).exists() ||
            File(context.filesDir, LEGACY_PNG).exists()

    /**
     * Restore the saved wallpaper. If no backup exists (live wallpaper that
     * couldn't be copied at setup), paints a neutral dark wallpaper so the
     * intruder's face is at least gone — per spec, wallpaper must come back.
     * Returns true when the screen no longer shows the intruder photo.
     */
    fun restore(context: Context): Boolean = runCatching {
        val app = context.applicationContext
        val wm = WallpaperManager.getInstance(app)

        val file = listOf(FILE_NAME, LEGACY_PNG)
            .map { File(app.filesDir, it) }
            .firstOrNull { it.exists() }

        if (file != null) {
            val bytes = file.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), MAX_EDGE)
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return false
            wm.setBitmap(bitmap)
            bitmap.recycle()
            Log.i(TAG, "Wallpaper restored from ${file.name}")
        } else {
            // No backup (was a live wallpaper) → neutral dark background.
            val neutral = Bitmap.createBitmap(1080, 2280, Bitmap.Config.ARGB_8888)
            neutral.eraseColor(android.graphics.Color.rgb(20, 20, 28))
            wm.setBitmap(neutral)
            neutral.recycle()
            Log.i(TAG, "Wallpaper reset to neutral (no backup existed)")
        }
        true
    }.onFailure {
        Log.e(TAG, "Wallpaper restore failed", it)
    }.getOrDefault(false)

    /** Set an arbitrary bitmap (e.g. the intruder's captured face) as wallpaper. */
    fun setAsWallpaper(context: Context, bitmap: Bitmap): Boolean = runCatching {
        val scaled = downscale(bitmap, MAX_EDGE)
        WallpaperManager.getInstance(context).setBitmap(scaled)
        if (scaled !== bitmap) scaled.recycle()
        true
    }.getOrDefault(false)

    // --- helpers ----------------------------------------------------------

    /** Async wrapper for restore (runs off the main thread, posts to main). */
    fun restoreAsync(context: Context, callback: (Boolean) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val ok = restore(app)
            mainHandler.post { callback(ok) }
        }
    }

    /** Async wrapper for setAsWallpaper. */
    fun setAsWallpaperAsync(context: Context, bitmap: Bitmap, callback: (Boolean) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val ok = setAsWallpaper(app, bitmap)
            mainHandler.post { callback(ok) }
        }
    }

    private fun downscale(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun sampleSize(widthOrHeight: Int, maxEdge: Int): Int {
        var size = 1
        while (widthOrHeight / size > maxEdge) size *= 2
        return size
    }

    private fun Drawable.toBitmapSafely(): Bitmap? = try {
        when {
            this is BitmapDrawable && bitmap != null -> bitmap!!
            intrinsicWidth > 0 && intrinsicHeight > 0 -> {
                val w = intrinsicWidth.coerceAtMost(MAX_EDGE)
                val h = intrinsicHeight.coerceAtMost(MAX_EDGE)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(bmp).also { canvas ->
                    setBounds(0, 0, canvas.width, canvas.height)
                    draw(canvas)
                }
                bmp
            }
            else -> null
        }
    } catch (t: Throwable) {
        Log.w(TAG, "drawable→bitmap failed", t)
        null
    }
}
