package com.whoami.guard.setup

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.whoami.guard.face.FaceEngine
import com.whoami.guard.face.FaceStore
import com.whoami.guard.log.DecisionLog
import com.whoami.guard.media.WallpaperKeeper
import com.whoami.guard.prefs.AppPrefs
import com.whoami.guard.notifications.AlertNotifier

/**
 * The quiet check: a fully transparent activity that grabs ONE front-camera
 * photo, decides me-vs-not-me, writes the decision to the log, and vanishes.
 *
 * No preview is bound — the activity window is see-through, so whatever the
 * user was doing (home screen, WhatsApp) stays visible behind it.
 * Target wall-clock: well under 2 seconds.
 */
class QuietCheckActivity : AppCompatActivity() {

    private lateinit var engine: FaceEngine
    private lateinit var store: FaceStore
    private lateinit var prefs: AppPrefs

    private var imageCapture: ImageCapture? = null
    private var decided = false
    private val timeout = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No layout: transparent activity, camera does the work.

        val triggerName = intent.getStringExtra(EXTRA_TRIGGER) ?: "MANUAL"
        val trigger = runCatching { DecisionLog.Trigger.valueOf(triggerName) }
            .getOrDefault(DecisionLog.Trigger.MANUAL)

        prefs = AppPrefs(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            finishSoon()
            return
        }

        engine = FaceEngine(this)
        store = FaceStore(this)

        // Hard timeout — never leave a zombie activity on screen.
        timeout.postDelayed({
            if (!decided) {
                DecisionLog.append(
                    this, trigger, DecisionLog.Outcome.ERROR,
                    "check timed out"
                )
                finishSoon()
            }
        }, TIMEOUT_MS)

        bindAndCapture(trigger)
    }

    private fun bindAndCapture(trigger: DecisionLog.Trigger) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, imageCapture
                )

                imageCapture?.takePicture(
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            // Analysis off the main thread.
                            Thread {
                                try {
                                    analyzeAndDecide(image, trigger)
                                } finally {
                                    image.close()
                                }
                            }.start()
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "capture failed", exception)
                            DecisionLog.append(
                                this@QuietCheckActivity, trigger,
                                DecisionLog.Outcome.ERROR,
                                "camera: ${exception.message}"
                            )
                            runOnUiThread { finishSoon() }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "bind failed", e)
                DecisionLog.append(
                    this, trigger, DecisionLog.Outcome.ERROR,
                    "bind: ${e.message}"
                )
                finishSoon()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeAndDecide(image: ImageProxy, trigger: DecisionLog.Trigger) {
        if (decided) return
        decided = true
        timeout.removeCallbacksAndMessages(null)

        try {
            val bitmap = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            val upright = if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            } else bitmap

            val analysis = engine.analyze(upright)
            val enrollment = store.load()
            when {
                enrollment == null -> {
                    DecisionLog.append(
                        this, trigger, DecisionLog.Outcome.ERROR,
                        "no face enrolled"
                    )
                }
                !analysis.faceFound || analysis.embedding == null -> {
                    // Could not see a face — apply the configured policy.
                    val policy = prefs.uncertainPolicy
                    DecisionLog.append(
                        this, trigger, DecisionLog.Outcome.UNCERTAIN,
                        "no clear face → policy=$policy"
                    )
                    if (policy == AppPrefs.POLICY_CAPTURE) {
                        onIntruder(trigger, upright)
                    }
                }
                else -> {
                    val emb = analysis.embedding
                    val best = enrollment.embeddings
                        .maxOf { engine.similarity(emb, it) }
                    val detail = String.format("score=%.3f thr=%.2f", best, prefs.matchThreshold)
                    if (best >= prefs.matchThreshold) {
                        DecisionLog.append(
                            this, trigger, DecisionLog.Outcome.MATCH, detail
                        )
                        onOwner()
                    } else {
                        DecisionLog.append(
                            this, trigger, DecisionLog.Outcome.NO_MATCH, detail
                        )
                        onIntruder(trigger, upright)
                    }
                }
            }
            if (upright !== bitmap) upright.recycle()
            bitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "analysis failed", e)
            DecisionLog.append(
                this, trigger, DecisionLog.Outcome.ERROR,
                "analysis: ${e.message}"
            )
        } finally {
            runOnUiThread { finishSoon() }
        }
    }

    private fun onOwner() {
        // If we were in intruder mode (wallpaper swapped), bring the real one back.
        if (prefs.wallpaperSwapped) {
            val restored = WallpaperKeeper.restore(this)
            prefs.wallpaperSwapped = false
            if (restored) {
                AlertNotifier.notifyRestored(this)
                DecisionLog.append(
                    this, DecisionLog.Trigger.MANUAL, DecisionLog.Outcome.INFO,
                    "wallpaper restored for owner"
                )
            }
        }
    }

    private fun onIntruder(trigger: DecisionLog.Trigger, frontBitmap: Bitmap?) {
        GuardServiceBridge.lastIntruderTrigger = trigger
        GuardServiceBridge.captureActive = true

        // 1. Open a session folder and stash the front photo (the check shot).
        val session = com.whoami.guard.media.SessionStore.createSession(this, trigger)
        if (frontBitmap != null) {
            com.whoami.guard.media.SessionStore.saveJpeg(session, "front.jpg", frontBitmap)
        }

        // 2. Swap wallpaper to the intruder's photo — instantly, per spec.
        if (frontBitmap != null) {
            val ok = WallpaperKeeper.setAsWallpaper(this, frontBitmap)
            prefs.wallpaperSwapped = ok
            DecisionLog.append(
                this, trigger, DecisionLog.Outcome.INFO,
                if (ok) "wallpaper swapped to intruder photo"
                else "wallpaper swap FAILED"
            )
        }

        // 3. Hand off to the capture pipeline (still on screen → legal FGS start).
        runCatching {
            com.whoami.guard.capture.CaptureService.start(this, session, trigger)
        }.onFailure { e ->
            Log.e(TAG, "capture service failed to start", e)
            GuardServiceBridge.captureActive = false
            DecisionLog.append(
                this, trigger, DecisionLog.Outcome.ERROR,
                "capture start: ${e.message}"
            )
        }
    }

    private fun finishSoon() {
        timeout.removeCallbacksAndMessages(null)
        runCatching { engine.close() }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        timeout.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "QuietCheck"
        const val EXTRA_TRIGGER = "trigger"
        private const val TIMEOUT_MS = 6_000L
    }
}

/** Tiny bridge so activities and future capture service can share state. */
object GuardServiceBridge {
    @Volatile var captureActive: Boolean = false
    @Volatile var lastIntruderTrigger: DecisionLog.Trigger? = null
}
