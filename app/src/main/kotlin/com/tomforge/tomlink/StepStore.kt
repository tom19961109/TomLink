package com.tomforge.tomlink

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal class StepStore(context: Context) : SQLiteOpenHelper(context, "steps.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE step_records (id INTEGER PRIMARY KEY, payload TEXT NOT NULL, start_ms INTEGER, end_ms INTEGER, steps INTEGER)")
        createTimeIndex(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE step_records ADD COLUMN start_ms INTEGER")
            db.execSQL("ALTER TABLE step_records ADD COLUMN end_ms INTEGER")
            db.execSQL("ALTER TABLE step_records ADD COLUMN steps INTEGER")
            db.rawQuery("SELECT id, payload FROM step_records ORDER BY id", null).use { rows ->
                db.compileStatement("UPDATE step_records SET start_ms=?, end_ms=?, steps=? WHERE id=?").use { update ->
                    while (rows.moveToNext()) {
                        val interval = runCatching { interval(JSONObject(rows.getString(1))) }.getOrNull()
                            ?: continue
                        update.bindLong(1, interval.start)
                        update.bindLong(2, interval.end)
                        update.bindLong(3, interval.steps)
                        update.bindLong(4, rows.getLong(0))
                        update.executeUpdateDelete()
                    }
                }
            }
            createTimeIndex(db)
        }
    }

    private fun createTimeIndex(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX step_records_end_ms ON step_records (end_ms)")
    }

    private fun interval(record: JSONObject) = StepInterval(
        Instant.parse(record.getString("startTime")).toEpochMilli(),
        Instant.parse(record.getString("endTime")).toEpochMilli(),
        record.getLong("value"),
    )

    fun append(record: JSONObject) {
        val interval = interval(record)
        val values = ContentValues().apply {
            put("payload", record.toString())
            put("start_ms", interval.start)
            put("end_ms", interval.end)
            put("steps", interval.steps)
        }
        writableDatabase.insertOrThrow("step_records", null, values)
    }

    fun stepsByDay(from: LocalDate, until: LocalDate, zone: ZoneId): Map<LocalDate, Long> {
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = until.atStartOfDay(zone).toInstant().toEpochMilli()
        return readableDatabase.rawQuery(
            "SELECT start_ms, end_ms, steps FROM step_records WHERE end_ms > ? AND start_ms < ?",
            arrayOf(start.toString(), end.toString()),
        ).use { rows ->
            val intervals = sequence {
                while (rows.moveToNext()) yield(StepInterval(rows.getLong(0), rows.getLong(1), rows.getLong(2)))
            }
            dailySteps(intervals, from, until, zone)
        }
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
