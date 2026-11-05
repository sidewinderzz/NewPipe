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

    @Volatile
    private var cacheLoaded = false

    override fun onCreate(db: SQLiteDatabase) {
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
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
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
        cacheLoaded = true
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
        private const val DB_VERSION = 1
        private const val TABLE = "video_text"
        private const val COL_URL = "url"
        private const val COL_DESCRIPTION = "description"
        private const val COL_HAS_DESCRIPTION = "has_description"
        private const val COL_EMBEDDING = "embedding"
        private const val COL_UPDATED = "updated"

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
