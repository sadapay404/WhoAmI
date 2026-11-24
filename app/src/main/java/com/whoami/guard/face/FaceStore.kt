package com.whoami.guard.face

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stores the owner's enrolled face embeddings in app-private storage.
 * File: filesDir/face_store.json — unreadable by other apps (and by the gallery).
 */
class FaceStore(context: Context) {

    private val file = File(context.filesDir, "face_store.json")

    data class Enrollment(
        val embeddings: List<FloatArray>,
        val enrolledAt: Long
    )

    fun save(embeddings: List<FloatArray>): Boolean = runCatching {
        val arr = JSONArray()
        embeddings.forEach { emb ->
            val a = JSONArray()
            emb.forEach { a.put(it.toDouble()) }
            arr.put(a)
        }
        val json = JSONObject()
            .put("version", 1)
            .put("enrolledAt", System.currentTimeMillis())
            .put("embeddings", arr)
        file.writeText(json.toString())
        true
    }.getOrDefault(false)

    fun load(): Enrollment? = runCatching {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val arr = json.getJSONArray("embeddings")
        val embeddings = (0 until arr.length()).map { i ->
            val a = arr.getJSONArray(i)
            FloatArray(a.length()) { j -> a.getDouble(j).toFloat() }
        }
        if (embeddings.isEmpty()) null
        else Enrollment(embeddings, json.optLong("enrolledAt", 0L))
    }.getOrNull()

    fun clear() {
        if (file.exists()) file.delete()
    }

    fun isEnrolled(): Boolean = load() != null

    /**
     * Best cosine similarity between [probe] and every enrolled sample.
     * Returns -1 when nothing is enrolled.
     */
    fun bestMatch(probe: FloatArray, engine: FaceEngine): Float {
        val enrollment = load() ?: return -1f
        return enrollment.embeddings.maxOf { engine.similarity(probe, it) }
    }
}
