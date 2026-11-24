package com.whoami.guard.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * On-device face engine.
 *
 * Pipeline:  Bitmap → ML Kit (find faces) → crop → 112×112 → MobileFaceNet → 192-dim embedding.
 * Everything runs offline; no network calls, ever.
 */
class FaceEngine(context: Context) {

    private val interpreter: Interpreter
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    )

    init {
        interpreter = Interpreter(loadModel(context), Interpreter.Options().apply { setNumThreads(4) })
    }

    /** Result of analyzing one photo for a single (largest) face — used by enrollment. */
    data class Analysis(
        val faceFound: Boolean,
        val embedding: FloatArray?,
        val faceBox: Rect?,
        val qualityOk: Boolean
    )

    /**
     * One detected face with its embedding.
     * Group-shot matching checks EVERY face against the owner's enrollment.
     */
    data class FaceHit(
        val embedding: FloatArray,
        val box: Rect,
        /** face min-side / image min-side, 0..1 — roughly how prominent the face is */
        val sizeFrac: Float
    )

    /** Detect ALL faces big enough to be a real person in frame, embed each. Blocking — background thread only. */
    fun analyzeAll(bitmap: Bitmap, maxFaces: Int = 8): List<FaceHit> {
        val faces = detectFaces(bitmap)
            .sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
            .take(maxFaces)

        val imgMin = minOf(bitmap.width, bitmap.height).toFloat()
        val hits = ArrayList<FaceHit>(faces.size)
        for (face in faces) {
            val box = padBox(face.boundingBox, bitmap.width, bitmap.height)
            val minSide = minOf(box.width(), box.height())
            // Too small = background noise; a real person near the phone is always bigger.
            if (minSide < imgMin * 0.04f) continue
            val cropped = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
            val emb = embed(cropped)
            cropped.recycle()
            hits.add(FaceHit(emb, face.boundingBox, minSide / imgMin))
        }
        return hits
    }

    /**
     * Find the most prominent face in [bitmap] and compute its embedding.
     * Blocking (ML Kit Tasks.await) — invoke from a background executor only.
     */
    fun analyze(bitmap: Bitmap): Analysis {
        val faces = detectFaces(bitmap)

        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            ?: return Analysis(false, null, null, false)

        val box = padBox(face.boundingBox, bitmap.width, bitmap.height)
        val cropped = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
        val emb = embed(cropped)
        cropped.recycle()

        return Analysis(true, emb, face.boundingBox, qualityOk(face, box, bitmap))
    }

    /** Compute a 192-dim embedding for an already-cropped face image. */
    fun embed(faceBitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
        val input = bitmapToBuffer(resized)
        if (resized !== faceBitmap) resized.recycle()

        val output = Array(1) { FloatArray(EMBEDDING_SIZE) }
        interpreter.run(input, output)
        return normalize(output[0])
    }

    /** Cosine similarity between two embeddings, in [-1, 1]. Higher = more alike. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb) + 1e-6f)
    }

    /**
     * Best cosine score of [probe] against every enrolled sample.
     * Returns -1 when nothing is enrolled.
     */
    fun bestMatch(probe: FloatArray, enrolled: List<FloatArray>): Float {
        if (enrolled.isEmpty()) return -1f
        return enrolled.maxOf { similarity(probe, it) }
    }

    fun close() {
        interpreter.close()
        faceDetector.close()
    }

    // --- internals -------------------------------------------------------

    private fun detectFaces(bitmap: Bitmap): List<Face> = runCatching {
        com.google.android.gms.tasks.Tasks.await(
            faceDetector.process(InputImage.fromBitmap(bitmap, 0))
        )
    }.getOrDefault(emptyList())

    private fun qualityOk(face: Face, box: Rect, source: Bitmap): Boolean {
        // Reject tiny faces (someone in the far background) and dead-dark shots.
        val minSide = minOf(box.width(), box.height())
        if (minSide < source.width * 0.12f) return false
        val leftEye = face.leftEyeOpenProbability ?: 0.5f
        val rightEye = face.rightEyeOpenProbability ?: 0.5f
        return leftEye > 0.3f && rightEye > 0.3f
    }

    private fun padBox(box: Rect, imgW: Int, imgH: Int): Rect {
        val pad = (maxOf(box.width(), box.height()) * 0.15f).toInt()
        val left = (box.left - pad).coerceAtLeast(0)
        val top = (box.top - pad).coerceAtLeast(0)
        val right = (box.right + pad).coerceAtMost(imgW)
        val bottom = (box.bottom + pad).coerceAtMost(imgH)
        return Rect(left, top, right, bottom)
    }

    private fun bitmapToBuffer(bmp: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bmp.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (px in pixels) {
            // MobileFaceNet normalization: (pixel / 128) - 1
            buf.putFloat(((px shr 16 and 0xFF) / 128f) - 1f) // R
            buf.putFloat(((px shr 8 and 0xFF) / 128f) - 1f)  // G
            buf.putFloat(((px and 0xFF) / 128f) - 1f)        // B
        }
        buf.rewind()
        return buf
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum) + 1e-6f
        for (i in v.indices) v[i] /= norm
        return v
    }

    private fun loadModel(context: Context): ByteBuffer {
        val fd = context.assets.openFd("mobilefacenet.tflite")
        FileInputStream(fd.fileDescriptor).use { stream ->
            val channel = stream.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    companion object {
        const val INPUT_SIZE = 112
        const val EMBEDDING_SIZE = 192
    }
}
