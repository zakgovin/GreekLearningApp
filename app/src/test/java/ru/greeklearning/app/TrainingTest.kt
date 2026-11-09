package ru.greeklearning.app

import android.speech.tts.TextToSpeech
import android.graphics.drawable.GradientDrawable
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class TrainingTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private lateinit var store: Store
    private var controller: ActivityController<MainActivity>?=null
    @Before fun setup() {
        context.getSharedPreferences("runtime",0).edit().clear().commit()
        context.deleteDatabase("learning.db");store=Store(context);store.seed()
        ShadowTextToSpeech.addLanguageAvailability(Locale("el", "GR"))
    }
    @After fun close() { controller?.pause()?.stop()?.destroy();store.close();context.getSharedPreferences("runtime",0).edit().clear().commit();context.deleteDatabase("learning.db") }
    private fun views(v: View): List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun all()=views(controller!!.get().window.decorView)
    private fun click(text: String) { all().filterIsInstance<Button>().first { it.text.toString()==text }.performClick() }
    private fun session(mode: Mode, list: List<Word>, audioHeard: Boolean = false) {
        val s=JSONObject().put("audioHeard",audioHeard).put("mode",mode.name).put("ids",JSONArray(list.map { it.id })).put("index",0).put("hints",0)
            .put("draft","").put("attempt",java.util.UUID.randomUUID().toString()).put("right",0).put("wrong",0).put("assisted",0).put("done",JSONArray()).put("feedback","")
            .put("initialTotals",JSONObject().apply { list.forEach { put(it.id,store.total(it).toString()) } })
        context.getSharedPreferences("runtime",0).edit().putString("session",s.toString()).commit()
        controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        controller!!.get().onInit(TextToSpeech.SUCCESS)
        click("Продолжить тренировку")
    }
    @Test fun threeHintsThenIndependentFourthLetterEarnQuarterAndSurviveRecreation() {
        val word=store.words().first { it.greek=="νερό" };session(Mode.WRITE,listOf(word))
        repeat(3) { click("? Подсказать следующую букву") }
        controller!!.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity::class.java).setup();controller!!.get().onInit(TextToSpeech.SUCCESS);click("Продолжить тренировку")
        assertEquals("νερ",all().filterIsInstance<EditText>().single().text.toString())
        all().filterIsInstance<EditText>().single().setText("νερό");click("Проверить")
        assertEquals(Points.fraction(1,4),store.points(word.id,1))
        finishAudio();click("Завершить")
        assertEquals(Points.fraction(1,4),store.points(word.id,1))
    }
    private fun saved() = JSONObject(context.getSharedPreferences("runtime",0).getString("session",null)!!)
    private fun audioToken() = saved().getString("answerAudioUtterance")
    private fun finishAudio(token: String = audioToken()) {
        shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).utteranceProgressListener!!.onDone(token)
    }
    @Test fun correctChoiceWaitsForSpeechCompletionThenAdvancesAndLastWordShowsSummary() {
        val list=store.words().filter { it.category=="noun" }.take(2)
        session(Mode.TO_RU,list)
        val firstButton = all().filterIsInstance<Button>().first { it.text.toString()==list[0].russian.first() }
        firstButton.performClick()
        assertEquals(0,saved().getInt("index"))
        assertFalse(all().filterIsInstance<Button>().any { it.text.toString()=="Далее" })
        assertEquals(list[0].greek,shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
        assertEquals(Points.fraction(1,1),store.points(list[0].id,0))
        val firstToken=audioToken();finishAudio(firstToken)
        assertTrue(all().filterIsInstance<android.widget.TextView>().any { it.text.toString()==list[1].display })
        finishAudio(firstToken) // a repeated completion must not skip the second word
        firstButton.performClick() // a stale click must not answer the next question
        assertEquals(1,saved().getInt("index"));assertEquals(Points.ZERO,store.points(list[1].id,0))
        click(list[1].russian.first())
        assertFalse(all().filterIsInstance<android.widget.TextView>().any { it.text.toString()=="Тренировка завершена" })
        finishAudio()
        assertTrue(all().filterIsInstance<android.widget.TextView>().any { it.text.toString()=="Тренировка завершена" })
        assertFalse(all().filterIsInstance<Button>().any { it.text.toString()=="Далее" })
        assertEquals(Points.fraction(1,1),store.points(list[1].id,0))
    }
    @Test fun translationChoiceHasHeaderCloseWithoutSkipAndResumesSameQuestion() {
        val list=store.words().filter { it.category=="noun" }.take(2)
        store.apply("seed-close",Mode.TO_RU,mapOf(list[0].id to Points.fraction(3,1)),"correct")
        session(Mode.TO_RU,list)
        assertFalse(all().filterIsInstance<Button>().any { it.text.toString().startsWith("Пропустить") || it.text.toString()=="Выйти и сохранить" })
        val wrongId=saved().getJSONArray("options").let { a -> (0 until a.length()).map { a.getString(it) }.first { it!=list[0].id } }
        val wrong=store.words().first { it.id==wrongId }
        click(wrong.russian.first())
        val close=all().filterIsInstance<android.widget.ImageButton>().single { it.contentDescription=="Выйти и сохранить" }
        close.performClick()
        assertTrue(all().filterIsInstance<Button>().any { it.text.toString()=="Продолжить тренировку" })
        click("Продолжить тренировку")
        assertEquals(0,saved().getInt("index"))
        assertTrue(all().filterIsInstance<android.widget.TextView>().any { it.text.toString()==list[0].display })
        assertFalse(all().filterIsInstance<Button>().first { it.text.toString()==wrong.russian.first() }.isEnabled)
        assertEquals(Points.fraction(3,1),store.points(list[0].id,0))
    }
    @Test fun allTrainingModesHaveTopRightCloseAndNoBottomExit() {
        val list=store.words().filter { it.category=="noun" }.take(2)
        for(mode in Mode.entries) {
            session(mode,list,audioHeard=true)
            val close=all().filterIsInstance<android.widget.ImageButton>().single { it.contentDescription=="Выйти и сохранить" }
            val header=close.parent as android.widget.LinearLayout
            assertSame(close,header.getChildAt(header.childCount-1))
            assertEquals(View.LAYOUT_DIRECTION_LTR,header.layoutDirection)
            assertFalse(all().filterIsInstance<Button>().any { it.text.toString()=="Выйти и сохранить" || it.text.toString().startsWith("Пропустить блок") })
            close.performClick();click("Продолжить тренировку")
            assertEquals(mode.name,saved().getString("mode"));assertEquals(0,saved().getInt("index"))
            controller!!.pause().stop().destroy();controller=null
        }
    }
    @Test fun delayedAudioEventFromClosedExerciseCannotOverwriteNewTraining() {
        val list=store.words().filter { it.category=="noun" }.take(2)
        session(Mode.LISTEN_HARD,list,audioHeard=true)
        val oldActivity=controller!!.get();val oldToken="question:"+saved().getString("attempt")
        all().filterIsInstance<android.widget.ImageButton>().single { it.contentDescription=="Выйти и сохранить" }.performClick()
        controller!!.pause().stop().destroy();controller=null
        session(Mode.SPEAK,list)
        oldActivity.onUtteranceFinished(oldToken);oldActivity.onUtteranceFailed(oldToken)
        assertEquals(Mode.SPEAK.name,saved().getString("mode"))
    }
    @Test fun matchingCloseRetainsSolvedPairsAndHasNoSkip() {
        val list=store.words().filter { it.category=="noun" }.take(2);session(Mode.MATCH,list)
        click(list[0].display);click(list[0].russian.first())
        assertFalse(all().filterIsInstance<Button>().any { it.text.toString().startsWith("Пропустить") })
        all().filterIsInstance<android.widget.ImageButton>().single { it.contentDescription=="Выйти и сохранить" }.performClick()
        click("Продолжить тренировку")
        assertFalse(all().filterIsInstance<Button>().first { it.text.toString()==list[0].display }.isEnabled)
        assertEquals(Points.fraction(1,1),store.points(list[0].id,0))
        finishAudio()
        click(list[1].display);click(list[1].russian.first());finishAudio()
        assertEquals(Points.fraction(1,1),store.points(list[0].id,0))
    }
    @Test fun chooseWordHasNoSkipButCanCloseAndResume() {
        val list=store.words().filter { it.category=="noun" }.take(2);session(Mode.TO_EL,list)
        assertFalse(all().filterIsInstance<Button>().any { it.text.toString().startsWith("Пропустить") })
        all().filterIsInstance<android.widget.ImageButton>().single { it.contentDescription=="Выйти и сохранить" }.performClick()
        click("Продолжить тренировку")
        assertEquals(Mode.TO_EL.name,saved().getString("mode"))
        assertEquals(list.map { it.id },saved().getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertFalse(all().filterIsInstance<Button>().any { it.text.toString().startsWith("Пропустить") })
    }
    @Test fun chooseWordMistakeAtZeroThenCorrectionShowsHalfPointInSummary() {
        val word=store.words().first { it.greek=="νερό" };session(Mode.TO_EL,listOf(word))
        val wrongId=saved().getJSONArray("options").let { a -> (0 until a.length()).map { a.getString(it) }.first { it!=word.id } }
        click(store.words().first { it.id==wrongId }.display)
        assertEquals(Points.ZERO,store.points(word.id,0))
        controller!!.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        controller!!.get().onInit(TextToSpeech.SUCCESS);click("Продолжить тренировку")
        click(word.display);finishAudio()
        assertEquals(Points.fraction(1,2),store.points(word.id,0))
        assertEquals("+0,5%",all().filterIsInstance<android.widget.TextView>().single { it.tag=="change:${word.id}" }.text.toString())
    }
    @Test fun skipIsAbsentInWritingAndInitialListeningButRetainedInAdvancedAndSpeaking() {
        val word=store.words().first { it.greek=="νερό" }
        for(mode in listOf(Mode.WRITE,Mode.LISTEN_EASY,Mode.LISTEN_HARD,Mode.SPEAK)) {
            session(mode,listOf(word),audioHeard=true)
            val hasSkip=all().filterIsInstance<Button>().any { it.text.toString().startsWith("Пропустить") }
            assertEquals(mode in listOf(Mode.LISTEN_HARD,Mode.SPEAK),hasSkip)
            if(mode==Mode.WRITE) {
                click("Проверить")
                assertEquals("Введите ответ",org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
                assertEquals(0,saved().getInt("index"));assertEquals(Points.ZERO,store.points(word.id,1))
            }
            controller!!.pause().stop().destroy();controller=null
        }
    }
    @Test fun wrongChoicesStayRedAndQuestionAllowsRetriesUntilCorrect() {
        val word=store.words().first { it.greek=="νερό" }
        store.apply("seed-choice",Mode.TO_RU,mapOf(word.id to Points.fraction(3,1)),"correct")
        session(Mode.TO_RU,listOf(word))
        val options=saved().getJSONArray("options")
        val wrong=(0 until options.length()).map { id -> store.words().first { it.id==options.getString(id) } }.filter { it.id!=word.id }.take(2)
        val stale=all().filterIsInstance<Button>().first { it.text.toString()==wrong[0].russian.first() }
        stale.performClick();stale.performClick()
        assertEquals(Points.fraction(3,1),store.points(word.id,0))
        val red=all().filterIsInstance<Button>().first { it.text.toString()==wrong[0].russian.first() }
        assertFalse(red.isEnabled)
        assertEquals(android.graphics.Color.rgb(252,218,215),(red.background as GradientDrawable).color!!.defaultColor)
        assertFalse(all().filterIsInstance<Button>().any { it.text.toString()=="Далее" || it.text.toString()=="Послушать правильный ответ" })
        click(wrong[1].russian.first())
        assertEquals(Points.fraction(2,1),store.points(word.id,0));assertEquals(0,saved().getInt("index"))
        click(word.russian.first())
        assertEquals(Points.fraction(2,1),store.points(word.id,0));assertTrue(saved().getBoolean("answerAudioPending"))
        finishAudio();assertEquals(1,saved().getInt("index"))
    }
    @Test fun speechFailureKeepsQuestionAndRetryDoesNotCreditAgain() {
        val word=store.words().first { it.greek=="νερό" };session(Mode.TO_RU,listOf(word))
        click(word.russian.first());val oldToken=audioToken()
        controller!!.get().onUtteranceFailed(oldToken)
        assertEquals(0,saved().getInt("index"));assertEquals(Points.fraction(1,1),store.points(word.id,0))
        click("Повторить озвучивание");val newToken=audioToken();assertNotEquals(oldToken,newToken)
        finishAudio(oldToken);assertEquals(0,saved().getInt("index"))
        finishAudio(newToken);assertEquals(1,saved().getInt("index"))
        assertEquals(Points.fraction(1,1),store.points(word.id,0))
    }
    @Test fun suspendedAnswerPlaybackResumesWithoutDuplicateCredit() {
        val word=store.words().first { it.greek=="νερό" };session(Mode.TO_RU,listOf(word))
        click(word.russian.first());val oldToken=audioToken()
        controller!!.pause().stop().destroy()
        controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        controller!!.get().onInit(TextToSpeech.SUCCESS);click("Продолжить тренировку")
        val newToken=audioToken();assertNotEquals(oldToken,newToken)
        finishAudio(oldToken);assertEquals(0,saved().getInt("index"))
        finishAudio(newToken);assertEquals(1,saved().getInt("index"))
        assertEquals(Points.fraction(1,1),store.points(word.id,0))
    }
    @Test fun wrongAnswerStillShowsCorrectionBeforeAdvancing() {
        val list=store.words().filter { it.category=="noun" }.take(2)
        session(Mode.WRITE,list)
        all().filterIsInstance<EditText>().single().setText("ошибка");click("Проверить")
        assertTrue(all().filterIsInstance<Button>().any { it.text.toString()=="Далее" })
        click("Далее")
        assertTrue(all().filterIsInstance<android.widget.TextView>().any { it.text.toString()==list[1].russian.first() })
    }
    @Test fun choosingGreekWordCreditsTranslation() {
        val word=store.words().first { it.greek=="νερό" };session(Mode.TO_EL,listOf(word))
        click(word.display);assertEquals(Points.fraction(1,1),store.points(word.id,0))
    }
    @Test fun choosingRussianTranslationCreditsTranslation() {
        val word=store.words().first { it.greek=="νερό" };session(Mode.TO_RU,listOf(word))
        click(word.russian.first());assertEquals(Points.fraction(1,1),store.points(word.id,0))
    }
    @Test fun initialListeningChoiceCreditsSharedListeningSkill() {
        val word=store.words().first { it.greek=="νερό" };session(Mode.LISTEN_EASY,listOf(word),audioHeard=true)
        click(word.russian.first());assertEquals(Points.fraction(1,1),store.points(word.id,2))
    }
    @Test fun correctWritingTurnsInputGreenSpeaksAndWaitsBeforeNextQuestion() {
        val list=listOf(store.words().first { it.greek=="νερό" },store.words().first { it.greek=="σπίτι" })
        session(Mode.WRITE,list)
        all().filterIsInstance<EditText>().single().setText(list[0].greek);click("Проверить")
        val green=all().filterIsInstance<EditText>().single()
        assertEquals(android.graphics.Color.rgb(211,233,215),(green.background as GradientDrawable).color!!.defaultColor)
        assertFalse(green.isEnabled);assertEquals(list[0].greek,green.text.toString())
        assertEquals(list[0].greek,shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
        assertEquals(0,saved().getInt("index"));assertEquals(Points.fraction(1,1),store.points(list[0].id,1))
        val token=audioToken();finishAudio(token)
        assertEquals(1,saved().getInt("index"));assertEquals("",all().filterIsInstance<EditText>().single().text.toString())
        assertTrue(all().filterIsInstance<EditText>().single().isEnabled)
        finishAudio(token);assertEquals(1,saved().getInt("index"))
        all().filterIsInstance<EditText>().single().setText(list[1].greek);click("Проверить");finishAudio()
        assertTrue(all().filterIsInstance<android.widget.TextView>().any { it.text.toString()=="Тренировка завершена" })
    }
    @Test fun writingPlaybackFailureAndResumeNeverDuplicateFractionalCredit() {
        val word=store.words().first { it.greek=="νερό" };session(Mode.WRITE,listOf(word))
        repeat(3) { click("? Подсказать следующую букву") }
        all().filterIsInstance<EditText>().single().setText(word.greek);click("Проверить")
        controller!!.get().onUtteranceFailed(audioToken());click("Повторить озвучивание")
        controller!!.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        controller!!.get().onInit(TextToSpeech.SUCCESS);click("Продолжить тренировку")
        assertFalse(all().filterIsInstance<EditText>().single().isEnabled)
        finishAudio();assertEquals(Points.fraction(1,4),store.points(word.id,1))
    }
    @Test fun fullHintEarnsZero() {
        val word=store.words().first { it.greek=="νερό" };session(Mode.WRITE,listOf(word))
        repeat(4) { click("? Подсказать следующую букву") };click("Проверить")
        assertEquals(Points.ZERO,store.points(word.id,1))
    }
    @Test fun summaryShowsGreekWordsOverallProgressAndActualNegativeAndFractionalChanges() {
        val a=store.words().first { it.greek=="σπίτι" };val b=store.words().first { it.greek=="νερό" }
        store.apply("summary-a-translation",Mode.TO_RU,mapOf(a.id to Points.fraction(2,1)),"correct")
        store.apply("summary-a-writing",Mode.WRITE,mapOf(a.id to Points.fraction(2,1)),"correct")
        store.apply("summary-b",Mode.TO_RU,mapOf(b.id to Points.fraction(3,1)),"correct")
        session(Mode.WRITE,listOf(a,b))
        all().filterIsInstance<EditText>().single().setText("ошибка");click("Проверить");click("Далее")
        repeat(3) { click("? Подсказать следующую букву") }
        all().filterIsInstance<EditText>().single().setText(b.greek);click("Проверить");finishAudio()
        fun result(tag: String)=all().filterIsInstance<android.widget.TextView>().single { it.tag==tag }.text.toString()
        assertEquals("3%",result("current:${a.id}"));assertEquals("−1%",result("change:${a.id}"))
        assertEquals("3,25%",result("current:${b.id}"));assertEquals("+0,25%",result("change:${b.id}"))
        assertTrue(all().filterIsInstance<android.widget.TextView>().any { it.text.toString()==a.display })
        assertFalse(all().filterIsInstance<android.widget.TextView>().any { it.text.toString().startsWith("Самостоятельно:") })
        controller!!.pause().stop().destroy()
        controller=Robolectric.buildActivity(MainActivity::class.java).setup();controller!!.get().onInit(TextToSpeech.SUCCESS);click("Продолжить тренировку")
        assertEquals("−1%",result("change:${a.id}"));assertEquals("+0,25%",result("change:${b.id}"))
    }
    @Test fun summaryUsesActualClampedChangeAndDoesNotPretendCappedAnswerAddedOne() {
        val word=store.words().first { it.greek=="νερό" }
        store.apply("cap-summary",Mode.WRITE,mapOf(word.id to Points.MAX),"correct")
        store.apply("other-skill-summary",Mode.TO_RU,mapOf(word.id to Points.fraction(2,1)),"correct")
        session(Mode.WRITE,listOf(word))
        all().filterIsInstance<EditText>().single().setText(word.greek);click("Проверить");finishAudio()
        assertEquals("27%",all().filterIsInstance<android.widget.TextView>().single { it.tag=="current:${word.id}" }.text.toString())
        assertEquals("0%",all().filterIsInstance<android.widget.TextView>().single { it.tag=="change:${word.id}" }.text.toString())
    }
    @Test fun legacyCompletedSessionShowsCurrentProgressWithoutInventingChange() {
        val word=store.words().first { it.greek=="νερό" };session(Mode.WRITE,listOf(word))
        controller!!.pause().stop().destroy()
        val old=saved().put("index",1);old.remove("initialTotals")
        context.getSharedPreferences("runtime",0).edit().putString("session",old.toString()).commit()
        controller=Robolectric.buildActivity(MainActivity::class.java).setup();controller!!.get().onInit(TextToSpeech.SUCCESS);click("Продолжить тренировку")
        assertEquals("0%",all().filterIsInstance<android.widget.TextView>().single { it.tag=="current:${word.id}" }.text.toString())
        assertEquals("—",all().filterIsInstance<android.widget.TextView>().single { it.tag=="change:${word.id}" }.text.toString())
    }
    @Test fun matchingSpeaksEachCorrectWordAndWaitsBeforeFinalSummary() {
        val list=store.words().filter { it.category=="noun" }.take(2);session(Mode.MATCH,list)
        click(list[0].display);click(list[0].russian.first())
        assertEquals(list[0].greek,shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
        assertFalse(all().filterIsInstance<Button>().first { it.text.toString()==list[1].display }.isEnabled)
        val token=audioToken();finishAudio(token)
        assertTrue(all().filterIsInstance<Button>().first { it.text.toString()==list[1].display }.isEnabled)
        finishAudio(token);assertEquals(0,saved().getInt("index"))
        click(list[1].display);click(list[1].russian.first())
        assertEquals(list[1].greek,shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
        assertEquals(0,saved().getInt("index"));finishAudio()
        assertEquals(2,saved().getInt("index"))
        list.forEach { assertEquals(Points.fraction(1,1),store.points(it.id,0)) }
    }
    @Test fun matchingAudioFailureContinuesSilentlyAcrossPairsAndRecreation() {
        val list=store.words().filter { it.category=="noun" }.take(3);session(Mode.MATCH,list)
        click(list[0].display);click(list[0].russian.first());val old=audioToken()
        controller!!.get().onUtteranceFailed(old)
        assertTrue(saved().getBoolean("matchAudioDisabled"))
        assertFalse(saved().optBoolean("answerAudioPending"))
        assertFalse(all().filterIsInstance<Button>().any { it.text.toString() in listOf("Повторить озвучивание","Продолжить без звука") })
        finishAudio(old);assertEquals(0,saved().getInt("index"))
        click(list[1].display);click(list[1].russian.first())
        assertFalse(saved().optBoolean("answerAudioPending"))
        controller!!.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        controller!!.get().onInit(TextToSpeech.SUCCESS);click("Продолжить тренировку")
        click(list[2].display);click(list[2].russian.first())
        assertEquals(3,saved().getInt("index"))
        list.forEach { assertEquals(Points.fraction(1,1),store.points(it.id,0)) }
    }
    @Test fun matchingHungSpeechTimesOutWithoutBlockingNextPair() {
        val list=store.words().filter { it.category=="noun" }.take(2);session(Mode.MATCH,list)
        // Suppress Robolectric's automatic completion to simulate a stalled Android TTS service.
        ShadowTextToSpeech.getLastTextToSpeechInstance().setOnUtteranceProgressListener(null)
        click(list[0].display);click(list[0].russian.first())
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(16))
        assertTrue(saved().getBoolean("matchAudioDisabled"))
        click(list[1].display);click(list[1].russian.first())
        assertEquals(2,saved().getInt("index"))
    }
    @Test fun matchingShowsFivePairsThenRemainingWordsWithoutChangingSessionSize() {
        val list=store.words().filter { it.category=="noun" }.take(7)
        session(Mode.MATCH,list)
        fun visibleGreek()=all().filterIsInstance<Button>().filter { button -> list.any { it.display==button.text.toString() } }
        assertEquals(5,visibleGreek().size)
        assertFalse(visibleGreek().any { it.text.toString()==list[5].display })
        list.take(5).forEach { click(it.display);click(it.russian.first());finishAudio() }
        assertEquals(5,saved().getInt("index"));assertEquals(7,saved().getJSONArray("ids").length())
        assertEquals(2,visibleGreek().size)
        list.drop(5).forEach { click(it.display);click(it.russian.first());finishAudio() }
        assertEquals(7,saved().getInt("index"))
        assertTrue(all().filterIsInstance<android.widget.TextView>().any { it.text.toString()=="Тренировка завершена" })
        list.forEach { assertEquals(Points.fraction(1,1),store.points(it.id,0)) }
    }
    @Test fun legacyEightPairSessionKeepsAlreadySolvedWordsBeyondFirstFive() {
        val list=store.words().filter { it.category=="noun" }.take(8)
        session(Mode.MATCH,list)
        controller!!.pause().stop().destroy()
        val state=saved().put("done",JSONArray(list.take(6).map { it.id })).put("right",6)
            .put("leftOrder",JSONArray(list.map { it.id })).put("rightOrder",JSONArray(list.map { it.id }))
        context.getSharedPreferences("runtime",0).edit().putString("session",state.toString()).commit()
        list.take(6).forEach { store.apply("legacy-${it.id}",Mode.MATCH,mapOf(it.id to Points.fraction(1,1)),"correct") }
        controller=Robolectric.buildActivity(MainActivity::class.java).setup();controller!!.get().onInit(TextToSpeech.SUCCESS);click("Продолжить тренировку")
        assertEquals(5,saved().getInt("index"))
        assertFalse(all().filterIsInstance<Button>().first { it.text.toString()==list[5].display }.isEnabled)
        list.drop(6).forEach { click(it.display);click(it.russian.first());finishAudio() }
        assertEquals(8,saved().getInt("index"))
        list.forEach { assertEquals(Points.fraction(1,1),store.points(it.id,0)) }
    }
    @Test fun wrongMatchingPairFlashesBothCellsThenResetsWithoutExtraPenalty() {
        val list=store.words().filter { it.category=="noun" }.take(3)
        list.forEach { store.apply("flash-seed-${it.id}",Mode.MATCH,mapOf(it.id to Points.fraction(2,1)),"correct") }
        session(Mode.MATCH,list)
        click(list[2].display);click(list[2].russian.first());finishAudio()
        val left=all().filterIsInstance<Button>().first { it.text.toString()==list[0].display }
        val right=all().filterIsInstance<Button>().first { it.text.toString()==list[1].russian.first() }
        left.performClick();right.performClick()
        for(b in listOf(left,right)) {
            assertEquals(android.graphics.Color.rgb(252,218,215),(b.background as GradientDrawable).color!!.defaultColor)
            assertFalse(b.isEnabled)
        }
        right.performClick();left.performClick()
        assertEquals(Points.fraction(1,1),store.points(list[0].id,0));assertEquals(Points.fraction(1,1),store.points(list[1].id,0))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        for(text in listOf(list[0].display,list[1].russian.first())) {
            val b=all().filterIsInstance<Button>().first { it.text.toString()==text }
            assertTrue(b.isEnabled);assertTrue(b.background is android.graphics.drawable.RippleDrawable)
        }
        assertFalse(all().filterIsInstance<Button>().first { it.text.toString()==list[2].display }.isEnabled)
        click(list[0].display);click(list[0].russian.first())
        assertEquals(Points.fraction(2,1),store.points(list[0].id,0));assertTrue(saved().getBoolean("answerAudioPending"))
    }
    @Test fun closingDuringMismatchFlashDoesNotReplaceHomeOrLosePenalty() {
        val list=store.words().filter { it.category=="noun" }.take(2);session(Mode.MATCH,list)
        click(list[0].display);click(list[1].russian.first())
        all().filterIsInstance<android.widget.ImageButton>().single { it.contentDescription=="Выйти и сохранить" }.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        assertTrue(all().filterIsInstance<Button>().any { it.text.toString()=="Продолжить тренировку" })
        click("Продолжить тренировку")
        assertTrue(all().filterIsInstance<Button>().first { it.text.toString()==list[0].display }.isEnabled)
        assertEquals(1,saved().getInt("wrong"))
    }
    @Test fun wrongMatchingPairDeductsFromBothWords() {
        val list=store.words().filter { it.category=="noun" }.take(2)
        list.forEach { store.apply("seed-${it.id}",Mode.MATCH,mapOf(it.id to Points.fraction(2,1)),"correct") }
        session(Mode.MATCH,list)
        click(list[0].display);click(list[1].russian.first())
        list.forEach { assertEquals(Points.fraction(1,1),store.points(it.id,0)) }
    }
    @Test fun advancedListeningHighlightsRussianInputAndSpeaksGreekBeforeAdvancing() {
        val list=listOf(store.words().first { it.greek=="νερό" },store.words().first { it.greek=="σπίτι" })
        session(Mode.LISTEN_HARD,list,audioHeard=true)
        all().filterIsInstance<EditText>().single().setText("вода");click("Проверить")
        val field=all().filterIsInstance<EditText>().single()
        assertEquals("вода",field.text.toString());assertFalse(field.isEnabled)
        assertEquals(android.graphics.Color.rgb(211,233,215),(field.background as GradientDrawable).color!!.defaultColor)
        assertEquals("νερό",shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
        assertEquals(0,saved().getInt("index"))
        assertFalse(all().filterIsInstance<Button>().single { it.text.toString()=="▶ Прослушать слово" }.isEnabled)
        assertFalse(all().filterIsInstance<Button>().any { it.text.toString()=="Проверить" || it.text.toString().startsWith("Пропустить") })
        val token=audioToken();finishAudio(token)
        assertEquals(1,saved().getInt("index"));assertTrue(all().filterIsInstance<EditText>().single().isEnabled)
        assertEquals("",all().filterIsInstance<EditText>().single().text.toString())
        finishAudio(token);assertEquals(1,saved().getInt("index"))
        assertEquals(Points.fraction(1,1),store.points(list[0].id,2))
    }
    @Test fun advancedListeningPlaybackRetryKeepsAcceptedInputAndAwardsOnce() {
        val word=store.words().first { it.greek=="νερό" };session(Mode.LISTEN_HARD,listOf(word),audioHeard=true)
        all().filterIsInstance<EditText>().single().setText("вода");click("Проверить")
        controller!!.get().onUtteranceFailed(audioToken());click("Повторить озвучивание")
        controller!!.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        controller!!.get().onInit(TextToSpeech.SUCCESS);click("Продолжить тренировку")
        assertEquals("вода",all().filterIsInstance<EditText>().single().text.toString());assertFalse(all().filterIsInstance<EditText>().single().isEnabled)
        finishAudio()
        assertEquals(1,saved().getInt("index"));assertEquals(Points.fraction(1,1),store.points(word.id,2))
    }
    @Test fun advancedListeningWrongAnswerIsVisibleAfterRecreationAndClearedForNextWord() {
        val list=listOf(store.words().first { it.greek=="νερό" },store.words().first { it.greek=="σπίτι" })
        session(Mode.LISTEN_HARD,list,audioHeard=true)
        all().filterIsInstance<EditText>().single().setText("таблетко");click("Проверить")
        fun shows(text: String)=all().filterIsInstance<android.widget.TextView>().any { it.text.toString()==text }
        assertTrue(shows("Ваш ответ: таблетко"))
        assertTrue(shows("${list[0].display} — ${list[0].russian.joinToString(", ")}"))
        assertEquals(1,saved().getInt("wrong"))
        controller!!.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        controller!!.get().onInit(TextToSpeech.SUCCESS);click("Продолжить тренировку")
        assertTrue(shows("Ваш ответ: таблетко"));assertEquals(1,saved().getInt("wrong"))
        click("Далее")
        assertEquals("",all().filterIsInstance<EditText>().single().text.toString())
        click("Пропустить · −1%")
        assertFalse(all().filterIsInstance<android.widget.TextView>().any { it.text.toString().startsWith("Ваш ответ:") })
    }
    @Test fun writingAcceptsArticleWithAndWithoutHintsAndKeepsHintCredit() {
        val word=store.words().first { it.greek=="νερό" }
        session(Mode.WRITE,listOf(word,word))
        all().filterIsInstance<EditText>().single().setText("το νερό");click("Проверить")
        assertTrue(saved().getBoolean("answerAudioPending"))
        assertEquals(Points.fraction(1,1),store.points(word.id,1));finishAudio()
        click("? Подсказать следующую букву")
        all().filterIsInstance<EditText>().single().setText("το νερό");click("Проверить")
        assertTrue(saved().getBoolean("answerAudioPending"))
        assertEquals(Points.fraction(7,4),store.points(word.id,1))
        assertEquals(0,saved().getInt("wrong"))
    }
    @Test fun advancedListeningAcceptsRussianTranslation() {
        val word=store.words().first { it.greek=="νερό" };session(Mode.LISTEN_HARD,listOf(word),audioHeard=true)
        all().filterIsInstance<EditText>().single().setText(" ВОДА ");click("Проверить")
        assertEquals(Points.fraction(1,1),store.points(word.id,2))
    }
    @Test fun listeningWithoutSuccessfulPlaybackDoesNotCreditOrPenalizeTypedAnswer() {
        val word=store.words().first { it.greek=="νερό" };session(Mode.LISTEN_HARD,listOf(word))
        all().filterIsInstance<EditText>().single().setText("вода");click("Проверить")
        assertEquals(Points.ZERO,store.points(word.id,2))
        assertTrue(all().filterIsInstance<EditText>().isNotEmpty())
    }
    @Test fun pronunciationAllowsOnlineGreekAndLanguageErrorDoesNotPenalizeOrBlockRetry() {
        shadowOf(context).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val service=android.content.pm.ResolveInfo().apply {
            serviceInfo=android.content.pm.ServiceInfo().apply { packageName="test.recognition";name="GreekRecognitionService" }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(android.content.Intent(android.speech.RecognitionService.SERVICE_INTERFACE),service)
        context.getSharedPreferences("settings",0).edit().putBoolean("speechConsent",true).commit()
        val word=store.words().first { it.greek=="φίλος" && it.russian.first()=="друг" }
        session(Mode.SPEAK,listOf(word))
        shadowOf(controller!!.get()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        assertTrue(android.speech.SpeechRecognizer.isRecognitionAvailable(controller!!.get()))
        click("● Записать ответ")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val recognizer=shadowOf(org.robolectric.shadows.ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertEquals("el-GR",recognizer.lastRecognizerIntent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE))
        assertFalse(recognizer.lastRecognizerIntent.getBooleanExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE,true))
        recognizer.triggerOnError(android.speech.SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.findViewById<android.widget.TextView>(android.R.id.message).text.toString().contains("не поддерживает греческий"))
        assertEquals(0,saved().getInt("wrong"));assertEquals("",saved().getString("feedback"))
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        click("● Записать ответ")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val retry=shadowOf(org.robolectric.shadows.ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        retry.triggerOnResults(android.os.Bundle().apply { putStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION,arrayListOf("ο φίλος")) })
        assertEquals(Points.fraction(1,1),store.points(word.id,3))
        assertEquals(0,saved().getInt("index"));assertTrue(saved().getBoolean("correct"))
        fun shows(text: String)=all().filterIsInstance<android.widget.TextView>().any { it.text.toString()==text }
        assertTrue(shows("Верно · +1%"));assertTrue(shows("${word.display} — ${word.russian.joinToString(", ")}"))
        assertTrue(shows("Распознано: ο φίλος"))
        controller!!.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        controller!!.get().onInit(TextToSpeech.SUCCESS);click("Продолжить тренировку")
        assertTrue(shows("Верно · +1%"));assertEquals(Points.fraction(1,1),store.points(word.id,3))
        click("Далее");assertEquals(1,saved().getInt("index"))
    }
    @Test fun pronunciationSkipDeductsAndShowsAnswerWithoutMicrophone() {
        val word=store.words().first { it.greek=="νερό" }
        store.apply("seed",Mode.SPEAK,mapOf(word.id to Points.fraction(2,1)),"correct")
        session(Mode.SPEAK,listOf(word));click("Пропустить · −1%")
        assertEquals(Points.fraction(1,1),store.points(word.id,3))
        assertTrue(all().filterIsInstance<android.widget.TextView>().any { it.text.toString().contains(word.display) })
    }
    @Test fun dailyPracticeCountsRealAttemptsButNotSpeechCompletionOrRecreation() {
        val list=listOf(store.words().first { it.greek=="νερό" },store.words().first { it.greek=="σπίτι" })
        session(Mode.TO_EL,list)
        val options=saved().getJSONArray("options")
        val wrong=(0 until options.length()).map { options.getString(it) }.first { it!=list[0].id }
        click(store.words().first { it.id==wrong }.display)
        assertEquals(0L,store.dailyProgress().count)
        click(list[0].display);assertEquals(1L,store.dailyProgress().count)
        val token=audioToken();finishAudio(token);finishAudio(token)
        assertEquals(1L,store.dailyProgress().count)
        all().filterIsInstance<android.widget.ImageButton>().single { it.contentDescription=="Выйти и сохранить" }.performClick()
        assertEquals("1",all().filterIsInstance<android.widget.TextView>().single { it.tag=="daily-count" }.text.toString())
        controller!!.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals("1",all().filterIsInstance<android.widget.TextView>().single { it.tag=="daily-count" }.text.toString())
    }
    @Test fun wrongMatchingPairThenBothCorrectPairsCountsOnlyTwoWords() {
        val list=store.words().filter { it.category=="noun" }.take(2);session(Mode.MATCH,list)
        click(list[0].display);click(list[1].russian.first())
        assertEquals(0L,store.dailyProgress().count)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        click(list[0].display);click(list[0].russian.first());assertEquals(1L,store.dailyProgress().count);finishAudio()
        click(list[1].display);click(list[1].russian.first());assertEquals(2L,store.dailyProgress().count);finishAudio()
        assertEquals(2L,store.dailyProgress().count)
    }
    @Test fun allChoiceModesRetrySameWordWithoutInflatingDailyCount() {
        val list=listOf(store.words().first { it.greek=="νερό" },store.words().first { it.greek=="σπίτι" })
        for(mode in listOf(Mode.TO_RU,Mode.TO_EL,Mode.LISTEN_EASY)) {
            val before=store.dailyProgress().count
            session(mode,list,audioHeard=true)
            val options=saved().getJSONArray("options")
            val wrongId=(0 until options.length()).map { options.getString(it) }.first { it!=list[0].id }
            val wrong=store.words().first { it.id==wrongId }
            click(if(mode==Mode.TO_EL) wrong.display else wrong.russian.first())
            assertEquals(before,store.dailyProgress().count)
            click(if(mode==Mode.TO_EL) list[0].display else list[0].russian.first())
            assertEquals(before+1,store.dailyProgress().count);finishAudio()
            assertEquals(before+1,store.dailyProgress().count)
            controller!!.pause().stop().destroy();controller=null
        }
    }
    @Test fun translationChoiceAwardsHalfAfterOneErrorAndRestoresThatState() {
        for(mode in listOf(Mode.TO_RU,Mode.TO_EL)) {
            val word=store.words().first { it.greek=="νερό" }
            val before=store.points(word.id,0)
            session(mode,listOf(word))
            val options=saved().getJSONArray("options")
            val wrongId=(0 until options.length()).map { options.getString(it) }.first { it!=word.id }
            val wrong=store.words().first { it.id==wrongId }
            click(if(mode==Mode.TO_EL) wrong.display else wrong.russian.first())
            assertEquals(before,store.points(word.id,0))
            controller!!.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity::class.java).setup()
            controller!!.get().onInit(TextToSpeech.SUCCESS);click("Продолжить тренировку")
            click(if(mode==Mode.TO_EL) word.display else word.russian.first())
            assertEquals(before+Points.fraction(1,2),store.points(word.id,0))
            finishAudio();assertEquals(before+Points.fraction(1,2),store.points(word.id,0))
            assertEquals("+0,5%",all().filterIsInstance<android.widget.TextView>().single { it.tag=="change:${word.id}" }.text.toString())
            controller!!.pause().stop().destroy();controller=null
        }
    }
    @Test fun translationMultipleWrongChoicesNeverCostMoreThanOnePoint() {
        for(mode in listOf(Mode.TO_RU,Mode.TO_EL)) {
            val word=store.words().first { it.greek=="σπίτι" }
            store.apply("baseline-$mode",Mode.TO_RU,mapOf(word.id to Points.fraction(5,1)),"correct")
            val before=store.points(word.id,0);session(mode,listOf(word))
            val options=saved().getJSONArray("options")
            val wrong=(0 until options.length()).map { options.getString(it) }.filter { it!=word.id }.take(4)
            assertTrue(wrong.size>=3)
            wrong.forEachIndexed { index,id ->
                val option=store.words().first { it.id==id }
                click(if(mode==Mode.TO_EL) option.display else option.russian.first())
                assertEquals(before+if(index==0) Points.ZERO else Points.fraction(-1,1),store.points(word.id,0))
            }
            click(if(mode==Mode.TO_EL) word.display else word.russian.first());finishAudio()
            assertEquals(before-Points.fraction(1,1),store.points(word.id,0))
            assertEquals("−1%",all().filterIsInstance<android.widget.TextView>().single { it.tag=="change:${word.id}" }.text.toString())
            controller!!.pause().stop().destroy();controller=null
        }
    }

}
