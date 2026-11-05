/*
 * SubsPipe: storage for video descriptions and channel topic tags used by search.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.subspipe

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * A small separate database (so the main NewPipe database and its migrations are left
 * untouched) holding each feed video's description and each channel's topic tags.
 * Everything is also kept in memory because every search looks at all of it.
 */
class SmartSearchStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    /** Who wrote a channel's tags. Tags you edited yourself are never overwritten. */
    enum class TagSource { AI, USER }

    data class ChannelTags(val tags: List<String>, val source: TagSource)

    /** Video URL to lowercase description ("" when the video has none). */
    private val descriptions = ConcurrentHashMap<String, String>()

    /** [channelKey] to its tags. */
    private val channelTags = ConcurrentHashMap<String, ChannelTags>()

    @Volatile
    private var cacheLoaded = false

    override fun onCreate(db: SQLiteDatabase) {
        createTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createTables(db)
        if (oldVersion < 3) {
            // versions 1 and 2 stored model embeddings; keep the fetched descriptions
            db.execSQL(
                "INSERT OR IGNORE INTO $VIDEO_TABLE ($COL_URL, $COL_DESCRIPTION, $COL_UPDATED) " +
                    "SELECT url, IFNULL(description, ''), updated FROM video_text " +
                    "WHERE has_description = 1"
            )
            db.execSQL("DROP TABLE IF EXISTS video_text")
            db.execSQL("DROP TABLE IF EXISTS channel_text")
        }
    }

    private fun createTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $VIDEO_TABLE (" +
                "$COL_URL TEXT PRIMARY KEY NOT NULL, " +
                "$COL_DESCRIPTION TEXT NOT NULL, " +
                "$COL_UPDATED INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TAG_TABLE (" +
                "$COL_CHANNEL TEXT PRIMARY KEY NOT NULL, " +
                "$COL_TAGS TEXT NOT NULL, " +
                "$COL_SOURCE TEXT NOT NULL, " +
                "$COL_UPDATED INTEGER NOT NULL)"
        )
    }

    @Synchronized
    private fun ensureCache() {
        if (cacheLoaded) {
            return
        }
        readableDatabase.query(
            VIDEO_TABLE,
            arrayOf(COL_URL, COL_DESCRIPTION),
            null,
            null,
            null,
            null,
            null
        ).use { c ->
            while (c.moveToNext()) {
                descriptions[c.getString(0)] = c.getString(1).lowercase()
            }
        }
        readableDatabase.query(
            TAG_TABLE,
            arrayOf(COL_CHANNEL, COL_TAGS, COL_SOURCE),
            null,
            null,
            null,
            null,
            null
        ).use { c ->
            while (c.moveToNext()) {
                val source = runCatching { TagSource.valueOf(c.getString(2)) }
                    .getOrDefault(TagSource.AI)
                channelTags[c.getString(0)] = ChannelTags(splitTags(c.getString(1)), source)
            }
        }
        cacheLoaded = true
    }

    /** @return the lowercase description, "" if the video has none, null if not fetched */
    fun description(url: String): String? {
        ensureCache()
        return descriptions[url]
    }

    fun putDescription(url: String, description: String) {
        ensureCache()
        val values = ContentValues().apply {
            put(COL_URL, url)
            put(COL_DESCRIPTION, description)
            put(COL_UPDATED, System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            VIDEO_TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        descriptions[url] = description.lowercase()
    }

    /** @return the channel's tags, looked up by any URL form of the channel */
    fun tags(channelUrl: String?): ChannelTags? {
        if (channelUrl == null) {
            return null
        }
        ensureCache()
        return channelTags[channelKey(channelUrl)]
    }

    fun putTags(channelUrl: String, tags: List<String>, source: TagSource) {
        ensureCache()
        val key = channelKey(channelUrl)
        val clean = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val values = ContentValues().apply {
            put(COL_CHANNEL, key)
            put(COL_TAGS, clean.joinToString(", "))
            put(COL_SOURCE, source.name)
            put(COL_UPDATED, System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            TAG_TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        channelTags[key] = ChannelTags(clean, source)
    }

    /** Forgets the AI-written tags (your own edits stay), so they get written again. */
    fun clearAiTags() {
        ensureCache()
        writableDatabase.delete(TAG_TABLE, "$COL_SOURCE = ?", arrayOf(TagSource.AI.name))
        channelTags.entries.removeIf { it.value.source == TagSource.AI }
    }

    /** @return videos with a fetched description, channels with tags */
    fun counts(): Pair<Int, Int> {
        ensureCache()
        return descriptions.size to channelTags.size
    }

    companion object {
        private const val DB_NAME = "subspipe_smart_search.db"
        private const val DB_VERSION = 3
        private const val VIDEO_TABLE = "video_description"
        private const val TAG_TABLE = "channel_tags"
        private const val COL_URL = "url"
        private const val COL_DESCRIPTION = "description"
        private const val COL_UPDATED = "updated"
        private const val COL_CHANNEL = "channel"
        private const val COL_TAGS = "tags"
        private const val COL_SOURCE = "source"
        private val CHANNEL_ID = Regex("UC[\\w-]{22}")

        /** Same channel can appear as /channel/UC…, with http or https, etc. */
        fun channelKey(url: String): String = CHANNEL_ID.find(url)?.value ?: url

        fun splitTags(text: String): List<String> = text.split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        @Volatile
        private var instance: SmartSearchStore? = null

        fun get(context: Context): SmartSearchStore = instance ?: synchronized(this) {
            instance ?: SmartSearchStore(context).also { instance = it }
        }
    }
}
