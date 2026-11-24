package com.whoami.guard.setup

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
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
 * The quiet check: a fully transparent, input-transparent activity that
 * verifies the owner's face and vanishes.
 *
 * Rules (per owner spec):
 *  1. If the OWNER'S face is among the detected faces → MATCH — no capture,
 *     even when friends/others are in the same photo (group unlock).
 *  2. If the owner is not seen, keep sampling the front camera for up to
 *     [GRACE_MS] (10 s). The moment the owner appears → MATCH.
 *  3. If the owner still hasn't appeared after ≥10 s → capture (subject to
 *     the "capture after grace" toggle; default ON).
 *
 * The window is FLAG_NOT_TOUCHABLE so touches fall through to the app
 * behind us for the whole grace period — nobody feels a frozen screen.
 */
class QuietCheckActivity : AppCompatActivity() {

    private lateinit var engine: FaceEngine
    private lateinit var store: FaceStore
    private lateinit var prefs: AppPrefs

    private var imageCapture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null

    @Volatile private var decided = false
    @Volatile private var shotInFlight = false
    @Volatile private var analysisRunning = false
    @Volatile private var finishWhenDone = false
    private var trigger = DecisionLog.Trigger.MANUAL

    private var startedAt = 0L
    private var attempts = 0
    private var bestScoreSeen = Float.NEGATIVE_INFINITY

    /** Frame kept for front.jpg if capture fires: most faces seen, latest on ties. */
    private var storedFrame: Bitmap? = null
    private var storedFaceCount = -1

    private val mainHandler = Handler(Looper.getMainLooper())
    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No layout: transparent activity, camera does the work.

        trigger = runCatching {
            DecisionLog.Trigger.valueOf(intent.getStringExtra(EXTRA_TRIGGER) ?: "MANUAL")
        }.getOrDefault(DecisionLog.Trigger.MANUAL)

        prefs = AppPrefs(this)

        // Touches fall through to the app behind us — nobody sees a frozen UI
        // while we wait out the grace period.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            finishSoon()
            return
        }

        engine = FaceEngine(this)
        store = FaceStore(this)
        GuardServiceBridge.checkActive = true

        startedAt = System.currentTimeMillis()
        DecisionLog.append(
            this, trigger, DecisionLog.Outcome.INFO,
            "check started (grace ${GRACE_MS / 1000}s)"
        )

        // Abort quietly if the user locks the phone mid-check — nobody there,
        // nothing to decide. Next unlock will check again.
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF && !decided) {
                    DecisionLog.append(
                        this@QuietCheckActivity, trigger,
                        DecisionLog.Outcome.INFO, "grace aborted: screen locked"
                    )
                    finishSoon()
                }
            }
        }
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        // Hard timeout — never leave a zombie activity on screen.
        mainHandler.postDelayed({ forceDecision("hard-timeout") }, HARD_TIMEOUT_MS)

        bindCamera()
    }

    // --- camera loop ------------------------------------------------------

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                p.unbindAll()
                p.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, imageCapture
                )
                takeShot()
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

    private fun takeShot() {
        if (decided || shotInFlight || isFinishing) return
        shotInFlight = true
        attempts++

        imageCapture?.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    analysisRunning = true
                    Thread {
                        try {
                            analyzeFrame(image)
                        } finally {
                            image.close()
                            analysisRunning = false
                            // If teardown was requested mid-analysis, do it now.
                            if (finishWhenDone) runOnUiThread { realFinish() }
                        }
                    }.start()
                }

                override fun onError(exception: ImageCaptureException) {
                    shotInFlight = false
                    Log.e(TAG, "capture failed", exception)
                    // Transient camera hiccup during grace → just retry.
                    if (!decided && System.currentTimeMillis() - startedAt < GRACE_MS) {
                        mainHandler.postDelayed({ takeShot() }, RETRY_DELAY_MS)
                    } else {
                        DecisionLog.append(
                            this@QuietCheckActivity, trigger,
                            DecisionLog.Outcome.ERROR, "camera: ${exception.message}"
                        )
                        forceDecision("camera-error")
                    }
                }
            }
        )
    }

    private fun analyzeFrame(image: ImageProxy) {
        if (decided) {
            shotInFlight = false
            return
        }
        shotInFlight = false

        try {
            val bitmap = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            val upright = if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            } else bitmap
            if (upright !== bitmap) bitmap.recycle()

            val enrollment = store.load()
            if (enrollment == null) {
                DecisionLog.append(
                    this, trigger, DecisionLog.Outcome.ERROR, "no face enrolled"
                )
                upright.recycle()
                forceDecision("not-enrolled")
                return
            }

            // Rule 1: check EVERY face in the photo — owner among friends = MATCH.
            val hits = engine.analyzeAll(upright)

            // Analysis took a while — if the activity was torn down meanwhile
            // (hard timeout / screen off), don't act on a dead window.
            if (decided) {
                upright.recycle()
                return
            }

            // Keep the most informative frame for a possible front.jpg:
            // most faces seen; latest wins ties; if nobody ever seen, keep latest.
            if (hits.size >= storedFaceCount) {
                storedFrame?.recycle()
                storedFrame = downscale(upright, STORED_FRAME_MAX_EDGE)
                storedFaceCount = hits.size
            }
            val ownerBest = if (hits.isEmpty()) -1f
            else hits.maxOf { engine.bestMatch(it.embedding, enrollment.embeddings) }
            if (ownerBest > bestScoreSeen) bestScoreSeen = ownerBest
            upright.recycle()

            if (ownerBest >= prefs.matchThreshold) {
                decided = true
                DecisionLog.append(
                    this, trigger, DecisionLog.Outcome.MATCH,
                    String.format(
                        "score=%.3f thr=%.2f faces=%d attempt=%d",
                        ownerBest, prefs.matchThreshold, hits.size, attempts
                    )
                )
                onOwner()
                finishSoon()
                return
            }

            // Rule 2: owner not seen yet — wait out the grace period.
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed < GRACE_MS) {
                mainHandler.postDelayed({ takeShot() }, RETRY_DELAY_MS)
                return
            }

            // Rule 3: ≥10s, owner never appeared → capture (or allow, if toggled off).
            decided = true
            val detail = String.format(
                "no owner in %.1fs (best=%.3f, faces=%d, attempts=%d)",
                elapsed / 1000.0, bestScoreSeen.coerceAtLeast(-1f), hits.size, attempts
            )
            if (prefs.uncertainPolicy == AppPrefs.POLICY_CAPTURE) {
                DecisionLog.append(this, trigger, DecisionLog.Outcome.NO_MATCH, detail)
                onIntruder(trigger, storedFrame)
            } else {
                DecisionLog.append(this, trigger, DecisionLog.Outcome.UNCERTAIN, "$detail → allowed")
                releaseStoredFrame()
            }
            finishSoon()
        } catch (e: Exception) {
            Log.e(TAG, "analysis failed", e)
            DecisionLog.append(
                this, trigger, DecisionLog.Outcome.ERROR,
                "analysis: ${e.message}"
            )
            forceDecision("analysis-exception")
        }
    }

    /** Timeout / error path: decide with what we have. */
    private fun forceDecision(reason: String) {
        if (decided) return
        decided = true
        val elapsed = System.currentTimeMillis() - startedAt
        DecisionLog.append(
            this, trigger, DecisionLog.Outcome.UNCERTAIN,
            "check ended early (${reason}, %.1fs)".format(elapsed / 1000.0)
        )
        // NOTE: storedFrame is left alone here — the analysis thread may be
        // using it. onDestroy() releases it exactly once.
        finishSoon()
    }

    // --- outcomes ---------------------------------------------------------

    private fun onOwner() {
        // If we were in intruder mode (wallpaper swapped), bring the real one back.
        releaseStoredFrame()
        if (prefs.wallpaperSwapped) {
            val restored = WallpaperKeeper.restore(this)
            prefs.wallpaperSwapped = false
            if (restored) {
                AlertNotifier.notifyRestored(this)
                DecisionLog.append(
                    this, trigger, DecisionLog.Outcome.INFO,
                    "wallpaper restored for owner"
                )
            }
        }
    }

    private fun onIntruder(trigger: DecisionLog.Trigger, frontBitmap: Bitmap?) {
        GuardServiceBridge.lastIntruderTrigger = trigger
        GuardServiceBridge.captureActive = true

        // 1. Open a session folder and stash the front photo (best frame from the grace loop).
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
        // storedFrame recycled by onDestroy via releaseStoredFrame()
    }

    // --- teardown ---------------------------------------------------------

    private fun releaseStoredFrame() {
        storedFrame?.recycle()
        storedFrame = null
    }

    private fun finishSoon() {
        mainHandler.removeCallbacksAndMessages(null)
        // Never tear down the engine while the analysis thread is inside it —
        // defer until the in-flight analysis finishes.
        if (analysisRunning) {
            finishWhenDone = true
            return
        }
        runOnUiThread { realFinish() }
    }

    private fun realFinish() {
        runCatching { screenReceiver?.let { unregisterReceiver(it) } }
        screenReceiver = null
        runCatching { provider?.unbindAll() }
        if (!::engine.isInitialized || !analysisRunning) {
            runCatching { engine.close() }
        }
        if (!isFinishing) {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { screenReceiver?.let { unregisterReceiver(it) } }
        runCatching { provider?.unbindAll() }
        if (!analysisRunning) runCatching { if (::engine.isInitialized) engine.close() }
        releaseStoredFrame()
        GuardServiceBridge.checkActive = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "QuietCheck"
        const val EXTRA_TRIGGER = "trigger"

        /** Owner has this long to appear before capture fires (spec: "10 seconds at least"). */
        private const val GRACE_MS = 10_000L
        private const val RETRY_DELAY_MS = 500L
        private const val HARD_TIMEOUT_MS = 16_000L

        /** Stored frames are downscaled — plenty for wallpaper + front.jpg, cheap in RAM. */
        private const val STORED_FRAME_MAX_EDGE = 2400

        private fun downscale(src: Bitmap, maxEdge: Int): Bitmap {
            val longest = maxOf(src.width, src.height)
            if (longest <= maxEdge) return src
            val scale = maxEdge.toFloat() / longest
            val out = Bitmap.createScaledBitmap(
                src, (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1), true
            )
            src.recycle()
            return out
        }
    }
}

/** Tiny bridge so activities and services can share state. */
object GuardServiceBridge {
    @Volatile var captureActive: Boolean = false
    @Volatile var checkActive: Boolean = false
    @Volatile var lastIntruderTrigger: DecisionLog.Trigger? = null
}
