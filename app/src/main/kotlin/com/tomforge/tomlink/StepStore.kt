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

internal class StepStore(context: Context) : SQLiteOpenHelper(context, "steps.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE step_records (id INTEGER PRIMARY KEY, payload TEXT NOT NULL, start_ms INTEGER, end_ms INTEGER, steps INTEGER, transport_key TEXT UNIQUE)")
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
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE step_records ADD COLUMN transport_key TEXT")
            db.execSQL("CREATE UNIQUE INDEX transport_records_key ON step_records (transport_key)")
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
        val transport = record.optString("type") == "transport"
        val values = ContentValues().apply {
            put("payload", record.toString())
            put("start_ms", Instant.parse(record.getString("startTime")).toEpochMilli())
            put("end_ms", Instant.parse(record.getString("endTime")).toEpochMilli())
            if (transport) put("transport_key", record.getString("recordId"))
            else put("steps", record.getLong("value"))
        }
        if (transport) writableDatabase.insertWithOnConflict("step_records", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        else writableDatabase.insertOrThrow("step_records", null, values)
    }

    fun stepsByDay(from: LocalDate, until: LocalDate, zone: ZoneId, newest: Long = Long.MAX_VALUE): Map<LocalDate, Long> {
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = until.atStartOfDay(zone).toInstant().toEpochMilli()
        return readableDatabase.rawQuery(
            "SELECT start_ms, end_ms, steps FROM step_records WHERE steps IS NOT NULL AND end_ms > ? AND start_ms < ? AND id <= ?",
            arrayOf(start.toString(), end.toString(), newest.toString()),
        ).use { rows ->
            val intervals = sequence {
                while (rows.moveToNext()) yield(StepInterval(rows.getLong(0), rows.getLong(1), rows.getLong(2)))
            }
            dailySteps(intervals, from, until, zone)
        }
    }

    fun transportByDay(from: LocalDate, until: LocalDate, zone: ZoneId, newest: Long = Long.MAX_VALUE, active: ActiveTravel? = null): Map<LocalDate, TravelDuration> {
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = until.atStartOfDay(zone).toInstant().toEpochMilli()
        return readableDatabase.rawQuery(
            "SELECT start_ms, end_ms, payload FROM step_records WHERE transport_key IS NOT NULL AND end_ms > ? AND start_ms < ? AND id <= ? ORDER BY start_ms, id",
            arrayOf(begin.toString(), end.toString(), newest.toString()),
        ).use { rows ->
            val totals = mutableMapOf<LocalDate, TravelDuration>()
            var creditedEnd = begin
            fun addTravel(startMs: Long, endMs: Long, bicycle: Boolean) {
                var start = maxOf(creditedEnd, startMs)
                val stop = minOf(end, endMs)
                while (start < stop) {
                    val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                    val boundary = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val next = minOf(stop, boundary)
                    val duration = next - start
                    val total = totals[day] ?: TravelDuration()
                    totals[day] = if (bicycle) total.copy(bicycleMs = total.bicycleMs + duration)
                    else total.copy(vehicleMs = total.vehicleMs + duration)
                    start = next
                }
                creditedEnd = maxOf(creditedEnd, stop)
            }
            while (rows.moveToNext()) {
                addTravel(rows.getLong(0), rows.getLong(1), JSONObject(rows.getString(2)).optString("mode") == "bicycle")
            }
            active?.let { addTravel(it.startWallMs, it.endWallMs, it.bicycle) }
            totals
        }
    }

    fun lastId(): Long = readableDatabase.rawQuery("SELECT COALESCE(MAX(id), 0) FROM step_records", null).use {
        it.moveToFirst()
        it.getLong(0)
    }

    fun changedDays(after: Long, newest: Long): Set<LocalDate> =
        readableDatabase.rawQuery(
            "SELECT DISTINCT date(start_ms/1000, 'unixepoch', '+8 hours'), date((end_ms-1)/1000, 'unixepoch', '+8 hours') FROM step_records WHERE id > ? AND id <= ? AND end_ms > start_ms",
            arrayOf(after.toString(), newest.toString()),
        ).use { rows ->
            sortedSetOf<LocalDate>().apply {
                while (rows.moveToNext()) {
                    var day = LocalDate.parse(rows.getString(0))
                    val last = LocalDate.parse(rows.getString(1))
                    while (day <= last) {
                        add(day)
                        day = day.plusDays(1)
                    }
                }
            }
        }

    fun export(): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT payload FROM step_records ORDER BY id", null).use { rows ->
            while (rows.moveToNext()) result.put(JSONObject(rows.getString(0)))
        }
        return result
    }
}
