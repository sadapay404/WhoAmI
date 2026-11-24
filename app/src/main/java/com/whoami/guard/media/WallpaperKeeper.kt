package com.whoami.guard.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.app.WallpaperManager
import java.io.File
import java.io.FileOutputStream

/**
 * Backs up the owner's current wallpaper so we can restore it the instant
 * they unlock with their own face again.
 *
 * File: filesDir/wallpaper_backup.png
 */
object WallpaperKeeper {

    private const val TAG = "WallpaperKeeper"
    private const val FILE_NAME = "wallpaper_backup.png"

    fun backup(context: Context): Boolean = runCatching {
        val wm = WallpaperManager.getInstance(context)
        val drawable = wm.drawable ?: run {
            Log.w(TAG, "WallpaperManager returned null drawable")
            return false
        }
        val bitmap = drawable.toBitmap()
        val out = File(context.filesDir, FILE_NAME)
        FileOutputStream(out).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        if (bitmap !== drawable.toBitmap()) bitmap.recycle()
        Log.i(TAG, "Wallpaper backed up: ${out.length()} bytes")
        true
    }.onFailure {
        Log.e(TAG, "Wallpaper backup failed", it)
    }.getOrDefault(false)

    fun hasBackup(context: Context): Boolean = File(context.filesDir, FILE_NAME).exists()

    /** Restore the saved wallpaper. Returns true on success. */
    fun restore(context: Context): Boolean = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return false
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            ?: return false
        val wm = WallpaperManager.getInstance(context)
        wm.setBitmap(bitmap)
        bitmap.recycle()
        Log.i(TAG, "Wallpaper restored")
        true
    }.onFailure {
        Log.e(TAG, "Wallpaper restore failed", it)
    }.getOrDefault(false)

    /** Set an arbitrary bitmap (e.g. the intruder's captured face) as wallpaper. */
    fun setAsWallpaper(context: Context, bitmap: Bitmap): Boolean = runCatching {
        WallpaperManager.getInstance(context).setBitmap(bitmap)
        true
    }.getOrDefault(false)

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable && bitmap != null) return bitmap!!
        val w = if (intrinsicWidth > 0) intrinsicWidth else 1080
        val h = if (intrinsicHeight > 0) intrinsicHeight else 2340
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bmp
    }
}
