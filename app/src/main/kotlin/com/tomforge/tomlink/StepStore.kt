package com.tomforge.tomlink

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
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

    fun count() = DatabaseUtils.queryNumEntries(readableDatabase, "step_records")

    fun export(): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT payload FROM step_records ORDER BY id", null).use { rows ->
            while (rows.moveToNext()) result.put(JSONObject(rows.getString(0)))
        }
        return result
    }
}
