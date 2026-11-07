package ru.greeklearning.app

import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WordCardTest {
    private fun views(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun dictionaryCardRestoresSearchAndDoesNotChangeProgress() {
        val context=RuntimeEnvironment.getApplication();context.getSharedPreferences("runtime",0).edit().clear().commit()
        context.deleteDatabase("learning.db");val store=Store(context);store.seed()
        val word=store.words().first { it.greek=="σπίτι" }
        store.apply("previous-user-answer",Mode.WRITE,mapOf(word.id to Points.fraction(17,4)),"correct")
        val before=store.backup()
        var controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        fun all()=views(controller.get().window.decorView)
        fun click(s: String)=all().filterIsInstance<Button>().first { it.text.toString()==s }.performClick()
        click("Мой словарь")
        all().filterIsInstance<EditText>().first().apply { setText("σπίτι");onEditorAction(EditorInfo.IME_ACTION_SEARCH) }
        all().first { it.tag=="dictionary:${word.id}" }.performClick()
        assertTrue(all().filterIsInstance<TextView>().any { it.text.toString()=="Примеры употребления" })
        assertTrue(all().filterIsInstance<TextView>().any { it.text.toString()=="του σπιτιού" })
        assertTrue(all().filterIsInstance<TextView>().any { it.text.toString()=="τα σπίτια" })
        val root=controller.get().window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,360,800)
        all().forEach { it.clearAnimation() }
        val b=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);root.draw(Canvas(b))
        File("build/reports/ux/word-card.png").apply { parentFile?.mkdirs();outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } }
        controller.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue(all().filterIsInstance<TextView>().any { it.text.toString()=="του σπιτιού" })
        controller.get().onBackPressed()
        assertEquals("σπίτι",all().filterIsInstance<EditText>().first().text.toString())
        assertEquals(before,store.backup())
        controller.pause().stop().destroy();store.close()
    }
    @Test fun resultsCardKeepsSessionAndVerbTenseSwitchWorks() {
        val context=RuntimeEnvironment.getApplication();context.getSharedPreferences("runtime",0).edit().clear().commit()
        val store=Store(context);store.seed();val word=store.words().first { it.greek=="γράφω" }
        val s=JSONObject().put("mode",Mode.WRITE.name).put("index",1).put("ids",JSONArray(listOf(word.id)))
            .put("initialTotals",JSONObject().put(word.id,"0/1"))
        context.getSharedPreferences("runtime",0).edit().putString("session",s.toString()).commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        fun all()=views(activity.window.decorView)
        all().filterIsInstance<Button>().first { it.text.toString()=="Продолжить тренировку" }.performClick()
        all().first { it.tag=="result:${word.id}" }.performClick()
        assertTrue(all().filterIsInstance<TextView>().any { it.text.toString()=="γράφεις" })
        all().filterIsInstance<Spinner>().single().setSelection(1)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(all().filterIsInstance<TextView>().any { it.text.toString()=="έγραψα" })
        all().filterIsInstance<Spinner>().single().setSelection(2)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(all().filterIsInstance<TextView>().any { it.text.toString()=="θα γράψω" })
        activity.onBackPressed()
        assertTrue(all().any { it.tag=="result:${word.id}" })
        assertEquals(s.toString(),context.getSharedPreferences("runtime",0).getString("session",null))
        assertTrue(all().any { it.tag=="summary-actions" })
        controller.pause().stop().destroy();store.close()
    }
    @Test fun referenceHasAllExistingIdsAndBasicIrregularForms() {
        val context=RuntimeEnvironment.getApplication();val store=Store(context);store.seed();val content=WordDetails(context)
        store.words().filter { it.source.startsWith("klik-a2-") }.forEach { word ->
            val item=content.get(word.id)!!;assertTrue(item.getJSONArray("examples").length() in 1..3)
        }
        val good=content.get(store.words().first { it.greek=="καλός" }.id)!!.getJSONObject("grammar").toString()
        assertTrue(good.contains("καλή"));assertTrue(good.contains("καλό"))
        val be=content.get(store.words().first { it.greek=="είμαι" }.id)!!.getJSONObject("grammar").toString()
        assertTrue(be.contains("είσαι"));assertTrue(be.contains("ήμουν"));assertTrue(be.contains("θα είμαι"))
        val icon=context.getDrawable(ru.greeklearning.app.R.mipmap.ic_launcher)!!
        val bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);icon.setBounds(0,0,192,192);icon.draw(Canvas(bitmap))
        File("build/reports/ux/icon.png").apply { parentFile?.mkdirs();outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
        store.close()
    }
}
