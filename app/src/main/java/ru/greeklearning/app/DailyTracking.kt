package ru.greeklearning.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DailyProgress(val day: LocalDate, val count: Long, val goal: Int?)

/** Calendar-day totals. Attempt insertion and counting always share Store's transaction. */
internal class DailyTracking(private val now: () -> Instant, private val zone: () -> ZoneId) {
    fun today(): LocalDate = now().atZone(zone()).toLocalDate()
    fun initialize(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS daily_counts(day TEXT PRIMARY KEY,count INTEGER NOT NULL CHECK(count>=0))")
        db.execSQL("CREATE TABLE IF NOT EXISTS daily_goals(day TEXT PRIMARY KEY,goal INTEGER NOT NULL CHECK(goal BETWEEN 10 AND 10000))")
        val initialized=db.rawQuery("SELECT 1 FROM metadata WHERE key='daily-stats-v1'",null).use { it.moveToFirst() }
        if(initialized) return
        db.rawQuery("SELECT payload FROM attempts",null).use { c ->
            while(c.moveToNext()) runCatching { JSONObject(c.getString(0)) }.getOrNull()?.let { record(db,it) }
        }
        db.insertWithOnConflict("daily_goals",null,ContentValues().apply { put("day",today().toString());put("goal",100) },SQLiteDatabase.CONFLICT_IGNORE)
        db.insertOrThrow("metadata",null,ContentValues().apply { put("key","daily-stats-v1");put("value","1") })
    }
    fun stamp(attempt: JSONObject): JSONObject {
        val time=now()
        return attempt.put("time",time.toEpochMilli()).put("localDay",time.atZone(zone()).toLocalDate().toString())
    }
    fun record(db: SQLiteDatabase, attempt: JSONObject) {
        if(attempt.optString("outcome") !in listOf("correct","incorrect","skipped")) return
        if(Mode.entries.none { it.name==attempt.optString("mode") }) return
        val count=attempt.optJSONObject("changes")?.length() ?: return
        if(count==0) return
        val day=runCatching {
            if(attempt.has("localDay")) parseDay(attempt.getString("localDay"))
            else if(attempt.has("time") && attempt.optLong("time",-1)>=0) parseDay(Instant.ofEpochMilli(attempt.getLong("time")).atZone(zone()).toLocalDate().toString())
            else null
        }.getOrNull() ?: return // Old markers without a timestamp cannot establish a historical day.
        db.insertWithOnConflict("daily_counts",null,ContentValues().apply { put("day",day.toString());put("count",0) },SQLiteDatabase.CONFLICT_IGNORE)
        db.execSQL("UPDATE daily_counts SET count=count+? WHERE day=?",arrayOf(count,day.toString()))
        // Freeze the first recovered day of legacy data, so later exports cannot move it across timezones.
        if(!attempt.has("localDay") && attempt.optString("id").isNotBlank()) {
            attempt.put("localDay",day.toString())
            db.update("attempts",ContentValues().apply { put("payload",attempt.toString()) },"id=?",arrayOf(attempt.getString("id")))
        }
    }
    fun get(db: SQLiteDatabase, day: LocalDate=today()): DailyProgress {
        val count=db.rawQuery("SELECT count FROM daily_counts WHERE day=?",arrayOf(day.toString())).use { if(it.moveToFirst()) it.getLong(0) else 0L }
        val goal=db.rawQuery("SELECT goal FROM daily_goals WHERE day<=? ORDER BY day DESC LIMIT 1",arrayOf(day.toString())).use { if(it.moveToFirst()) it.getInt(0) else null }
        return DailyProgress(day,count,goal)
    }
    fun firstDay(db: SQLiteDatabase): LocalDate = db.rawQuery("SELECT MIN(day) FROM (SELECT day FROM daily_counts UNION ALL SELECT day FROM daily_goals)",null).use {
        if(it.moveToFirst() && !it.isNull(0)) parseDay(it.getString(0)) else today()
    }
    fun setGoal(db: SQLiteDatabase, goal: Int) {
        require(goal in 10..10000) { "Дневная цель должна быть от 10 до 10000" }
        db.insertWithOnConflict("daily_goals",null,ContentValues().apply { put("day",today().toString());put("goal",goal) },SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun exportGoals(db: SQLiteDatabase): JSONArray = JSONArray().apply {
        db.rawQuery("SELECT day,goal FROM daily_goals ORDER BY day",null).use { c ->
            while(c.moveToNext()) put(JSONObject().put("day",c.getString(0)).put("goal",c.getInt(1)))
        }
    }
    fun validateGoals(array: JSONArray) {
        require(array.length()<=100000)
        val days=mutableSetOf<String>()
        for(i in 0 until array.length()) {
            val g=array.getJSONObject(i);val day=parseDay(g.getString("day"))
            require(days.add(day.toString()) && g.getInt("goal") in 10..10000) { "Некорректная история дневных целей" }
        }
    }
    fun importGoals(db: SQLiteDatabase,array: JSONArray) {
        for(i in 0 until array.length()) {
            val g=array.getJSONObject(i)
            db.insertWithOnConflict("daily_goals",null,ContentValues().apply { put("day",g.getString("day"));put("goal",g.getInt("goal")) },SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
    companion object {
        fun parseDay(raw: String): LocalDate {
            val date=LocalDate.parse(raw)
            require(date.year in 1970..9999 && date.toString()==raw) { "Некорректная дата дневной статистики" }
            return date
        }
    }
}
