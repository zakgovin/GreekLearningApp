package ru.greeklearning.app

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
class ChoiceScoresTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private lateinit var store: Store
    @Before fun setup() { context.deleteDatabase("learning.db");store=Store(context);store.seed() }
    @After fun close() { store.close();context.deleteDatabase("learning.db") }
    private fun submit(id: String,mode: Mode,key: String,correct: Boolean,attempt: String) =
        store.apply(attempt,mode,mapOf(id to if(correct) Points.fraction(1,1) else Points.fraction(-1,1)),if(correct) "correct" else "incorrect","run",key,choiceScoring=true)
    @Test fun exactTotalsForZeroOneAndManyErrorsInBothModes() {
        val ids=store.words().take(8).map { it.id };var n=0
        for(mode in listOf(Mode.TO_RU,Mode.TO_EL)) for(errors in listOf(0,1,2,5)) {
            val id=ids[n++];store.apply("seed-$id",Mode.TO_RU,mapOf(id to Points.fraction(5,1)),"correct")
            val key="run:$mode:$id";val dailyBefore=store.dailyProgress().count
            repeat(errors) { i ->
                assertTrue(submit(id,mode,key,false,"wrong-$id-$i"))
                assertEquals(Points.fraction(if(i==0) 5 else 4,1),store.points(id,0))
                assertEquals(dailyBefore,store.dailyProgress().count)
            }
            assertTrue(submit(id,mode,key,true,"correct-$id"))
            assertEquals(Points.fraction(5,1)+Rules.choiceTotal(errors,true),store.points(id,0))
            assertEquals(dailyBefore+1,store.dailyProgress().count)
            assertFalse(submit(id,mode,key,true,"duplicate-new-id-$id"))
            assertEquals(Points.fraction(5,1)+Rules.choiceTotal(errors,true),store.points(id,0))
        }
    }
    @Test fun ceilingFloorAndNewQuestionsKeepIndependentScores() {
        val ids=store.words().take(2).map { it.id }
        submit(ids[0],Mode.TO_RU,"zero",false,"z1");submit(ids[0],Mode.TO_RU,"zero",false,"z2");submit(ids[0],Mode.TO_RU,"zero",true,"z3")
        assertEquals(Points.ZERO,store.points(ids[0],0))
        store.apply("at-cap",Mode.TO_RU,mapOf(ids[1] to Points.MAX),"correct")
        submit(ids[1],Mode.TO_RU,"cap",false,"c1");submit(ids[1],Mode.TO_RU,"cap",true,"c2")
        assertEquals(Points.MAX,store.points(ids[1],0))
        repeat(4) { submit(ids[1],Mode.TO_RU,"next-question",false,"n$it") };submit(ids[1],Mode.TO_RU,"next-question",true,"n-done")
        assertEquals(Points.fraction(24,1),store.points(ids[1],0))
    }
    @Test fun backupAndRepeatedImportRestorePendingHalfCreditWithoutExtraAward() {
        val id=store.words().first().id;store.apply("seed",Mode.TO_RU,mapOf(id to Points.fraction(5,1)),"correct")
        submit(id,Mode.TO_RU,"pending",false,"first-wrong")
        val backup=store.backup();store.close();context.deleteDatabase("learning.db");store=Store(context)
        store.importData(backup);store.importData(backup)
        submit(id,Mode.TO_RU,"pending",true,"done")
        assertEquals(Points.fraction(11,2),store.points(id,0))
        assertFalse(submit(id,Mode.TO_RU,"pending",true,"again"))
        assertEquals(Points.fraction(11,2),store.points(id,0))
        assertTrue(store.reviewIds(Mode.TO_RU).contains(id))
    }
    @Test fun versionSixPendingPenaltiesAreReconciledWithoutResettingOtherProgress() {
        val ids=store.words().take(2).map { it.id }
        ids.forEach { id -> store.apply("seed-$id",Mode.TO_RU,mapOf(id to Points.fraction(5,1)),"correct") }
        repeat(3) { store.apply("old-many-$it",Mode.TO_RU,mapOf(ids[0] to Points.fraction(-1,1)),"incorrect","run","old-many") }
        store.apply("old-one",Mode.TO_EL,mapOf(ids[1] to Points.fraction(-1,1)),"incorrect","run","old-single")
        val before=store.allPoints();val goal=store.dailyGoal()
        store.writableDatabase.execSQL("DROP TABLE choice_scores")
        store.writableDatabase.execSQL("DELETE FROM metadata WHERE key='choice-scores-v1'")
        store.writableDatabase.version=6;store.close();store=Store(context)
        assertEquals(7,store.readableDatabase.version);assertEquals(before,store.allPoints());assertEquals(goal,store.dailyGoal())
        submit(ids[0],Mode.TO_RU,"old-many",true,"fixed-many")
        submit(ids[1],Mode.TO_EL,"old-single",true,"fixed-one")
        assertEquals(Points.fraction(4,1),store.points(ids[0],0))
        assertEquals(Points.fraction(11,2),store.points(ids[1],0))
    }
}
