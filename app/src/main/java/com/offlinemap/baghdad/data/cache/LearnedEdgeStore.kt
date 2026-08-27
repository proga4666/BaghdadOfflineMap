package com.offlinemap.baghdad.data.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentHashMap

class LearnedEdgeStore(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    // Fast in-memory lookup set for zero-overhead A* graph searches
    private val memoryEdgeSet = ConcurrentHashMap.newKeySet<Int>()

    init {
        loadAllEdgesIntoMemory()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_EDGES (
                $COL_EDGE_ID INTEGER PRIMARY KEY,
                $COL_USAGE_COUNT INTEGER DEFAULT 1,
                $COL_LAST_SEEN INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_EDGES")
        onCreate(db)
    }

    private fun loadAllEdgesIntoMemory() {
        try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT $COL_EDGE_ID FROM $TABLE_EDGES", null)
            cursor.use {
                while (it.moveToNext()) {
                    val edgeId = it.getInt(0)
                    memoryEdgeSet.add(edgeId)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isLearnedEdge(edgeId: Int): Boolean {
        return memoryEdgeSet.contains(edgeId)
    }

    fun getLearnedEdgesCount(): Int = memoryEdgeSet.size

    fun addLearnedEdges(edgeIds: Collection<Int>) {
        if (edgeIds.isEmpty()) return
        memoryEdgeSet.addAll(edgeIds)

        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (edgeId in edgeIds) {
                    val values = ContentValues().apply {
                        put(COL_EDGE_ID, edgeId)
                        put(COL_LAST_SEEN, System.currentTimeMillis())
                    }
                    db.insertWithOnConflict(
                        TABLE_EDGES,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                    db.execSQL(
                        "UPDATE $TABLE_EDGES SET $COL_USAGE_COUNT = $COL_USAGE_COUNT + 1, $COL_LAST_SEEN = ? WHERE $COL_EDGE_ID = ?",
                        arrayOf(System.currentTimeMillis().toString(), edgeId.toString())
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        const val DATABASE_NAME = "learned_edges.db"
        const val DATABASE_VERSION = 1

        const val TABLE_EDGES = "learned_edges"
        const val COL_EDGE_ID = "edge_id"
        const val COL_USAGE_COUNT = "usage_count"
        const val COL_LAST_SEEN = "last_seen"
    }
}
