package com.whoami.guard.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.whoami.guard.MainActivity
import com.whoami.guard.R
import com.whoami.guard.log.DecisionLog
import com.whoami.guard.setup.GuardServiceBridge
import com.whoami.guard.media.SessionStore
import java.io.File
import java.util.concurrent.Executors

/**
 * The intruder capture pipeline. Started (while an activity is visibly on
 * screen — an Android 14/15 requirement) the moment a face fails the check.
 *
 * Sequence:
 *   0. front photo — usually already saved by QuietCheck; captured here as fallback
 *   1. back photo
 *   2. 5s front video (no audio — mic belongs to session audio)
 *   3. 5s back video WITH audio (session audio pauses for those ~5s to guarantee
 *      both the video's audio track and a near-continuous session recording)
 *   4. session audio keeps going until SCREEN_OFF → session finalized
 *
 * Wallpaper is already swapped by QuietCheck before this service starts.
 */
class CaptureService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var sessionDir: File? = null
    private var triggerName: String = DecisionLog.Trigger.MANUAL.name
    private var needsSelfie = false

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var audioRecorder: MediaRecorder? = null
    private val audioSegments = mutableListOf<String>()

    private var screenReceiver: android.content.BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var finished = false
    private var stepIndex = 0
    private val watchdog = Runnable { advance("watchdog") }

    private val requiredAudio = arrayOf(
        Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
    )

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        GuardServiceBridge.captureActive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground immediately — Android gives us ~5s.
        promoteToForeground()

        val dirPath = intent?.getStringExtra(EXTRA_SESSION_DIR)
        sessionDir = dirPath?.let { File(it) } ?: SessionStore.createSession(
            this, parseTrigger(intent)
        )
        triggerName = intent?.getStringExtra(EXTRA_TRIGGER) ?: triggerName
        needsSelfie = intent?.getBooleanExtra(EXTRA_NEEDS_SELFIE, false) ?: false

        if (sessionDir?.exists() != true) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (sessionDir!!.listFiles()?.none { it.name == "front.jpg" } != false) {
            needsSelfie = true
        }

        registerScreenReceiver()
        acquireWakeHint()
        DecisionLog.append(
            this, parseTrigger(intent), DecisionLog.Outcome.INFO,
            "capture started → ${sessionDir?.name}"
        )

        // Permission sanity — without these the pipeline cannot run.
        val missing = requiredAudio.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            DecisionLog.append(
                this, parseTrigger(intent), DecisionLog.Outcome.ERROR,
                "missing permissions: $missing"
            )
            finalizeAndStop("missing-permissions")
            return START_NOT_STICKY
        }

        startSessionAudio()
        stepIndex = 0
        advance("start")
        return START_NOT_STICKY
    }

    // --- pipeline ---------------------------------------------------------

    private fun advance(reason: String) {
        mainHandler.removeCallbacks(watchdog)
        if (finished) return

        val steps = buildStepList()
        if (stepIndex >= steps.size) {
            // All captures done — sit tight until SCREEN_OFF.
            Log.i(TAG, "pipeline complete, waiting for lock ($reason)")
            return
        }
        val step = steps[stepIndex++]
        Log.i(TAG, "step → $step ($reason)")
        mainHandler.postDelayed(watchdog, step.timeoutMs)
        step.action()
    }

    private data class Step(val name: String, val timeoutMs: Long, val action: () -> Unit)

    private fun buildStepList(): List<Step> = buildList {
        if (needsSelfie) add(Step("front photo (selfie)", 9_000) { captureStill("front.jpg", front = true) })
        add(Step("back photo", 9_000) { captureStill("back.jpg", front = false) })
        add(Step("front video 5s", 12_000) { recordVideo("front_video.mp4", front = true, withAudio = false) })
        add(Step("back video 5s", 12_000) { recordVideo("back_video.mp4", front = false, withAudio = true) })
    }

    private fun captureStill(fileName: String, front: Boolean) {
        runCatching {
            val cameraProvider = ProcessCameraProvider.getInstance(this).get()
            provider = cameraProvider
            cameraProvider.unbindAll()

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture

            val selector =
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA

            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            cameraProvider.bindToLifecycle(this, selector, capture)

            capture.takePicture(
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val bmp = image.toBitmap()
                            val rot = image.imageInfo.rotationDegrees
                            val upright = if (rot != 0) {
                                val m = Matrix().apply { postRotate(rot.toFloat()) }
                                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                            } else bmp
                            val dir = sessionDir ?: return
                            val ok = SessionStore.saveJpeg(dir, fileName, upright)
                            if (upright !== bmp) upright.recycle()
                            bmp.recycle()
                            log(
                                if (ok) DecisionLog.Outcome.INFO
                                else DecisionLog.Outcome.ERROR,
                                "$fileName ${if (ok) "saved" else "FAILED"}"
                            )
                        } finally {
                            image.close()
                            unbindCamera()
                            advance("still-done")
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        log(DecisionLog.Outcome.ERROR, "$fileName: ${exception.message}")
                        unbindCamera()
                        advance("still-error")
                    }
                }
            )
        }.onFailure { e ->
            log(DecisionLog.Outcome.ERROR, "$fileName bind: ${e.message}")
            unbindCamera()
            advance("still-bind-error")
        }
    }

    private fun recordVideo(fileName: String, front: Boolean, withAudio: Boolean) {
        runCatching {
            val cameraProvider = ProcessCameraProvider.getInstance(this).get()
            provider = cameraProvider
            cameraProvider.unbindAll()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            val vc = VideoCapture.withOutput(recorder)
            videoCapture = vc

            val selector =
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA

            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            cameraProvider.bindToLifecycle(this, selector, vc)

            // Session audio must not fight the video for the mic.
            if (withAudio) pauseSessionAudio()

            val out = File(sessionDir, fileName)
            val options = FileOutputOptions.Builder(out).build()
            val builder = vc.output.prepareRecording(this, options)
            if (withAudio) builder.withAudioEnabled()

            var started = false
            val recording = builder.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        started = true
                        log(DecisionLog.Outcome.INFO, "$fileName recording…")
                        mainHandler.postDelayed({
                            runCatching { activeRecording?.stop() }
                        }, VIDEO_SECONDS * 1000)
                    }
                    is VideoRecordEvent.Finalize -> {
                        val ok = !event.hasError()
                        log(
                            if (ok) DecisionLog.Outcome.INFO else DecisionLog.Outcome.ERROR,
                            "$fileName ${if (ok) "saved (${out.length()} B)" else "error ${event.error}"}"
                        )
                        activeRecording = null
                        unbindCamera()
                        if (withAudio) resumeSessionAudio()
                        advance("video-done")
                    }
                    else -> {}
                }
            }
            activeRecording = recording
        }.recoverCatching { firstErr ->
            // Audio contention or permission wrinkle → retry silently once.
            log(DecisionLog.Outcome.ERROR, "$fileName first try: ${firstErr.message}")
            if (withAudio) {
                runCatching {
                    val vc = videoCapture ?: throw firstErr
                    val out = File(sessionDir, fileName)
                    out.delete()
                    val options = FileOutputOptions.Builder(out).build()
                    val recording = vc.output
                        .prepareRecording(this, options) // no audio this time
                        .start(ContextCompat.getMainExecutor(this)) { event ->
                            if (event is VideoRecordEvent.Finalize) {
                                log(
                                    if (!event.hasError()) DecisionLog.Outcome.INFO
                                    else DecisionLog.Outcome.ERROR,
                                    "$fileName (silent fallback) error=${event.error}"
                                )
                                activeRecording = null
                                unbindCamera()
                                resumeSessionAudio()
                                advance("video-retry-done")
                            }
                        }
                    activeRecording = recording
                }.onFailure { e ->
                    log(DecisionLog.Outcome.ERROR, "$fileName retry failed: ${e.message}")
                    resumeSessionAudio()
                    unbindCamera()
                    advance("video-error")
                }
            } else {
                unbindCamera()
                advance("video-error")
            }
        }
    }

    // --- session audio ----------------------------------------------------

    private fun startSessionAudio() {
        val dir = sessionDir ?: return
        val index = audioSegments.size + 1
        val name = if (index == 1) "session_audio.m4a" else "session_audio${index}.m4a"
        val file = SessionStore.sessionFile(dir, name)

        runCatching {
            val rec = if (android.os.Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            audioRecorder = rec
            audioSegments.add(name)
            log(DecisionLog.Outcome.INFO, "$name started")
        }.onFailure { e ->
            log(DecisionLog.Outcome.ERROR, "session audio: ${e.message}")
        }
    }

    private fun pauseSessionAudio() {
        audioRecorder?.let { rec ->
            runCatching {
                rec.stop()
                rec.release()
                log(DecisionLog.Outcome.INFO, "session audio paused")
            }
            audioRecorder = null
        }
    }

    private fun resumeSessionAudio() {
        if (audioRecorder == null && !finished) startSessionAudio()
    }

    private fun stopSessionAudioFinal() {
        audioRecorder?.let { rec ->
            runCatching {
                rec.stop()
                rec.release()
                log(DecisionLog.Outcome.INFO, "session audio stopped")
            }
            audioRecorder = null
        }
    }

    // --- teardown ---------------------------------------------------------

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    finalizeAndStop("screen-off")
                }
            }
        }
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    private fun acquireWakeHint() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhoAmI:capture").apply {
            setReferenceCounted(false)
            acquire(60_000) // just enough to finalize files on lock
        }
    }

    private fun finalizeAndStop(reason: String) {
        if (finished) return
        finished = true
        GuardServiceBridge.captureActive = false

        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacks(watchdog)

        runCatching {
            activeRecording?.stop()
            activeRecording = null
        }
        pauseSessionAudio()
        unbindCamera()

        val dir = sessionDir
        if (dir != null) {
            SessionStore.finalizeSession(
                dir,
                mapOf(
                    "audio_segments" to audioSegments.joinToString(","),
                    "stop_reason" to reason
                )
            )
            val captured = SessionStore.hasAnythingCaptured(dir)
            log(
                if (captured) DecisionLog.Outcome.INFO else DecisionLog.Outcome.ERROR,
                "session ended ($reason) captured=$captured"
            )
        }

        runCatching { screenReceiver?.let { unregisterReceiver(it) } }
        screenReceiver = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        stopSelf()
    }

    private fun unbindCamera() {
        runCatching {
            provider?.unbindAll()
        }
        imageCapture = null
        videoCapture = null
        if (lifecycleRegistry.currentState == Lifecycle.State.RESUMED) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }

    override fun onDestroy() {
        // System-initiated teardown safety net (timeout, swipe-away, crash).
        if (!finished) {
            finished = true
            GuardServiceBridge.captureActive = false
            runCatching { activeRecording?.stop() }
            pauseSessionAudio()
            sessionDir?.let { SessionStore.finalizeSession(it, mapOf("stop_reason" to "destroy")) }
        }
        runCatching { screenReceiver?.let { unregisterReceiver(it) } }
        unbindCamera()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cameraExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- plumbing ---------------------------------------------------------

    private fun promoteToForeground() {
        ensureChannel()
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("WhoAmI")
            .setContentText("Media session in progress")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()

        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: SecurityException) {
            // Shouldn't happen (setup grants both) — degrade rather than crash.
            log(DecisionLog.Outcome.ERROR, "FGS type denied: ${e.message}")
            runCatching {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
        } catch (e: Exception) {
            log(DecisionLog.Outcome.ERROR, "FGS start: ${e.message}")
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Protection", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun parseTrigger(intent: Intent?): DecisionLog.Trigger {
        val name = intent?.getStringExtra(EXTRA_TRIGGER) ?: return DecisionLog.Trigger.MANUAL
        return runCatching { DecisionLog.Trigger.valueOf(name) }
            .getOrDefault(DecisionLog.Trigger.MANUAL)
    }

    private fun log(outcome: DecisionLog.Outcome, detail: String) {
        DecisionLog.append(
            this,
            runCatching { DecisionLog.Trigger.valueOf(triggerName) }
                .getOrDefault(DecisionLog.Trigger.MANUAL),
            outcome, detail
        )
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "guard"
        private const val NOTIFICATION_ID = 102
        private const val VIDEO_SECONDS = 5L

        const val EXTRA_SESSION_DIR = "session_dir"
        const val EXTRA_TRIGGER = "trigger"
        const val EXTRA_NEEDS_SELFIE = "needs_selfie"

        /**
         * Must be called while an activity of this app is visibly on screen
         * (Android 14/15 background-start rules for camera/mic FGS types).
         */
        fun start(context: Context, sessionDir: File, trigger: DecisionLog.Trigger) {
            val intent = Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_SESSION_DIR, sessionDir.absolutePath)
                .putExtra(EXTRA_TRIGGER, trigger.name)
                .putExtra(EXTRA_NEEDS_SELFIE, false)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Test path: service captures everything itself (incl. front photo). */
        fun startSimulated(context: Context, trigger: DecisionLog.Trigger) {
            val session = SessionStore.createSession(context, trigger)
            val intent = Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_SESSION_DIR, session.absolutePath)
                .putExtra(EXTRA_TRIGGER, trigger.name)
                .putExtra(EXTRA_NEEDS_SELFIE, true)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
