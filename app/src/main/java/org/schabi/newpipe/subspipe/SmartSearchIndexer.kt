/*
 * SubsPipe: background work that keeps feed search data up to date.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.subspipe

import android.content.Context
import android.util.Log
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.atomic.AtomicBoolean
import org.jsoup.Jsoup
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.feed.service.FeedEventManager
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.ListHelper

/**
 * Keeps two things up to date for feed search:
 * 1. topic tags for every subscribed channel, written by an AI model once per channel
 *    (only when an AI key is set in Settings > Smart search);
 * 2. video descriptions, fetched newest first, only on Wi-Fi, and rate limited to go easy
 *    on YouTube.
 */
object SmartSearchIndexer {
    private const val TAG = "SmartSearchIndexer"

    /** How many descriptions to fetch per feed refresh. The rest follow on later refreshes. */
    private const val MAX_DESCRIPTION_FETCHES_PER_RUN = 150

    /** Pause between description fetches. */
    private const val DELAY_BETWEEN_FETCHES_MS = 500L

    /** Stop a run after this many failures in a row (probably offline or throttled). */
    private const val MAX_CONSECUTIVE_FAILURES = 5

    /** Longest description kept per video. */
    private const val MAX_DESCRIPTION_CHARS = 1500

    /** Channels sent to the AI per request. */
    private const val CHANNELS_PER_REQUEST = 20

    /** Recent titles sent per channel. */
    private const val TITLES_PER_CHANNEL = 15

    /** Pause between AI requests, to stay inside free-tier rate limits. */
    private const val DELAY_BETWEEN_AI_REQUESTS_MS = 4_000L

    /** Why the last tagging run failed, shown in Settings and the feed's help dialog. */
    @Volatile
    var lastTagError: String? = null
        private set

    private val running = AtomicBoolean(false)
    private val tagging = AtomicBoolean(false)

    fun indexInBackground(context: Context, streams: List<StreamEntity>) {
        if (streams.isEmpty() || !running.compareAndSet(false, true)) {
            return
        }
        val appContext = context.applicationContext
        val snapshot = streams.toList()
        Single.fromCallable {
            if (ChannelTagger.apiKey(appContext) != null && tagging.compareAndSet(false, true)) {
                try {
                    tagChannels(appContext, snapshot)
                } finally {
                    tagging.set(false)
                }
            }
            fetchDescriptions(appContext, snapshot)
        }
            .subscribeOn(Schedulers.io())
            .doFinally { running.set(false) }
            .subscribe({}, { Log.w(TAG, "Smart search indexing failed", it) })
    }

    /**
     * Tags channels now (from Settings). With [redo], AI-written tags are replaced too;
     * tags you edited yourself are always kept.
     * @param done called on the main thread with how many channels got tags, or null on error
     */
    fun tagChannelsNow(context: Context, redo: Boolean, done: (Int?) -> Unit) {
        val appContext = context.applicationContext
        if (!tagging.compareAndSet(false, true)) {
            done(0)
            return
        }
        Single.fromCallable {
            if (redo) {
                SmartSearchStore.get(appContext).clearAiTags()
            }
            val streams = FeedDatabaseManager(appContext)
                .getStreams(FeedGroupEntity.GROUP_ALL_ID, true, true, true)
                .blockingGet(emptyList())
                .map { it.stream }
            tagChannels(appContext, streams)
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doFinally { tagging.set(false) }
            .subscribe({ done(if (lastTagError == null) it else null) }, {
                lastTagError = it.message
                done(null)
            })
    }

    /** @return how many channels got new tags */
    private fun tagChannels(context: Context, streams: List<StreamEntity>): Int {
        val apiKey = ChannelTagger.apiKey(context) ?: return 0
        val store = SmartSearchStore.get(context)
        val subscriptions = try {
            NewPipeDatabase.getInstance(context).subscriptionDAO().getAll().blockingFirst()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read subscriptions", e)
            return 0
        }
        val titlesByChannel = streams
            .groupBy { it.uploaderUrl?.let(SmartSearchStore::channelKey) }
            .mapValues { (_, channelStreams) ->
                channelStreams.take(TITLES_PER_CHANNEL).map { it.title }
            }

        val untagged = subscriptions.mapNotNull { subscription ->
            val url = subscription.url ?: return@mapNotNull null
            if (store.tags(url) != null) {
                return@mapNotNull null
            }
            ChannelTagger.Channel(
                url = url,
                name = subscription.name.orEmpty(),
                about = subscription.description.orEmpty(),
                titles = titlesByChannel[SmartSearchStore.channelKey(url)].orEmpty()
            )
        }

        lastTagError = null
        var tagged = 0
        for ((index, batch) in untagged.chunked(CHANNELS_PER_REQUEST).withIndex()) {
            if (index > 0) {
                Thread.sleep(DELAY_BETWEEN_AI_REQUESTS_MS)
            }
            try {
                ChannelTagger.tag(apiKey, batch).forEach { (url, tags) ->
                    store.putTags(url, tags, SmartSearchStore.TagSource.AI)
                    tagged++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not tag channels", e)
                lastTagError = e.message ?: e.javaClass.simpleName
                break
            }
        }
        return tagged
    }

    private fun fetchDescriptions(context: Context, streams: List<StreamEntity>) {
        val store = SmartSearchStore.get(context)
        var fetched = 0
        var consecutiveFailures = 0
        // newest first (the feed list is already newest first)
        for (stream in streams) {
            if (fetched >= MAX_DESCRIPTION_FETCHES_PER_RUN ||
                consecutiveFailures >= MAX_CONSECUTIVE_FAILURES ||
                // never compete with a feed refresh for the network; carry on next time
                FeedEventManager.isLoading() ||
                // only on Wi-Fi (or another unmetered network), to save mobile data and battery
                ListHelper.isMeteredNetwork(context)
            ) {
                break
            }
            if (store.description(stream.url) != null) {
                continue
            }
            fetched++
            try {
                val info = ExtractorHelper.getStreamInfo(stream.serviceId, stream.url, false)
                    .blockingGet()
                store.putDescription(
                    stream.url,
                    plainText(info.description).take(MAX_DESCRIPTION_CHARS)
                )
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
