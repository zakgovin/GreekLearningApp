package ru.greeklearning.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Cumulative score and error count for a single translation question, not for a whole word/day. */
internal object ChoiceScores {
    data class State(val errors: Int,val applied: Points)
    fun initialize(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS choice_scores(key TEXT PRIMARY KEY,errors INTEGER NOT NULL,applied TEXT NOT NULL)")
        if(db.rawQuery("SELECT 1 FROM metadata WHERE key='choice-scores-v1'",null).use { it.moveToFirst() }) return
        var after=Long.MIN_VALUE
        while(true) {
            val batch=db.rawQuery("SELECT rowid,payload FROM attempts WHERE rowid>? ORDER BY rowid LIMIT 500",arrayOf(after.toString())).use { c ->
                buildList { while(c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
            }
            if(batch.isEmpty()) break
            batch.forEach { (_,raw) -> runCatching { observe(db,JSONObject(raw)) } }
            after=batch.last().first
        }
        db.insertOrThrow("metadata",null,ContentValues().apply { put("key","choice-scores-v1");put("value","1") })
    }
    fun get(db: SQLiteDatabase,key: String): State = db.rawQuery("SELECT errors,applied FROM choice_scores WHERE key=?",arrayOf(key)).use {
        if(it.moveToFirst()) State(it.getInt(0),Points.parse(it.getString(1))) else State(0,Points.ZERO)
    }
    fun seedLegacy(db: SQLiteDatabase,key: String,errors: Int) {
        require(errors>=0)
        db.insertWithOnConflict("choice_scores",null,values(key,State(errors,Points.fraction(-errors,1))),SQLiteDatabase.CONFLICT_IGNORE)
    }
    private fun values(key: String,state: State)=ContentValues().apply { put("key",key);put("errors",state.errors);put("applied",state.applied.toString()) }
    fun observe(db: SQLiteDatabase,attempt: JSONObject) {
        if(attempt.optString("mode") !in listOf(Mode.TO_RU.name,Mode.TO_EL.name)) return
        if(attempt.optString("outcome") !in listOf("correct","incorrect")) return
        val key=attempt.optString("practiceKey").takeIf { it.isNotBlank() } ?: return
        val changes=attempt.optJSONObject("changes") ?: return
        if(changes.length()!=1) return
        val old=get(db,key)
        val state=if(attempt.has("choiceTarget")) State(attempt.getInt("choiceErrors"),Points.parse(attempt.getString("choiceTarget")))
            else State(old.errors+if(attempt.optString("outcome")=="incorrect") 1 else 0,old.applied+Points.parse(changes.getString(changes.keys().next())))
        db.insertWithOnConflict("choice_scores",null,values(key,state),SQLiteDatabase.CONFLICT_REPLACE)
    }
}
