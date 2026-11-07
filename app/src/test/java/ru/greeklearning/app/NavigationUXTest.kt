package ru.greeklearning.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
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
class NavigationUXTest {
    private fun views(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun snapshot(root: View,name: String) {
        root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,360,800)
        views(root).forEach { it.clearAnimation() }
        val b=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);root.draw(Canvas(b))
        File("build/reports/ux/$name.png").apply { parentFile?.mkdirs();outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } }
    }
    @Test fun addWordLivesInDictionaryAndReturnsToDictionaryAfterSaving() {
        val context=RuntimeEnvironment.getApplication();context.getSharedPreferences("runtime",0).edit().clear().commit();context.deleteDatabase("learning.db")
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        fun all()=views(activity.window.decorView)
        fun click(s: String)=all().filterIsInstance<Button>().first { it.text.toString()==s }.performClick()
        assertFalse(all().filterIsInstance<Button>().any { it.text.toString() in listOf("Добавить слово","История по дням") })
        all().first { it.tag=="daily-progress" }.performClick()
        assertTrue(all().filterIsInstance<TextView>().any { it.text.toString()=="История по дням" });activity.onBackPressed()
        click("Мой словарь");snapshot(activity.window.decorView,"dictionary-add")
        val add=all().filterIsInstance<ImageButton>().single { it.contentDescription=="Добавить слово" }
        assertTrue(add.width>=48 && add.height>=48);add.performClick()
        val fields=all().filterIsInstance<EditText>()
        fields[0].setText("δοκιμή");fields[1].setText("η");fields[2].setText("проверка")
        click("Добавить")
        assertTrue(all().filterIsInstance<TextView>().any { it.text.toString()=="Мой словарь" })
        val store=Store(context);assertTrue(store.words().any { it.greek=="δοκιμή" && it.russian==listOf("проверка") });store.close()
        controller.pause().stop().destroy()
    }
    @Test fun selectedModeCountAndSettingsGearStayInSync() {
        val context=RuntimeEnvironment.getApplication();context.getSharedPreferences("runtime",0).edit().clear().commit()
        context.getSharedPreferences("settings",0).edit().putInt(Mode.TO_RU.name,10).putInt(Mode.WRITE.name,37).commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        fun all()=views(activity.window.decorView)
        fun count()=all().filterIsInstance<TextView>().single { it.tag=="training-count" }
        fun click(s: String)=all().filterIsInstance<Button>().first { it.text.toString()==s }.performClick()
        click("Начать тренировку");assertEquals("Слов за тренировку: 10",count().text.toString())
        all().filterIsInstance<Spinner>()[1].setSelection(Mode.WRITE.ordinal);shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("Слов за тренировку: 37",count().text.toString())
        assertFalse(all().filterIsInstance<Button>().any { it.text.toString()=="Настроить количество слов" })
        snapshot(activity.window.decorView,"training-count-settings")
        val gear=all().filterIsInstance<ImageButton>().single { it.tag=="training-settings" }
        assertSame(count().parent,gear.parent);assertTrue(gear.width>=48 && gear.height>=48);gear.performClick()
        all().filterIsInstance<EditText>().filter { it.tag!="daily-goal-input" }[Mode.WRITE.ordinal].setText("25")
        click("Сохранить");shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(Mode.WRITE.ordinal,all().filterIsInstance<Spinner>()[1].selectedItemPosition)
        assertEquals("Слов за тренировку: 25",count().text.toString())
        click("Начать тренировку")
        val session=org.json.JSONObject(context.getSharedPreferences("runtime",0).getString("session",null)!!)
        assertEquals(25,session.getJSONArray("ids").length());assertEquals(Mode.WRITE.name,session.getString("mode"))
        controller.pause().stop().destroy()
    }
}
