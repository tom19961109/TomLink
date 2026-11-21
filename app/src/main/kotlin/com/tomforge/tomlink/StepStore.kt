package com.tomforge.tomlink

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

internal class StepStore(context: Context) : SQLiteOpenHelper(context, "steps.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE step_records (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun append(record: JSONObject) {
        val values = ContentValues().apply { put("payload", record.toString()) }
        writableDatabase.insertOrThrow("step_records", null, values)
    }

    data class Page(val records: JSONArray, val lastId: Long)

    fun lastId(): Long = readableDatabase.rawQuery("SELECT COALESCE(MAX(id), 0) FROM step_records", null).use {
        it.moveToFirst()
        it.getLong(0)
    }

    fun page(after: Long, newest: Long): Page {
        val result = JSONArray()
        var last = after
        readableDatabase.rawQuery(
            "SELECT id, payload FROM step_records WHERE id > ? AND id <= ? ORDER BY id LIMIT 100",
            arrayOf(after.toString(), newest.toString()),
        ).use { rows ->
            while (rows.moveToNext()) {
                last = rows.getLong(0)
                result.put(JSONObject(rows.getString(1)))
            }
        }
        return Page(result, last)
    }

    fun export(): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT payload FROM step_records ORDER BY id", null).use { rows ->
            while (rows.moveToNext()) result.put(JSONObject(rows.getString(0)))
        }
        return result
    }
}
