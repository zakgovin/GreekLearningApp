package ru.greeklearning.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SummaryLayoutTest {
    private fun views(v: View): List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun summaryUsesCompactRowsAndRendersAtPhoneWidth() {
        val context=RuntimeEnvironment.getApplication()
        context.deleteDatabase("learning.db")
        val store=Store(context);store.seed()
        val names=listOf("τιμή","φυτό","εφέ","ντουλάπα","προϊόν","νερό","σπίτι","καρέκλα","πόλη","δουλειά")
        val first=names.map { name -> store.words().first { it.greek==name } }
        val words=(first+store.words().filter { w -> first.none { it.id==w.id } }.take(90))
        words.forEachIndexed { i,w -> if(i!=3) store.apply("ux-$i",Mode.TO_EL,mapOf(w.id to Points.fraction(1,1)),"correct") }
        val session=JSONObject().put("mode",Mode.TO_EL.name).put("index",words.size).put("ids",JSONArray(words.map { it.id }))
            .put("initialTotals",JSONObject().apply { words.forEach { put(it.id,"0/1") } })
        context.getSharedPreferences("runtime",0).edit().putString("session",session.toString()).commit();store.close()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        views(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString()=="Продолжить тренировку" }.performClick()
        val root=activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,360,800)
        val rows=views(root).filter { it.tag?.toString()?.startsWith("result:")==true }
        assertEquals(100,rows.size)
        assertTrue(rows.take(10).all { it.height in 48..72 })
        assertTrue(rows.all { it.height>=48 })
        assertTrue(rows.all { (it as LinearLayout).orientation==LinearLayout.HORIZONTAL })
        val firstRow=rows.first() as LinearLayout
        val change=firstRow.findViewWithTag<android.widget.TextView>("change:${words.first().id}")
        val current=firstRow.findViewWithTag<android.widget.TextView>("current:${words.first().id}")
        assertTrue(change.right<=current.left);assertTrue(change.textSize<current.textSize)
        val actions=views(root).single { it.tag=="summary-actions" }
        val footerPosition=IntArray(2);actions.getLocationOnScreen(footerPosition)
        assertTrue(footerPosition[1]+actions.height<=800)
        val buttons=views(actions).filterIsInstance<Button>()
        assertEquals(listOf("Завершить","Новая тренировка"),buttons.map { it.text.toString() })
        assertTrue(buttons.all { it.isShown && it.height>=48 })
        val scroll=views(root).filterIsInstance<android.widget.ScrollView>().single()
        assertTrue(scroll.canScrollVertically(1))
        views(root).forEach { it.clearAnimation() }
        val bitmap=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
        val output=File("build/reports/ux/summary.png");output.parentFile?.mkdirs();output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        scroll.scrollTo(0,scroll.getChildAt(0).height)
        val after=IntArray(2);actions.getLocationOnScreen(after)
        assertArrayEquals(footerPosition,after)
        assertTrue(scroll.scrollY>0)
        val lastBounds=android.graphics.Rect()
        assertTrue(rows.last().getGlobalVisibleRect(lastBounds))
        assertTrue(lastBounds.bottom<=footerPosition[1])
        buttons.last().performClick()
        assertTrue(views(activity.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.toString()=="Выберите тренировку" })
        controller.pause().stop().destroy()
    }
    @Test fun initialListeningRevealsGreekWithoutMovingAnswerButtons() {
        val context=RuntimeEnvironment.getApplication();val store=Store(context);store.seed()
        val word=store.words().first { it.greek=="νερό" }
        val state=JSONObject().put("mode",Mode.LISTEN_EASY.name).put("index",0).put("ids",JSONArray(listOf(word.id)))
            .put("attempt","stable-listening").put("right",0).put("wrong",0).put("assisted",0).put("done",JSONArray())
            .put("feedback","").put("audioHeard",true).put("played",true).put("initialTotals",JSONObject().put(word.id,"0/1"))
        context.getSharedPreferences("runtime",0).edit().putString("session",state.toString()).commit()
        org.robolectric.shadows.ShadowTextToSpeech.addLanguageAvailability(java.util.Locale("el","GR"))
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        activity.onInit(android.speech.tts.TextToSpeech.SUCCESS)
        fun all()=views(activity.window.decorView)
        all().filterIsInstance<Button>().first { it.text.toString()=="Продолжить тренировку" }.performClick()
        val root=activity.window.decorView
        fun layout() { root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,360,800) }
        layout()
        val slot=all().filterIsInstance<Button>().single { it.tag=="audio-word-slot" };val height=slot.height;val top=slot.top
        val optionIds=JSONObject(context.getSharedPreferences("runtime",0).getString("session",null)!!).getJSONArray("options")
        val labels=(0 until optionIds.length()).map { i -> store.words().first { it.id==optionIds.getString(i) }.russian.first() }.toSet()
        val before=all().filterIsInstance<Button>().filter { it.text.toString() in labels }.associate { it.text.toString() to (it.top to it.height) }
        assertEquals("▶ Прослушать слово",slot.text.toString())
        assertFalse(all().filterIsInstance<android.widget.TextView>().any { it.text.toString()==word.greek })
        all().filterIsInstance<Button>().first { it.text.toString()==word.russian.first() }.performClick();layout()
        val revealed=all().filterIsInstance<Button>().single { it.tag=="audio-word-slot" }
        assertEquals(word.greek,revealed.text.toString());assertFalse(revealed.isEnabled)
        assertEquals(height,revealed.height);assertEquals(top,revealed.top)
        val after=all().filterIsInstance<Button>().filter { it.text.toString() in labels }.associate { it.text.toString() to (it.top to it.height) }
        assertEquals(before,after)
        controller.pause().stop().destroy();store.close()
    }
    @Test fun screenTransitionsRespectDisabledSystemAnimations() {
        val context=RuntimeEnvironment.getApplication();context.getSharedPreferences("runtime",0).edit().clear().commit()
        val previous=Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)
        Settings.Global.putFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,0f)
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        try {
            views(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString()=="Начать тренировку" }.performClick()
            views(activity.window.decorView).filterIsInstance<android.widget.ImageButton>().single { it.contentDescription=="Настроить количество слов" }.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val content=activity.window.decorView.findViewWithTag<LinearLayout>("screen-content")
            assertNull(content.animation)
        } finally { controller.pause().stop().destroy();Settings.Global.putFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,previous) }
    }
}
