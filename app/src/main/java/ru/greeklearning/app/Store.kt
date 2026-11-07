package ru.greeklearning.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class Store(private val context: Context,
            now: () -> java.time.Instant = { java.time.Instant.now() },
            zone: () -> java.time.ZoneId = { java.time.ZoneId.systemDefault() }) : SQLiteOpenHelper(context, "learning.db", null, 6) {
    private val daily=DailyTracking(now,zone)
    fun today()=daily.today()
    fun dailyProgress(day: java.time.LocalDate=today())=daily.get(readableDatabase,day)
    fun firstActivityDay()=daily.firstDay(readableDatabase)
    fun dailyGoal()=dailyProgress().goal ?: 100
    fun setDailyGoal(goal: Int)=daily.setGoal(writableDatabase,goal)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE words(id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE progress(wordId TEXT NOT NULL, skill INTEGER NOT NULL, points TEXT NOT NULL, PRIMARY KEY(wordId,skill))")
        db.execSQL("CREATE TABLE attempts(id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        createSessionBalances(db)
        createReviewQueue(db)
        daily.initialize(db)
    }
    private fun createSessionBalances(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE session_balances(sessionId TEXT NOT NULL,wordId TEXT NOT NULL,skill INTEGER NOT NULL,balance TEXT NOT NULL,PRIMARY KEY(sessionId,wordId,skill))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1..5 && newVersion == 6) { "Explicit non-destructive migration required: $oldVersion → $newVersion" }
        if (oldVersion < 2) db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        if (oldVersion < 3) createSessionBalances(db)
        if(oldVersion < 4) createReviewQueue(db)
        if(oldVersion < 6) daily.initialize(db)
    }
    private fun createReviewQueue(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE review_queue(wordId TEXT NOT NULL,mode TEXT NOT NULL,originSession TEXT NOT NULL,queuedAt INTEGER NOT NULL,PRIMARY KEY(wordId,mode))")
    }
    fun reviewIds(mode: Mode): List<String> = readableDatabase.rawQuery("SELECT wordId FROM review_queue WHERE mode=? ORDER BY queuedAt,wordId",arrayOf(mode.name)).use { c ->
        buildList { while(c.moveToNext()) add(c.getString(0)) }
    }
    fun seedSessionBalance(sessionId: String, wordId: String, skill: Int, balance: Points) {
        writableDatabase.insertWithOnConflict("session_balances",null,ContentValues().apply {
            put("sessionId",sessionId);put("wordId",wordId);put("skill",skill);put("balance",balance.toString())
        },SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun seed() {
        val db = writableDatabase
        val version = context.assets.open("a2-version.txt").bufferedReader().use { it.readText().trim() }
        val installed = db.rawQuery("SELECT value FROM metadata WHERE key='dictionary-version'",null).use { if(it.moveToFirst()) it.getString(0) else null }
        if (installed == version) return
        val data = JSONObject(context.assets.open("a2.json").bufferedReader().use { it.readText() })
        require(data.getString("dictionaryVersion") == version)
        db.beginTransaction()
        try {
            val array = data.getJSONArray("words")
            for (i in 0 until array.length()) {
                val json = array.getJSONObject(i); val word = decode(json)
                val old = db.rawQuery("SELECT payload FROM words WHERE id=?",arrayOf(word.id)).use { if(it.moveToFirst()) JSONObject(it.getString(0)) else null }
                val values=ContentValues().apply { put("id",word.id);put("payload",json.toString()) }
                if(old == null) db.insertOrThrow("words",null,values)
                else if(old.optString("source") == "starter-v1" || old.optString("source").startsWith("klik-a2-")) {
                    db.update("words",values,"id=?",arrayOf(word.id))
                }
            }
            db.insertWithOnConflict("metadata",null,ContentValues().apply { put("key","dictionary-version");put("value",version) },SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun allPoints(): Map<Pair<String,Int>,Points> = readableDatabase.rawQuery("SELECT wordId,skill,points FROM progress",null).use { c ->
        buildMap { while(c.moveToNext()) put(c.getString(0) to c.getInt(1),Points.parse(c.getString(2))) }
    }
    fun words(): List<Word> = readableDatabase.rawQuery("SELECT payload FROM words", null).use { c ->
        buildList { while (c.moveToNext()) add(decode(JSONObject(c.getString(0)))) }
    }
    fun points(id: String, skill: Int): Points = readableDatabase.rawQuery(
        "SELECT points FROM progress WHERE wordId=? AND skill=?", arrayOf(id, skill.toString())
    ).use { if (it.moveToFirst()) Points.parse(it.getString(0)) else Points.ZERO }
    fun total(word: Word) = (0..3).fold(Points.ZERO) { a, skill -> a + points(word.id, skill) }

    /** One transaction covers BOTH words of an incorrect match and its idempotency marker. */
    fun apply(attemptId: String, mode: Mode, changes: Map<String, Points>, outcome: String, sessionId: String? = null, practiceKey: String? = null): Boolean {
        val db = writableDatabase; db.beginTransaction()
        try {
            if(practiceKey!=null && daily.completed(db,practiceKey)) { db.setTransactionSuccessful();return false }
            val attempt = daily.stamp(JSONObject().put("id", attemptId).put("mode", mode.name).put("outcome", outcome)
                .put("sessionId",sessionId).put("practiceKey",practiceKey).put("changes", JSONObject().apply { changes.forEach { (id, d) -> put(id, d.toString()) } }))
            val inserted = db.insertWithOnConflict("attempts", null, ContentValues().apply { put("id", attemptId); put("payload", attempt.toString()) }, SQLiteDatabase.CONFLICT_IGNORE)
            if (inserted == -1L) { db.setTransactionSuccessful(); return false }
            changes.forEach { (id, delta) ->
                val old = if(sessionId == null) points(id,mode.skill) else db.rawQuery(
                    "SELECT balance FROM session_balances WHERE sessionId=? AND wordId=? AND skill=?",
                    arrayOf(sessionId,id,mode.skill.toString())
                ).use { if(it.moveToFirst()) Points.parse(it.getString(0)) else points(id,mode.skill) }
                // Preserve penalties at zero within this session; never bank extra credit above 25.
                val raw = (old + delta).let { if(it > Points.MAX) Points.MAX else it }
                if(sessionId != null) db.insertWithOnConflict("session_balances",null,ContentValues().apply {
                    put("sessionId",sessionId);put("wordId",id);put("skill",mode.skill);put("balance",raw.toString())
                },SQLiteDatabase.CONFLICT_REPLACE)
                val value = raw.clamped()
                db.insertWithOnConflict("progress", null, ContentValues().apply {
                    put("wordId", id); put("skill", mode.skill); put("points", value.toString())
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            if (sessionId != null) changes.forEach { (id,delta) ->
                if (outcome == "incorrect" || outcome == "skipped") {
                    db.insertWithOnConflict("review_queue",null,ContentValues().apply {
                        put("wordId",id);put("mode",mode.name);put("originSession",sessionId);put("queuedAt",System.currentTimeMillis())
                    },SQLiteDatabase.CONFLICT_REPLACE)
                } else if (outcome == "correct" && delta == Points.fraction(1,1)) {
                    // Fixing the original mistake must not cancel its next-session repetition.
                    db.delete("review_queue","wordId=? AND mode=? AND originSession<>?",arrayOf(id,mode.name,sessionId))
                }
            }
            daily.record(db,attempt)
            db.setTransactionSuccessful(); return true
        } finally { db.endTransaction() }
    }
    fun add(greek: String, russian: String, pos: String, article: String) {
        val w = Word("user-" + UUID.randomUUID(), greek.trim(), russian.split(';').map { it.trim() }.filter { it.isNotEmpty() }, if(pos=="proper_noun") "noun" else pos, article.trim(),properName=pos=="proper_noun")
        val json = encode(w); decode(json)
        require(words().none { Rules.normalize(it.greek) == Rules.normalize(w.greek) && it.category == w.category }) { "Такое слово уже есть" }
        writableDatabase.insertOrThrow("words", null, ContentValues().apply { put("id", w.id); put("payload", json.toString()) })
    }
    fun backup(): String {
        val root = JSONObject().put("schemaVersion", 1).put("words", JSONArray(words().map { encode(it) }))
        root.put("progress", JSONArray().apply {
            readableDatabase.rawQuery("SELECT wordId,skill,points FROM progress", null).use { c ->
                while (c.moveToNext()) put(JSONObject().put("wordId", c.getString(0)).put("skill", c.getInt(1)).put("points", c.getString(2)))
            }
        })
        root.put("attempts", JSONArray().apply {
            readableDatabase.rawQuery("SELECT payload FROM attempts", null).use { c -> while(c.moveToNext()) put(JSONObject(c.getString(0))) }
        })
        root.put("reviewQueue",JSONArray().apply {
            readableDatabase.rawQuery("SELECT wordId,mode,originSession,queuedAt FROM review_queue",null).use { c ->
                while(c.moveToNext()) put(JSONObject().put("wordId",c.getString(0)).put("mode",c.getString(1)).put("originSession",c.getString(2)).put("queuedAt",c.getLong(3)))
            }
        })
        root.put("dailyGoalHistory",daily.exportGoals(readableDatabase)).put("dailyGoal",dailyGoal())
        val prefs = context.getSharedPreferences("settings", 0)
        root.put("settings", JSONObject().apply { Mode.entries.forEach { put(it.name, prefs.getInt(it.name, 10)) } })
        return root.toString(2)
    }
    fun importData(raw: String): Int {
        require(raw.length < 8_000_000) { "Файл слишком большой" }
        val root = JSONObject(raw); require(root.getInt("schemaVersion") == 1) { "Неизвестная версия файла" }
        val array = root.getJSONArray("words"); require(array.length() in 1..10000)
        val words = (0 until array.length()).map { decode(array.getJSONObject(it)) }
        require(words.map { it.id }.distinct().size == words.size) { "Повторяющиеся ID" }
        val ids = words.map { it.id }.toSet()
        val progress = root.optJSONArray("progress") ?: JSONArray()
        require(progress.length() <= words.size * 4)
        val seen = mutableSetOf<String>()
        for (i in 0 until progress.length()) {
            val p = progress.getJSONObject(i); val id = p.getString("wordId"); val skill = p.getInt("skill")
            require(id in ids && skill in 0..3 && seen.add("$id:$skill")) { "Некорректный прогресс" }
            val value = Points.parse(p.getString("points")); require(value >= Points.ZERO && value <= Points.MAX)
        }
        val settings = root.optJSONObject("settings")
        settings?.keys()?.forEach { key -> require(Mode.entries.any { it.name == key } && settings.getInt(key) in 1..100) }
        val attempts = root.optJSONArray("attempts") ?: JSONArray(); require(attempts.length() <= 100000)
        val goals=root.optJSONArray("dailyGoalHistory") ?: JSONArray()
        daily.validateGoals(goals)
        if(root.has("dailyGoal")) require(root.getInt("dailyGoal") in 10..10000) { "Некорректная дневная цель" }
        for(i in 0 until attempts.length()) {
            val a=attempts.getJSONObject(i)
            if(a.has("localDay")) DailyTracking.parseDay(a.getString("localDay"))
            if(a.has("practiceKey")) require(a.getString("practiceKey").length in 1..1000)
        }
        val review=root.optJSONArray("reviewQueue") ?: JSONArray()
        require(review.length()<=words.size*Mode.entries.size)
        val seenReview=mutableSetOf<String>()
        for(i in 0 until review.length()) {
            val item=review.getJSONObject(i);val mode=item.getString("mode");val id=item.getString("wordId")
            require(id in ids && Mode.entries.any { it.name==mode } && seenReview.add("$id:$mode"))
            require(item.getString("originSession").length in 1..200 && item.getLong("queuedAt")>=0)
        }
        val db = writableDatabase; db.beginTransaction()
        try {
            words.forEach { w ->
                db.insertWithOnConflict("words", null, ContentValues().apply { put("id", w.id); put("payload", encode(w).toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (i in 0 until progress.length()) {
                val p = progress.getJSONObject(i)
                db.insertWithOnConflict("progress", null, ContentValues().apply {
                    put("wordId", p.getString("wordId")); put("skill", p.getInt("skill")); put("points", p.getString("points"))
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (i in 0 until attempts.length()) {
                val a = attempts.getJSONObject(i)
                val inserted=db.insertWithOnConflict("attempts", null, ContentValues().apply { put("id", a.getString("id")); put("payload", a.toString()) }, SQLiteDatabase.CONFLICT_IGNORE)
                if(inserted != -1L) daily.record(db,a)
            }
            for(i in 0 until review.length()) {
                val item=review.getJSONObject(i)
                db.insertWithOnConflict("review_queue",null,ContentValues().apply {
                    put("wordId",item.getString("wordId"));put("mode",item.getString("mode"));put("originSession",item.getString("originSession"));put("queuedAt",item.getLong("queuedAt"))
                },SQLiteDatabase.CONFLICT_REPLACE)
            }
            daily.importGoals(db,goals)
            if(root.has("dailyGoal")) daily.setGoal(db,root.getInt("dailyGoal"))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        settings?.let { value -> context.getSharedPreferences("settings", 0).edit().apply {
            Mode.entries.forEach { if (value.has(it.name)) putInt(it.name, value.getInt(it.name)) }
        }.commit() }
        return words.size
    }
    companion object {
        fun decode(o: JSONObject): Word {
            val id = o.getString("id"); val greek = o.getString("greek"); val rawPos = o.getString("partOfSpeech"); val pos=if(rawPos=="proper_noun") "noun" else rawPos
            val russian = o.getJSONArray("russian").let { a -> (0 until a.length()).map { a.getString(it) } }
            val forms = o.optJSONArray("forms")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            require(id.length in 1..200 && greek.length in 1..200 && Rules.letterCount(greek) > 0)
            require(pos in Rules.parts && russian.isNotEmpty() && russian.size <= 30 && russian.all { it.isNotBlank() && it.length <= 300 })
            require(forms.size <= 30 && forms.all { it.isNotBlank() && it.length <= 200 })
            fun strings(key: String): List<String> = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val entries=strings("sourceEntries");val inflections=strings("inflections");val english=strings("english")
            val pages=o.optJSONArray("sourcePages")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()
            require(entries.size<=50 && pages.size<=100 && pages.all { it in 1..10000 })
            require(inflections.size<=30 && inflections.all { it.length<=200 } && english.size<=50)
            return Word(id, greek, russian, pos, o.optString("article"), forms, o.optString("source", "user"),entries,pages,inflections,english,o.optBoolean("properName") || rawPos=="proper_noun")
        }
        fun encode(w: Word) = JSONObject().put("id", w.id).put("greek", w.greek).put("russian", JSONArray(w.russian))
            .put("partOfSpeech", w.pos).put("article", w.article).put("forms", JSONArray(w.forms)).put("source", w.source)
            .put("sourceEntries",JSONArray(w.sourceEntries)).put("sourcePages",JSONArray(w.sourcePages))
            .put("inflections",JSONArray(w.inflections)).put("english",JSONArray(w.english)).put("properName",w.properName)
    }
}
