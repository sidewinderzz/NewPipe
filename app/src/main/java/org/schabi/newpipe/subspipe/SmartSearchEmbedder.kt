/*
 * SubsPipe: on-device text embeddings for meaning-based search.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.subspipe

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder

/**
 * Wraps a small on-device sentence model (Universal Sentence Encoder, run through
 * MediaPipe). It turns a piece of text into a list of numbers (an "embedding") so that
 * texts about similar ideas end up close together, even when they share no words.
 *
 * The model file is downloaded into the app's assets by the CI build. If it is missing
 * (e.g. a local build without it) [isAvailable] is false and search falls back to keywords.
 */
object SmartSearchEmbedder {
    private const val TAG = "SmartSearchEmbedder"
    const val MODEL_ASSET = "universal_sentence_encoder.tflite"

    /** Longest text passed to the model; longer text is cut off. */
    private const val MAX_CHARS = 2000

    private var embedder: TextEmbedder? = null
    private var initFailed = false

    /** Why the model could not be loaded, shown in the feed's help dialog. */
    @Volatile
    var loadError: String? = null
        private set

    @Synchronized
    private fun get(context: Context): TextEmbedder? {
        if (embedder != null || initFailed) {
            return embedder
        }
        try {
            context.assets.open(MODEL_ASSET).close()
            val options = TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                .setL2Normalize(true)
                .build()
            embedder = TextEmbedder.createFromOptions(context.applicationContext, options)
        } catch (e: Throwable) {
            Log.w(TAG, "Smart search model unavailable, using keyword search", e)
            loadError = "${e.javaClass.simpleName}: ${e.message}".take(300)
            initFailed = true
        }
        return embedder
    }

    fun isAvailable(context: Context): Boolean = get(context) != null

    /**
     * @return a normalized embedding for [text] (so a dot product equals cosine
     * similarity), or null if the model is not available
     */
    @Synchronized
    fun embed(context: Context, text: String): FloatArray? {
        val model = get(context) ?: return null
        val input = text.trim().take(MAX_CHARS)
        if (input.isEmpty()) {
            return null
        }
        return try {
            model.embed(input).embeddingResult().embeddings()[0].floatEmbedding()
        } catch (e: Throwable) {
            Log.w(TAG, "Could not embed text", e)
            null
        }
    }

    fun similarity(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var dot = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]
        }
        return dot
    }
}
