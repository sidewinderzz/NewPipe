/*
 * SubsPipe: background indexing of feed videos for smart search.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.subspipe

import android.content.Context
import android.util.Log
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.atomic.AtomicBoolean
import org.jsoup.Jsoup
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.util.ExtractorHelper

/**
 * Builds the smart-search index for videos in the subscription feed:
 * 1. every video gets an embedding of its title and channel name right away (offline, fast);
 * 2. then, newest first, the video's description is fetched and the embedding is
 *    recomputed from title + channel + description, so search can match what the
 *    video is actually about. This is rate limited to go easy on YouTube.
 */
object SmartSearchIndexer {
    private const val TAG = "SmartSearchIndexer"

    /** How many descriptions to fetch per feed refresh. The rest follow on later refreshes. */
    private const val MAX_DESCRIPTION_FETCHES_PER_RUN = 400

    /** Pause between description fetches. */
    private const val DELAY_BETWEEN_FETCHES_MS = 500L

    /** Stop a run after this many failures in a row (probably offline or throttled). */
    private const val MAX_CONSECUTIVE_FAILURES = 5

    /** Longest description kept per video. */
    private const val MAX_DESCRIPTION_CHARS = 1500

    /** Whether to fetch video descriptions, or only index titles. */
    @Volatile
    var fetchDescriptions = true

    private val running = AtomicBoolean(false)

    fun indexInBackground(context: Context, streams: List<StreamEntity>) {
        if (streams.isEmpty() || !running.compareAndSet(false, true)) {
            return
        }
        val appContext = context.applicationContext
        val snapshot = streams.toList()
        Completable.fromAction { index(appContext, snapshot) }
            .subscribeOn(Schedulers.io())
            .doFinally { running.set(false) }
            .subscribe({}, { Log.w(TAG, "Smart search indexing failed", it) })
    }

    private fun baseText(stream: StreamEntity) = "${stream.title}. ${stream.uploader}"

    private fun index(context: Context, streams: List<StreamEntity>) {
        if (!SmartSearchEmbedder.isAvailable(context)) {
            return
        }
        val store = SmartSearchStore.get(context)

        // 1. titles: quick and offline
        for (stream in streams) {
            if (store.get(stream.url) == null) {
                SmartSearchEmbedder.embed(context, baseText(stream))
                    ?.let { store.put(stream.url, it, null) }
            }
        }

        if (!fetchDescriptions) {
            return
        }

        // 2. descriptions, newest first (the feed list is already newest first)
        var fetched = 0
        var consecutiveFailures = 0
        for (stream in streams) {
            if (fetched >= MAX_DESCRIPTION_FETCHES_PER_RUN ||
                consecutiveFailures >= MAX_CONSECUTIVE_FAILURES
            ) {
                break
            }
            if (store.get(stream.url)?.hasDescription == true) {
                continue
            }
            fetched++
            try {
                val info = ExtractorHelper.getStreamInfo(stream.serviceId, stream.url, false)
                    .blockingGet()
                val description = plainText(info.description).take(MAX_DESCRIPTION_CHARS)
                val embedding = SmartSearchEmbedder.embed(
                    context, baseText(stream) + "\n" + description
                )
                if (embedding != null) {
                    store.put(stream.url, embedding, description)
                }
                consecutiveFailures = 0
            } catch (e: Exception) {
                consecutiveFailures++
                Log.w(TAG, "Could not fetch description for ${stream.url}", e)
            }
            Thread.sleep(DELAY_BETWEEN_FETCHES_MS)
        }
    }

    private fun plainText(description: Description?): String {
        if (description == null) {
            return ""
        }
        val content = description.content()
        return if (description.type() == Description.Type.HTML) {
            Jsoup.parse(content).text()
        } else {
            content
        }
    }
}
