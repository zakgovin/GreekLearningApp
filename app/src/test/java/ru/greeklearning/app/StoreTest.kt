package ru.greeklearning.app

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class StoreTest {
    private lateinit var context: Context
    private lateinit var store: Store
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        context.deleteDatabase("learning.db")
        store=Store(context);store.seed()
    }
    @After fun close() { store.close(); context.deleteDatabase("learning.db") }
    @Test fun bothWordsPenalizedExactlyOnceAndNeverBelowZero() {
        val a=store.words()[0].id;val b=store.words()[1].id
        store.apply("seedA",Mode.TO_RU,mapOf(a to Points.fraction(3,1)),"correct")
        store.apply("seedB",Mode.TO_RU,mapOf(b to Points.fraction(2,1)),"correct")
        val changes=mapOf(a to Points.fraction(-1,1),b to Points.fraction(-1,1))
        assertTrue(store.apply("wrong-pair",Mode.MATCH,changes,"incorrect"))
        assertFalse(store.apply("wrong-pair",Mode.MATCH,changes,"incorrect"))
        assertEquals(Points.fraction(2,1),store.points(a,0));assertEquals(Points.fraction(1,1),store.points(b,0))
        repeat(4) { store.apply("wrong$it",Mode.MATCH,changes,"incorrect") }
        assertEquals(Points.ZERO,store.points(a,0));assertEquals(Points.ZERO,store.points(b,0))
        assertEquals(Points.ZERO,store.points(a,1))
    }
    @Test fun reopeningAndReseedingKeepsFractionalProgressAndUserWords() {
        val id=store.words()[0].id
        store.apply("partial",Mode.WRITE,mapOf(id to Points.fraction(1,3)),"correct")
        val originalCount=store.words().size
        store.add("δοκιμή","проверка","noun","η")
        store.close();store=Store(context);store.seed()
        assertEquals(originalCount+1,store.words().size)
        assertEquals(Points.fraction(1,3),store.points(id,1))
        assertFalse(store.apply("partial",Mode.WRITE,mapOf(id to Points.fraction(1,3)),"correct"))
    }
    @Test fun upgradeFromStarterDatabaseRetainsAllLegacyIdsFractionsUserWordsAndAttempts() {
        store.close();context.deleteDatabase("learning.db")
        val old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("learning.db"),null)
        old.execSQL("CREATE TABLE words(id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        old.execSQL("CREATE TABLE progress(wordId TEXT NOT NULL, skill INTEGER NOT NULL, points TEXT NOT NULL, PRIMARY KEY(wordId,skill))")
        old.execSQL("CREATE TABLE attempts(id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        val starter=JSONObject(context.assets.open("starter.json").bufferedReader().use { it.readText() }).getJSONArray("words")
        for(i in 0 until starter.length()) {
            val word=starter.getJSONObject(i)
            old.execSQL("INSERT INTO words VALUES (?,?)",arrayOf(word.getString("id"),word.toString()))
        }
        val custom=Store.encode(Word("custom-before-update","δοκιμή",listOf("моя проверка"),"noun",source="user"))
        old.execSQL("INSERT INTO words VALUES (?,?)",arrayOf("custom-before-update",custom.toString()))
        old.execSQL("INSERT INTO progress VALUES ('starter-noun-001',1,'1/3')")
        old.execSQL("INSERT INTO attempts VALUES ('legacy-attempt','{}')")
        old.version=1;old.close()
        store=Store(context);store.seed()
        assertEquals(6,store.readableDatabase.version)
        val ids=store.words().map { it.id }.toSet()
        for(i in 0 until starter.length()) assertTrue(starter.getJSONObject(i).getString("id") in ids)
        assertTrue("custom-before-update" in ids)
        assertEquals(Points.fraction(1,3),store.points("starter-noun-001",1))
        assertFalse(store.apply("legacy-attempt",Mode.WRITE,mapOf("starter-noun-001" to Points.fraction(1,1)),"correct"))
        assertEquals("моя проверка",store.words().first { it.id=="custom-before-update" }.russian.first())
        assertTrue(store.words().first { it.id=="starter-noun-001" }.sourcePages.isNotEmpty())
        val count=store.words().size;store.seed();assertEquals(count,store.words().size)
    }
    @Test fun fullDictionaryMetadataSurvivesBackupAndRestore() {
        val original=store.words().first { it.sourceEntries.isNotEmpty() && it.inflections.isNotEmpty() }
        val backup=store.backup()
        store.close();context.deleteDatabase("learning.db");store=Store(context)
        store.importData(backup)
        val restored=store.words().first { it.id==original.id }
        assertEquals(original.sourceEntries,restored.sourceEntries)
        assertEquals(original.sourcePages,restored.sourcePages)
        assertEquals(original.inflections,restored.inflections)
    }
    @Test fun penaltiesAtZeroOffsetLaterAnswersAndSurviveReopeningWithoutLeakingToNewSession() {
        val id=store.words().first().id
        store.apply("debt-wrong",Mode.TO_EL,mapOf(id to Points.fraction(-1,1)),"incorrect","run1")
        assertEquals(Points.ZERO,store.points(id,0))
        store.close();store=Store(context)
        assertFalse(store.apply("debt-wrong",Mode.TO_EL,mapOf(id to Points.fraction(-1,1)),"incorrect","run1"))
        store.apply("debt-right",Mode.TO_EL,mapOf(id to Points.fraction(1,1)),"correct","run1")
        assertEquals(Points.ZERO,store.points(id,0))
        store.apply("fresh-right",Mode.TO_EL,mapOf(id to Points.fraction(1,1)),"correct","run2")
        assertEquals(Points.fraction(1,1),store.points(id,0))
    }
    @Test fun upperLimitDoesNotBankPositiveCreditAndSchemaTwoUpgradeKeepsProgress() {
        val id=store.words().first().id
        store.apply("at-cap",Mode.TO_EL,mapOf(id to Points.MAX),"correct")
        store.writableDatabase.execSQL("DROP TABLE session_balances");store.writableDatabase.execSQL("DROP TABLE review_queue");store.writableDatabase.version=2
        store.close();store=Store(context)
        assertEquals(6,store.readableDatabase.version);assertEquals(Points.MAX,store.points(id,0))
        store.apply("cap-right",Mode.TO_EL,mapOf(id to Points.fraction(1,1)),"correct","cap-run")
        store.apply("cap-wrong",Mode.TO_EL,mapOf(id to Points.fraction(-1,1)),"incorrect","cap-run")
        assertEquals(Points.fraction(24,1),store.points(id,0))
    }
    @Test fun mistakesRemainQueuedAfterCorrectionButClearAfterIndependentNextSessionSuccess() {
        val id=store.words().first().id
        store.apply("repeat-wrong",Mode.TO_EL,mapOf(id to Points.fraction(-1,1)),"incorrect","first")
        store.apply("repeat-corrected",Mode.TO_EL,mapOf(id to Points.fraction(1,1)),"correct","first")
        assertEquals(listOf(id),store.reviewIds(Mode.TO_EL));assertTrue(store.reviewIds(Mode.TO_RU).isEmpty())
        store.close();store=Store(context)
        assertEquals(listOf(id),store.reviewIds(Mode.TO_EL))
        store.apply("repeat-next",Mode.TO_EL,mapOf(id to Points.fraction(1,1)),"correct","second")
        assertTrue(store.reviewIds(Mode.TO_EL).isEmpty())
    }
    @Test fun bothMismatchedWordsAreQueuedAndBackupRestoresQueue() {
        val ids=store.words().take(2).map { it.id }
        store.apply("pair-repeat",Mode.MATCH,ids.associateWith { Points.fraction(-1,1) },"incorrect","pair-run")
        assertEquals(ids.toSet(),store.reviewIds(Mode.MATCH).toSet())
        val backup=store.backup();store.close();context.deleteDatabase("learning.db");store=Store(context)
        store.importData(backup)
        assertEquals(ids.toSet(),store.reviewIds(Mode.MATCH).toSet())
        assertTrue(store.reviewIds(Mode.TO_EL).isEmpty())
    }
    @Test fun schemaThreeUpgradeKeepsProgressAndExistingScoringBalances() {
        val id=store.words().first().id
        store.apply("before-upgrade-error",Mode.TO_EL,mapOf(id to Points.fraction(-1,1)),"incorrect","old-run")
        store.writableDatabase.execSQL("DROP TABLE review_queue");store.writableDatabase.version=3
        store.close();store=Store(context)
        assertEquals(6,store.readableDatabase.version)
        store.apply("after-upgrade-correct",Mode.TO_EL,mapOf(id to Points.fraction(1,1)),"correct","old-run")
        assertEquals(Points.ZERO,store.points(id,0));assertTrue(store.reviewIds(Mode.TO_EL).isEmpty())
    }
    @Test fun audioLevelsShareSkillAndCapAt25() {
        val id=store.words()[0].id
        repeat(30) { store.apply("audio$it",if(it%2==0)Mode.LISTEN_EASY else Mode.LISTEN_HARD,mapOf(id to Points.fraction(1,1)),"correct") }
        assertEquals(Points.MAX,store.points(id,2))
        store.apply("skip",Mode.LISTEN_HARD,mapOf(id to Points.fraction(-1,1)),"skipped")
        assertEquals(Points.fraction(24,1),store.points(id,2))
    }
    @Test fun exportRestoreAndMalformedImportAreTransactional() {
        val id=store.words()[0].id
        store.apply("fraction",Mode.WRITE,mapOf(id to Points.fraction(3,4)),"correct")
        context.getSharedPreferences("settings",0).edit().putInt(Mode.WRITE.name,100).commit()
        val expectedCount=store.words().size
        val backup=store.backup()
        store.close();context.deleteDatabase("learning.db");store=Store(context)
        assertEquals(expectedCount,store.importData(backup))
        assertEquals(Points.fraction(3,4),store.points(id,1))
        assertEquals(100,context.getSharedPreferences("settings",0).getInt(Mode.WRITE.name,0))
        val bad=JSONObject(backup);bad.getJSONArray("progress").getJSONObject(0).put("points","26/1")
        assertThrows(IllegalArgumentException::class.java) { store.importData(bad.toString()) }
        assertEquals(Points.fraction(3,4),store.points(id,1))
    }
}
