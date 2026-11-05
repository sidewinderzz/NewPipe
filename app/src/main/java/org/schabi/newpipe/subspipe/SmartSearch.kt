/*
 * SubsPipe: ranking feed videos for a search query.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.subspipe

import android.content.Context
import java.util.Locale
import org.schabi.newpipe.database.stream.StreamWithState

/**
 * Searches only the videos in your subscription feed.
 *
 * With the on-device model available, results are ranked by meaning (how close the
 * query is to the video's title, channel and description) plus a bonus for matching
 * words. Without the model it falls back to plain keyword matching.
 */
object SmartSearch {
    /** Minimum meaning-similarity for a video to show up without matching all words. */
    private const val MIN_SIMILARITY = 0.2f

    /**
     * How much a channel's overall topic counts. A search for a topic a channel is all
     * about brings up that channel's videos even if a title doesn't mention it.
     */
    private const val CHANNEL_WEIGHT = 0.9f

    /** Extra score for matching the typed words, scaled by the share of words matched. */
    private const val KEYWORD_WEIGHT = 0.35f

    private const val MAX_RESULTS = 150

    fun search(context: Context, items: List<StreamWithState>, query: String): List<StreamWithState> {
        val terms = searchTerms(query)
        if (terms.isEmpty()) {
            return items
        }

        val queryEmbedding = SmartSearchEmbedder.embed(context, query)
            ?: return items.filter { keywordScore(it, terms) == 1f }

        val store = SmartSearchStore.get(context)
        val channelTopics = channelTopicSimilarities(items, store, queryEmbedding)
        return items
            .mapNotNull { item ->
                val keyword = keywordScore(item, terms)
                val videoSimilarity = store.get(item.stream.url)?.embedding
                    ?.let { SmartSearchEmbedder.similarity(queryEmbedding, it) } ?: 0f
                val channelSimilarity = channelTopics[item.stream.uploaderUrl] ?: 0f
                val similarity = maxOf(videoSimilarity, CHANNEL_WEIGHT * channelSimilarity)
                if (keyword == 1f || similarity >= MIN_SIMILARITY) {
                    // the small video-level term orders a matching channel's own videos
                    item to similarity + 0.2f * videoSimilarity + KEYWORD_WEIGHT * keyword
                } else {
                    null
                }
            }
            .sortedByDescending { it.second }
            .take(MAX_RESULTS)
            .map { it.first }
    }

    /**
     * For each channel in the list, how close the query is to what the channel is about.
     * Two signals, the stronger wins:
     * - the channel's own name + "about" text,
     * - the average of all its videos' embeddings (the channel's overall topic, which
     *   works even when the about text is empty).
     */
    private fun channelTopicSimilarities(
        items: List<StreamWithState>,
        store: SmartSearchStore,
        queryEmbedding: FloatArray
    ): Map<String?, Float> = items
        .groupBy { it.stream.uploaderUrl }
        .mapValues { (channelUrl, channelItems) ->
            val profile = store.getChannel(channelUrl)
                ?.let { SmartSearchEmbedder.similarity(queryEmbedding, it) } ?: 0f
            val centroid = averageOf(channelItems.mapNotNull { store.get(it.stream.url)?.embedding })
                ?.let { SmartSearchEmbedder.similarity(queryEmbedding, it) } ?: 0f
            maxOf(profile, centroid)
        }

    /** Normalized average of embeddings, or null if there are none. */
    private fun averageOf(embeddings: List<FloatArray>): FloatArray? {
        if (embeddings.isEmpty()) {
            return null
        }
        val size = embeddings[0].size
        val sum = FloatArray(size)
        for (embedding in embeddings) {
            for (i in 0 until minOf(size, embedding.size)) {
                sum[i] += embedding[i]
            }
        }
        val norm = kotlin.math.sqrt(sum.fold(0f) { acc, v -> acc + v * v })
        if (norm == 0f) {
            return null
        }
        for (i in sum.indices) {
            sum[i] /= norm
        }
        return sum
    }

    /** A one-paragraph status for the help dialog, so problems can be spotted on the phone. */
    fun statusText(context: Context): String {
        if (!SmartSearchEmbedder.isAvailable(context)) {
            return "Smart search: OFF, using word matching only.\n" +
                (SmartSearchEmbedder.loadError ?: "Model file missing from this build.")
        }
        val (videos, described, channels) = SmartSearchStore.get(context).counts()
        return "Smart search: ON. Indexed $videos videos ($described with descriptions) " +
            "and $channels channels."
    }

    private fun searchTerms(query: String): List<String> = query
        .lowercase(Locale.getDefault())
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    /** Share of the query words that appear in the title or channel name (0 to 1). */
    private fun keywordScore(item: StreamWithState, terms: List<String>): Float {
        val haystack = "${item.stream.title} ${item.stream.uploader}".lowercase(Locale.getDefault())
        return terms.count { haystack.contains(it) }.toFloat() / terms.size
    }
}
