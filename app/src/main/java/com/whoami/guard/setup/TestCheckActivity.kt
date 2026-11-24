package com.whoami.guard.setup

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
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
 * One-shot face check: snap a front photo, compare against the enrolled face,
 * show MATCH / NO MATCH with the raw score. Used during setup and for tuning.
 */
class TestCheckActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var resultText: TextView
    private lateinit var scoreText: TextView
    private lateinit var checkButton: Button

    private lateinit var engine: FaceEngine
    private lateinit var store: FaceStore
    private lateinit var prefs: AppPrefs

    private val executor = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private var busy = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_check)

        previewView = findViewById(R.id.testPreview)
        resultText = findViewById(R.id.testResult)
        scoreText = findViewById(R.id.testScore)
        checkButton = findViewById(R.id.testCheckButton)

        engine = FaceEngine(this)
        store = FaceStore(this)
        prefs = AppPrefs(this)

        findViewById<Button>(R.id.testClose).setOnClickListener { finish() }
        checkButton.setOnClickListener { runCheck() }

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
            resultText.text = "Tap the button to check"
            scoreText.text = ""
        }, ContextCompat.getMainExecutor(this))
    }

    private fun runCheck() {
        if (busy) return
        busy = true
        checkButton.isEnabled = false
        resultText.text = "Checking…"

        imageCapture?.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    executor.execute {
                        try {
                            val bitmap = image.toBitmap()
                            val rotation = image.imageInfo.rotationDegrees
                            val upright = if (rotation != 0) {
                                val m = Matrix().apply {
                                    postRotate(rotation.toFloat())
                                }
                                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
                            } else bitmap

                            val analysis = engine.analyze(upright)
                            if (upright !== bitmap) upright.recycle()
                            bitmap.recycle()

                            val enrollment = store.load()
                            runOnUiThread {
                                when {
                                    enrollment == null -> {
                                        resultText.text = "NO FACE ENROLLED"
                                        scoreText.text = "Run setup first"
                                    }
                                    !analysis.faceFound -> {
                                        resultText.text = "NO FACE FOUND"
                                        scoreText.text = "Move into frame"
                                    }
                                    analysis.embedding == null -> {
                                        resultText.text = "CAN'T READ FACE"
                                        scoreText.text = "Try better light"
                                    }
                                    else -> {
                                        val best = enrollment.embeddings
                                            .maxOf { engine.similarity(analysis.embedding!!, it) }
                                        val match = best >= prefs.matchThreshold
                                        resultText.text = if (match) "MATCH — it's you" else "NO MATCH"
                                        scoreText.text = String.format(
                                            "score %.3f  ·  threshold %.2f",
                                            best, prefs.matchThreshold
                                        )
                                    }
                                }
                                busy = false
                                checkButton.isEnabled = true
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                resultText.text = "ERROR: ${e.message}"
                                busy = false
                                checkButton.isEnabled = true
                            }
                        } finally {
                            image.close()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        resultText.text = "CAPTURE FAILED: ${exception.message}"
                        busy = false
                        checkButton.isEnabled = true
                    }
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        engine.close()
    }
}
