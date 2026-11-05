/*
 * SubsPipe: storage for video descriptions and their embeddings.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.subspipe

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * A small separate database (so the main NewPipe database and its migrations are left
 * untouched) holding, for each feed video, its description and its embedding.
 * Embeddings are also kept in memory because every search compares against all of them.
 */
class SmartSearchStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class Entry(val embedding: FloatArray, val hasDescription: Boolean)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** Channel "profile" embeddings (channel name + about text), keyed by [channelKey]. */
    private val channelCache = ConcurrentHashMap<String, Pair<FloatArray, Int>>()

    @Volatile
    private var cacheLoaded = false

    override fun onCreate(db: SQLiteDatabase) {
        createChannelTable(db)
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "$COL_URL TEXT PRIMARY KEY NOT NULL, " +
                "$COL_DESCRIPTION TEXT, " +
                "$COL_HAS_DESCRIPTION INTEGER NOT NULL DEFAULT 0, " +
                "$COL_EMBEDDING BLOB NOT NULL, " +
                "$COL_UPDATED INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createChannelTable(db)
        }
    }

    private fun createChannelTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $CHANNEL_TABLE (" +
                "$COL_URL TEXT PRIMARY KEY NOT NULL, " +
                "$COL_TEXT_HASH INTEGER NOT NULL, " +
                "$COL_EMBEDDING BLOB NOT NULL)"
        )
    }

    @Synchronized
    private fun ensureCache() {
        if (cacheLoaded) {
            return
        }
        readableDatabase.query(
            TABLE, arrayOf(COL_URL, COL_EMBEDDING, COL_HAS_DESCRIPTION),
            null, null, null, null, null
        ).use { c ->
            while (c.moveToNext()) {
                cache[c.getString(0)] = Entry(fromBytes(c.getBlob(1)), c.getInt(2) != 0)
            }
        }
        readableDatabase.query(
            CHANNEL_TABLE, arrayOf(COL_URL, COL_EMBEDDING, COL_TEXT_HASH),
            null, null, null, null, null
        ).use { c ->
            while (c.moveToNext()) {
                channelCache[c.getString(0)] = fromBytes(c.getBlob(1)) to c.getInt(2)
            }
        }
        cacheLoaded = true
    }

    /** @return the channel's profile embedding, looked up by any URL form of the channel */
    fun getChannel(channelUrl: String?): FloatArray? {
        if (channelUrl == null) {
            return null
        }
        ensureCache()
        return channelCache[channelKey(channelUrl)]?.first
    }

    /** @return true if the stored channel profile was built from text with this hash */
    fun hasChannel(channelUrl: String, textHash: Int): Boolean {
        ensureCache()
        return channelCache[channelKey(channelUrl)]?.second == textHash
    }

    fun putChannel(channelUrl: String, textHash: Int, embedding: FloatArray) {
        ensureCache()
        val key = channelKey(channelUrl)
        val values = ContentValues().apply {
            put(COL_URL, key)
            put(COL_TEXT_HASH, textHash)
            put(COL_EMBEDDING, toBytes(embedding))
        }
        writableDatabase.insertWithOnConflict(
            CHANNEL_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
        channelCache[key] = embedding to textHash
    }

    fun get(url: String): Entry? {
        ensureCache()
        return cache[url]
    }

    fun put(url: String, embedding: FloatArray, description: String?) {
        ensureCache()
        val values = ContentValues().apply {
            put(COL_URL, url)
            put(COL_DESCRIPTION, description)
            put(COL_HAS_DESCRIPTION, if (description != null) 1 else 0)
            put(COL_EMBEDDING, toBytes(embedding))
            put(COL_UPDATED, System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        cache[url] = Entry(embedding, description != null)
    }

    companion object {
        private const val DB_NAME = "subspipe_smart_search.db"
        private const val DB_VERSION = 2
        private const val TABLE = "video_text"
        private const val COL_URL = "url"
        private const val COL_DESCRIPTION = "description"
        private const val COL_HAS_DESCRIPTION = "has_description"
        private const val COL_EMBEDDING = "embedding"
        private const val COL_UPDATED = "updated"
        private const val CHANNEL_TABLE = "channel_text"
        private const val COL_TEXT_HASH = "text_hash"
        private val CHANNEL_ID = Regex("UC[\\w-]{22}")

        /** Same channel can appear as /channel/UC…, with http or https, etc. */
        fun channelKey(url: String): String = CHANNEL_ID.find(url)?.value ?: url

        @Volatile
        private var instance: SmartSearchStore? = null

        fun get(context: Context): SmartSearchStore = instance ?: synchronized(this) {
            instance ?: SmartSearchStore(context).also { instance = it }
        }

        private fun toBytes(values: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { buffer.putFloat(it) }
            return buffer.array()
        }

        private fun fromBytes(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { buffer.getFloat() }
        }
    }
}
