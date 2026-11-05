/*
 * SubsPipe: ranking feed videos for a search query.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.subspipe

import android.content.Context
import java.util.Locale
import kotlin.math.ln
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.stream.StreamWithState

/**
 * Searches only the videos in your subscription feed.
 *
 * Each video is matched on five pieces of text, most important first: its title, its
 * channel's topic tags, the channel name, the video description and the channel's
 * "about" text. Channel tags are what make a topic search work: "woodworking" finds
 * every video of a channel tagged woodworking, even if a title never says it.
 *
 * Rare words count for more than common ones, and a video has to match most of what
 * you typed to show up.
 */
object SmartSearch {
    private const val TITLE_WEIGHT = 3f
    private const val TAG_WEIGHT = 2.5f
    private const val CHANNEL_NAME_WEIGHT = 2f
    private const val DESCRIPTION_WEIGHT = 1f
    private const val ABOUT_WEIGHT = 1f

    /** Share of the query (by word importance) a video must match to show up. */
    private const val MIN_COVERAGE = 0.6f

    private const val MAX_RESULTS = 300

    private val STOP_WORDS = setOf(
        "a", "an", "and", "the", "of", "to", "in", "on", "for", "with", "how", "what",
        "is", "are", "my", "about", "videos", "video", "or", "at", "by", "from"
    )

    private class Fields(
        val title: String,
        val tags: String,
        val channelName: String,
        val description: String,
        val about: String
    )

    fun search(context: Context, items: List<StreamWithState>, query: String): List<StreamWithState> {
        val terms = searchTerms(query)
        if (terms.isEmpty()) {
            return items
        }
        val store = SmartSearchStore.get(context)
        val aboutByChannel = channelAbouts(context)
        val matchers = terms.map { matcherFor(it) }

        // weights[i][t]: the best field weight with which item i matched term t (0 = no match)
        val weights = items.map { item ->
            val stream = item.stream
            val channelKey = stream.uploaderUrl?.let(SmartSearchStore::channelKey)
            val fields = Fields(
                title = stream.title.lowercase(Locale.ROOT),
                tags = store.tags(stream.uploaderUrl)?.tags?.joinToString(" | ").orEmpty(),
                channelName = stream.uploader.lowercase(Locale.ROOT),
                description = store.description(stream.url).orEmpty(),
                about = aboutByChannel[channelKey].orEmpty()
            )
            FloatArray(terms.size) { bestWeight(fields, matchers[it]) }
        }

        // rarer words count for more (so "old" in "fixing old tractors" matters less)
        val idf = FloatArray(terms.size) { t ->
            val matching = weights.count { it[t] > 0f }
            ln(1f + items.size.toFloat() / (1f + matching))
        }
        val totalIdf = idf.sum()

        return items.indices
            .mapNotNull { i ->
                var covered = 0f
                var score = 0f
                for (t in terms.indices) {
                    if (weights[i][t] > 0f) {
                        covered += idf[t]
                        score += idf[t] * weights[i][t]
                    }
                }
                if (covered > 0f && covered / totalIdf >= MIN_COVERAGE) i to score else null
            }
            // stable sort: equally good matches stay newest first
            .sortedByDescending { it.second }
            .take(MAX_RESULTS)
            .map { items[it.first] }
    }

    private fun bestWeight(fields: Fields, matches: (String) -> Boolean): Float = when {
        matches(fields.title) -> TITLE_WEIGHT
        matches(fields.tags) -> TAG_WEIGHT
        matches(fields.channelName) -> CHANNEL_NAME_WEIGHT
        matches(fields.description) -> DESCRIPTION_WEIGHT
        matches(fields.about) -> ABOUT_WEIGHT
        else -> 0f
    }

    /**
     * Matches a search word loosely: "tractors" also finds "tractor", "welding" also
     * finds "welder". Short words must match a whole word, so "art" doesn't hit "start".
     */
    private fun matcherFor(term: String): (String) -> Boolean {
        val stem = stem(term)
        if (stem.length >= 4) {
            return { text -> text.contains(stem) }
        }
        val regex = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(stem) + "(?![\\p{L}\\p{N}])")
        return { text -> regex.containsMatchIn(text) }
    }

    private fun stem(term: String): String {
        val candidate = when {
            term.endsWith("ing") -> term.dropLast(3)
            term.endsWith("ies") -> term.dropLast(3) + "y"
            term.endsWith("es") && term.length > 5 -> term.dropLast(2)
            term.endsWith("s") && !term.endsWith("ss") -> term.dropLast(1)
            else -> term
        }
        return if (candidate.length >= 4) candidate else term
    }

    private fun searchTerms(query: String): List<String> = query
        .lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}']+"))
        .map { it.trim('\'') }
        .filter { it.isNotEmpty() && it !in STOP_WORDS }
        .distinct()

    /** Channel "about" texts, keyed by [SmartSearchStore.channelKey]. */
    private fun channelAbouts(context: Context): Map<String, String> = try {
        NewPipeDatabase.getInstance(context).subscriptionDAO().getAll().blockingFirst()
            .mapNotNull { sub ->
                val url = sub.url ?: return@mapNotNull null
                SmartSearchStore.channelKey(url) to sub.description.orEmpty().lowercase(Locale.ROOT)
            }
            .toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    /** A one-paragraph status for the help dialog and settings, so problems show on the phone. */
    fun statusText(context: Context): String {
        val (described, tagged) = SmartSearchStore.get(context).counts()
        val subscriptions = try {
            NewPipeDatabase.getInstance(context).subscriptionDAO().getAll().blockingFirst().size
        } catch (e: Exception) {
            0
        }
        val key = ChannelTagger.apiKey(context)
        val keyLine = if (key == null) {
            "No AI key set, so channels don't get topic tags. Add one in Settings > Smart search."
        } else {
            "AI key: ${ChannelTagger.provider(key).label}."
        }
        val error = SmartSearchIndexer.lastTagError?.let { "\nLast tagging error: $it" }.orEmpty()
        return "Smart search: $tagged of $subscriptions channels have topic tags, " +
            "$described videos have descriptions.\n$keyLine$error"
    }
}
