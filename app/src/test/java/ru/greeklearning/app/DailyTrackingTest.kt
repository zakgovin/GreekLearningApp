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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DailyTrackingTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private lateinit var store: Store
    private var instant=Instant.parse("2026-11-08T12:00:00Z")
    private var zone=ZoneId.of("Europe/Moscow")
    private fun open()=Store(context,{instant},{zone})
    @Before fun setup() { context.deleteDatabase("learning.db");store=open();store.seed() }
    @After fun close() { store.close();context.deleteDatabase("learning.db") }
    @Test fun retryErrorsDoNotCountWhileTerminalOutcomesAndHintsCountOnce() {
        val ids=store.words().take(2).map { it.id }
        store.apply("answer1",Mode.WRITE,mapOf(ids[0] to Points.ZERO),"correct")
        assertEquals(1L,store.dailyProgress().count)
        store.apply("answer2",Mode.TO_RU,mapOf(ids[0] to Points.fraction(-1,1)),"incorrect")
        store.apply("answer3",Mode.SPEAK,mapOf(ids[0] to Points.fraction(-1,1)),"skipped")
        store.apply("pair",Mode.MATCH,ids.associateWith { Points.fraction(-1,1) },"incorrect")
        assertEquals(2L,store.dailyProgress().count)
        assertFalse(store.apply("pair",Mode.MATCH,ids.associateWith { Points.fraction(-1,1) },"incorrect"))
        store.apply("pair-corrected",Mode.MATCH,mapOf(ids[0] to Points.fraction(1,1)),"correct")
        store.apply("choice-corrected",Mode.TO_RU,mapOf(ids[0] to Points.fraction(1,1)),"correct")
        assertEquals(4L,store.dailyProgress().count)
        store.close();store=open();assertEquals(4L,store.dailyProgress().count)
    }
    @Test fun midnightResetsTodayWhilePastGoalsAndZeroActivityDaysRemain() {
        val id=store.words().first().id;val first=store.today()
        store.setDailyGoal(10)
        repeat(12) { store.apply("practice-$it",Mode.TO_EL,mapOf(id to Points.fraction(1,1)),"correct") }
        assertEquals(DailyProgress(first,12,10),store.dailyProgress())
        instant=instant.plusSeconds(86400)
        assertEquals(DailyProgress(first.plusDays(1),0,10),store.dailyProgress())
        store.setDailyGoal(10000)
        instant=instant.plusSeconds(86400)
        assertEquals(DailyProgress(first.plusDays(2),0,10000),store.dailyProgress())
        assertEquals(DailyProgress(first,12,10),store.dailyProgress(first))
        assertEquals(DailyProgress(first.plusDays(1),0,10000),store.dailyProgress(first.plusDays(1)))
        assertThrows(IllegalArgumentException::class.java) { store.setDailyGoal(9) }
        assertThrows(IllegalArgumentException::class.java) { store.setDailyGoal(10001) }
        assertEquals(10000,store.dailyGoal())
    }
    @Test fun localDayIsFrozenInAttemptAndSurvivesRestoreInAnotherTimezone() {
        instant=Instant.parse("2026-11-08T21:30:00Z")
        val id=store.words().first().id
        store.apply("local-midnight",Mode.LISTEN_EASY,mapOf(id to Points.fraction(1,1)),"correct")
        assertEquals(LocalDate.parse("2026-11-09"),store.today())
        val backup=store.backup()
        store.close();context.deleteDatabase("learning.db");zone=ZoneId.of("UTC");store=open();store.importData(backup)
        assertEquals(0L,store.dailyProgress(LocalDate.parse("2026-11-08")).count)
        assertEquals(1L,store.dailyProgress(LocalDate.parse("2026-11-09")).count)
    }
    @Test fun dstCalendarBoundaryUsesPhoneLocalDateRatherThanTwentyFourHourBuckets() {
        zone=ZoneId.of("Europe/Berlin");instant=Instant.parse("2026-10-25T00:30:00Z")
        val id=store.words().first().id
        store.apply("before-dst",Mode.WRITE,mapOf(id to Points.fraction(1,1)),"correct")
        instant=Instant.parse("2026-10-25T01:30:00Z")
        store.apply("after-dst",Mode.WRITE,mapOf(id to Points.fraction(1,1)),"correct")
        assertEquals(2L,store.dailyProgress().count)
        instant=Instant.parse("2026-10-25T23:01:00Z")
        assertEquals(LocalDate.parse("2026-10-26"),store.today());assertEquals(0L,store.dailyProgress().count)
        assertEquals(2L,store.dailyProgress(LocalDate.parse("2026-10-25")).count)
    }
    @Test fun versionFourUpgradeBackfillsOldAttemptsOnceWithoutTouchingWordProgress() {
        val ids=store.words().take(2).map { it.id };instant=Instant.parse("2026-11-07T12:00:00Z")
        store.apply("old-pair",Mode.MATCH,ids.associateWith { Points.fraction(-1,1) },"incorrect","old-session")
        store.apply("old-writing",Mode.WRITE,mapOf(ids[0] to Points.fraction(-1,1)),"incorrect")
        val points=store.allPoints();val reviews=store.reviewIds(Mode.MATCH)
        val db=store.writableDatabase
        db.execSQL("UPDATE attempts SET payload=? WHERE id='old-pair'",arrayOf(JSONObject(db.rawQuery("SELECT payload FROM attempts WHERE id='old-pair'",null).use { it.moveToFirst();it.getString(0) }).apply { remove("localDay") }.toString()))
        db.execSQL("INSERT INTO attempts VALUES ('old-marker','{}')")
        db.execSQL("DROP TABLE daily_counts");db.execSQL("DROP TABLE daily_goals")
        db.execSQL("DELETE FROM metadata WHERE key LIKE 'daily-stats-v%'");db.version=4;store.close()
        instant=Instant.parse("2026-11-08T12:00:00Z");store=open()
        assertEquals(6,store.readableDatabase.version)
        assertEquals(1L,store.dailyProgress(LocalDate.parse("2026-11-07")).count)
        assertNull(store.dailyProgress(LocalDate.parse("2026-11-07")).goal)
        assertEquals(points,store.allPoints());assertEquals(reviews,store.reviewIds(Mode.MATCH))
        store.close();store=open();assertEquals(1L,store.dailyProgress(LocalDate.parse("2026-11-07")).count)
    }
    @Test fun backupRestoreKeepsGoalsHistoryAndDuplicateImportDoesNotAddPractice() {
        val first=store.today();val id=store.words().first().id
        store.setDailyGoal(42);store.apply("one",Mode.WRITE,mapOf(id to Points.fraction(1,1)),"correct")
        instant=instant.plusSeconds(86400);store.setDailyGoal(250)
        store.apply("two",Mode.SPEAK,mapOf(id to Points.fraction(1,1)),"correct")
        val backup=store.backup();store.close();context.deleteDatabase("learning.db");store=open()
        store.importData(backup);store.importData(backup)
        assertEquals(DailyProgress(first,1,42),store.dailyProgress(first))
        assertEquals(DailyProgress(first.plusDays(1),1,250),store.dailyProgress())
        val bad=JSONObject(backup);bad.getJSONArray("dailyGoalHistory").getJSONObject(0).put("goal",10001)
        assertThrows(IllegalArgumentException::class.java) { store.importData(bad.toString()) }
        assertEquals(250,store.dailyGoal());assertEquals(1L,store.dailyProgress().count)
    }
    @Test fun olderBackupsWithoutDailyFieldsRecoverAvailableAttemptDates() {
        instant=Instant.parse("2026-11-08T21:30:00Z")
        val id=store.words().first().id
        store.apply("old-json",Mode.WRITE,mapOf(id to Points.fraction(1,1)),"correct")
        val backup=JSONObject(store.backup()).apply {
            remove("dailyGoalHistory");remove("dailyGoal");getJSONArray("attempts").getJSONObject(0).remove("localDay")
        }.toString()
        store.close();context.deleteDatabase("learning.db");store=open();store.importData(backup)
        assertEquals(1L,store.dailyProgress().count);assertEquals(100,store.dailyGoal())
        val frozen=store.backup()
        assertEquals("2026-11-09",JSONObject(frozen).getJSONArray("attempts").getJSONObject(0).getString("localDay"))
        store.close();context.deleteDatabase("learning.db");zone=ZoneId.of("UTC");store=open();store.importData(frozen)
        assertEquals(1L,store.dailyProgress(LocalDate.parse("2026-11-09")).count)
        assertEquals(0L,store.dailyProgress(LocalDate.parse("2026-11-08")).count)
    }
    @Test fun restoringAnOlderBackupKeepsConfiguredGoalForFutureDays() {
        val first=store.today();store.setDailyGoal(42)
        val backup=store.backup();store.close();context.deleteDatabase("learning.db")
        instant=instant.plusSeconds(3*86400);store=open();store.importData(backup)
        assertEquals(42,store.dailyGoal())
        assertEquals(42,store.dailyProgress(first.plusDays(1)).goal)
        assertEquals(42,store.dailyProgress(first).goal)
    }
    @Test fun everyModeCountsOneCompletionAndLegitimateWordRepetition() {
        val id=store.words().first().id
        for(mode in Mode.entries) {
            val before=store.dailyProgress().count;val key="run:${mode.name}:0:$id"
            val retry=mode in listOf(Mode.MATCH,Mode.TO_RU,Mode.TO_EL,Mode.LISTEN_EASY)
            store.apply("error-$mode",mode,mapOf(id to Points.fraction(-1,1)),"incorrect","run",key)
            assertEquals(before+if(retry) 0 else 1,store.dailyProgress().count)
            store.apply("correct-$mode",mode,mapOf(id to Points.fraction(1,1)),"correct","run",key)
            assertEquals(before+1,store.dailyProgress().count)
            store.apply("repeat-$mode",mode,mapOf(id to Points.fraction(1,1)),"correct","run","run:${mode.name}:1:$id")
            assertEquals(before+2,store.dailyProgress().count)
        }
    }
    @Test fun questionIdentityDeduplicatesDifferentAttemptIdsAcrossMidnightAndRestore() {
        val id=store.words().first().id;val first=store.today()
        store.apply("first-submit",Mode.WRITE,mapOf(id to Points.fraction(1,1)),"correct","session","session:WRITE:0:$id")
        instant=instant.plusSeconds(86400)
        assertFalse(store.apply("resubmitted-new-id",Mode.WRITE,mapOf(id to Points.fraction(1,1)),"correct","session","session:WRITE:0:$id"))
        assertEquals(Points.fraction(1,1),store.points(id,1))
        assertEquals(0L,store.dailyProgress().count)
        store.apply("next-occurrence",Mode.WRITE,mapOf(id to Points.fraction(1,1)),"correct","session","session:WRITE:1:$id")
        val backup=store.backup();store.close();context.deleteDatabase("learning.db");store=open();store.importData(backup);store.importData(backup)
        assertEquals(1L,store.dailyProgress(first).count);assertEquals(1L,store.dailyProgress().count)
    }
    @Test fun versionFiveMigrationRepairs118To110AndRetainsHistoryGoalsAndWordScores() {
        val ids=store.words().take(2).map { it.id };val day=store.today();store.setDailyGoal(200)
        repeat(110) { store.apply("completed-$it",Mode.MATCH,mapOf(ids[it%2] to Points.fraction(1,1)),"correct","old-run") }
        repeat(4) { store.apply("mismatch-$it",Mode.MATCH,ids.associateWith { Points.fraction(-1,1) },"incorrect","old-run") }
        val points=store.allPoints();val reviews=store.reviewIds(Mode.MATCH)
        val db=store.writableDatabase
        db.execSQL("UPDATE daily_counts SET count=118 WHERE day=?",arrayOf(day.toString()))
        db.execSQL("DROP TABLE daily_completions")
        db.execSQL("DELETE FROM metadata WHERE key='daily-stats-v2'")
        db.version=5;store.close();instant=instant.plusSeconds(86400);store=open()
        assertEquals(6,store.readableDatabase.version)
        assertEquals(DailyProgress(day,110,200),store.dailyProgress(day))
        assertEquals(200,store.dailyGoal());assertEquals(0L,store.dailyProgress().count)
        assertEquals(points,store.allPoints());assertEquals(reviews,store.reviewIds(Mode.MATCH))
        store.close();store=open();assertEquals(110L,store.dailyProgress(day).count)
    }
    @Test fun retryOnlyDaysRemainInHistoryWithZeroCompletedWords() {
        instant=instant.minusSeconds(86400)
        val day=store.today();val id=store.words().first().id
        store.apply("uncompleted",Mode.TO_RU,mapOf(id to Points.fraction(-1,1)),"incorrect","run","run:TO_RU:0:$id")
        assertEquals(0L,store.dailyProgress(day).count);assertEquals(day,store.firstActivityDay())
    }

}
