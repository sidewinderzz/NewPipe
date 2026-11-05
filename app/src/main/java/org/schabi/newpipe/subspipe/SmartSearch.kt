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
    private const val MIN_SIMILARITY = 0.3f

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
        return items
            .mapNotNull { item ->
                val keyword = keywordScore(item, terms)
                val embedding = store.get(item.stream.url)?.embedding
                val similarity = if (embedding != null) {
                    SmartSearchEmbedder.similarity(queryEmbedding, embedding)
                } else {
                    0f
                }
                if (keyword == 1f || similarity >= MIN_SIMILARITY) {
                    item to similarity + KEYWORD_WEIGHT * keyword
                } else {
                    null
                }
            }
            .sortedByDescending { it.second }
            .take(MAX_RESULTS)
            .map { it.first }
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
