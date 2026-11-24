package com.whoami.guard.setup

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.whoami.guard.R
import com.whoami.guard.face.FaceEngine
import com.whoami.guard.face.FaceStore
import com.whoami.guard.prefs.AppPrefs
import java.util.concurrent.Executors

/**
 * Captures several front-camera samples and stores the owner's face embeddings.
 * Takes ~8 samples over a few seconds while the user slowly moves their head.
 */
class EnrollFaceActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView

    private lateinit var engine: FaceEngine
    private lateinit var store: FaceStore
    private lateinit var prefs: AppPrefs

    private val captureExecutor = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private val embeddings = mutableListOf<FloatArray>()
    private var capturing = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                Toast.makeText(this, "Camera permission is required to enroll your face", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enroll)

        previewView = findViewById(R.id.enrollPreview)
        statusText = findViewById(R.id.enrollStatus)
        progressText = findViewById(R.id.enrollProgress)

        engine = FaceEngine(this)
        store = FaceStore(this)
        prefs = AppPrefs(this)

        findViewById<android.widget.Button>(R.id.enrollCancel).setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture
            )

            statusText.text = "Look at the camera… hold still"
            capturing = true
            captureExecutor.execute { captureLoop() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureLoop() {
        while (capturing && embeddings.size < TARGET_SAMPLES && !isFinishing) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var analyzed = false

            runOnUiThread {
                imageCapture?.takePicture(
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            captureExecutor.execute {
                                try {
                                    analyzed = processImage(image)
                                } finally {
                                    image.close()
                                    latch.countDown()
                                }
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            latch.countDown()
                        }
                    }
                )
            }

            latch.await()
            val delay = if (analyzed) 400L else 700L
            Thread.sleep(delay)
        }

        // Done — report back on the main thread.
        runOnUiThread {
            capturing = false
            if (embeddings.size >= MIN_SAMPLES) {
                store.save(embeddings)
                prefs.faceEnrolled = true
                prefs.enrolledSampleCount = embeddings.size
                setResult(RESULT_OK)
                Toast.makeText(
                    this,
                    "Face enrolled with ${embeddings.size} samples",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } else {
                statusText.text = "Couldn't get a clear read — try again in better light"
                progressText.text = "${embeddings.size}/$MIN_SAMPLES good samples"
                // Allow retry: restart capture if user stays on screen
                capturing = true
                captureExecutor.execute { captureLoop() }
            }
        }
    }

    private fun processImage(image: ImageProxy): Boolean {
        val bitmap = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        val upright = if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        } else bitmap

        val analysis = runCatching { engine.analyze(upright) }.getOrNull()
        if (upright !== bitmap) upright.recycle()
        bitmap.recycle()

        if (analysis == null || !analysis.faceFound || !analysis.qualityOk || analysis.embedding == null) {
            runOnUiThread { statusText.text = "Face not clear — look straight at the camera" }
            return false
        }

        embeddings.add(analysis.embedding)
        val n = embeddings.size
        runOnUiThread {
            progressText.text = "$n/$TARGET_SAMPLES good samples"
            statusText.text = when {
                n < 4 -> "Good — now slowly turn your head left"
                n < 7 -> "Nice — now turn your head slightly right"
                else -> "Almost done — look straight again"
            }
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        capturing = false
        captureExecutor.shutdownNow()
        engine.close()
    }

    companion object {
        private const val TARGET_SAMPLES = 8
        private const val MIN_SAMPLES = 5
    }
}
