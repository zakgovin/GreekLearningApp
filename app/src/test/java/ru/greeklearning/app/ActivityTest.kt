package ru.greeklearning.app

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowAlertDialog
import org.json.JSONObject
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ActivityTest {
    private fun views(v: View): List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun startOpensFirstQuestionWithoutConfirmationForAnyRequestedSize() {
        val context=RuntimeEnvironment.getApplication()
        for (requested in listOf(1,10,100)) {
            context.getSharedPreferences("runtime",0).edit().clear().commit()
            context.getSharedPreferences("settings",0).edit().putInt(Mode.TO_RU.name,requested).commit()
            val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
            val activity=controller.get()
            fun all()=views(activity.window.decorView)
            fun click(text: String)=all().filterIsInstance<Button>().first { it.text.toString()==text }.performClick()
            click("Начать тренировку") // opens mode/group selection
            click("Начать тренировку") // starts immediately, without a modal confirmation
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(all().filterIsInstance<TextView>().any { it.text.toString()=="Выбрать перевод" })
            val state=JSONObject(context.getSharedPreferences("runtime",0).getString("session",null)!!)
            assertEquals(requested,state.getJSONArray("ids").length())
            assertEquals(0,state.getInt("index"))
            controller.pause().stop().destroy()
        }
    }
    @Test fun randomTrainingKeepsItsChosenSetWhenClosedAndResumed() {
        val context=RuntimeEnvironment.getApplication();context.getSharedPreferences("runtime",0).edit().clear().commit()
        context.getSharedPreferences("settings",0).edit().putInt(Mode.TO_RU.name,10).commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        fun all()=views(activity.window.decorView)
        fun click(text: String)=all().filterIsInstance<Button>().first { it.text.toString()==text }.performClick()
        fun ids()=JSONObject(context.getSharedPreferences("runtime",0).getString("session",null)!!).getJSONArray("ids").toString()
        click("Начать тренировку");click("Начать тренировку");val initial=ids()
        all().filterIsInstance<android.widget.ImageButton>().single { it.contentDescription=="Выйти и сохранить" }.performClick()
        click("Продолжить тренировку");assertEquals(initial,ids())
        controller.pause().stop().destroy()
    }
    @Test fun nextTrainingActuallyIncludesQueuedMistakeBeforeRandomWords() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("runtime",0).edit().clear().commit()
        context.getSharedPreferences("settings",0).edit().putInt(Mode.TO_EL.name,1).commit()
        val store=Store(context);store.seed();val word=store.words().first { it.greek=="νερό" }
        store.apply("queued-wrong",Mode.TO_EL,mapOf(word.id to Points.fraction(-1,1)),"incorrect","previous-run")
        store.apply("queued-fixed",Mode.TO_EL,mapOf(word.id to Points.fraction(1,1)),"correct","previous-run");store.close()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        fun all()=views(activity.window.decorView)
        fun click(text: String)=all().filterIsInstance<Button>().first { it.text.toString()==text }.performClick()
        click("Начать тренировку")
        all().filterIsInstance<android.widget.Spinner>()[1].setSelection(Mode.TO_EL.ordinal)
        click("Начать тренировку")
        val state=JSONObject(context.getSharedPreferences("runtime",0).getString("session",null)!!)
        assertEquals(Mode.TO_EL.name,state.getString("mode"));assertEquals(1,state.getJSONArray("ids").length())
        assertEquals(word.id,state.getJSONArray("ids").getString(0))
        controller.pause().stop().destroy()
    }
    @Test fun dictionaryFiltersNamesSeparatelyAndTrainingNamesKeepsExistingProgress() {
        val context=RuntimeEnvironment.getApplication();context.getSharedPreferences("runtime",0).edit().clear().commit()
        val store=Store(context);store.seed();val name=store.words().first { it.properName }
        store.apply("proper-progress",Mode.TO_EL,mapOf(name.id to Points.fraction(2,1)),"correct");store.close()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        fun all()=views(activity.window.decorView)
        fun click(text: String)=all().filterIsInstance<Button>().first { it.text.toString()==text }.performClick()
        fun chooseCategory(label: String) {
            val selector=all().filterIsInstance<android.widget.Spinner>().single()
            val position=(0 until selector.count).first { selector.getItemAtPosition(it).toString()==label }
            selector.setSelection(position);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        click("Мой словарь");chooseCategory("Имена собственные")
        val source=Store(context);val byId=source.words().associateBy { it.id }
        assertTrue(all().filter { it.tag?.toString()?.startsWith("dictionary:")==true }.all { byId[it.tag.toString().removePrefix("dictionary:")]!!.properName })
        chooseCategory("Существительные")
        assertTrue(all().filter { it.tag?.toString()?.startsWith("dictionary:")==true }.all { !byId[it.tag.toString().removePrefix("dictionary:")]!!.properName })
        all().filterIsInstance<android.widget.ImageButton>().single { it.contentDescription=="На главную" }.performClick();click("Начать тренировку")
        val group=all().filterIsInstance<android.widget.Spinner>()[0]
        group.setSelection((0 until group.count).first { group.getItemAtPosition(it).toString()=="Имена собственные" })
        click("Начать тренировку")
        val ids=JSONObject(context.getSharedPreferences("runtime",0).getString("session",null)!!).getJSONArray("ids")
        assertTrue((0 until ids.length()).all { byId[ids.getString(it)]!!.properName })
        assertEquals(Points.fraction(2,1),source.points(name.id,0));source.close();controller.pause().stop().destroy()
    }
    @Test fun secondaryScreensUseTopRightCloseAndSettingsReturnToChosenMode() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        fun all()=views(activity.window.decorView)
        fun click(text: String)=all().filterIsInstance<Button>().first { it.text.toString()==text }.performClick()
        fun close(description: String) {
            val icon=all().filterIsInstance<android.widget.ImageButton>().single { it.contentDescription==description }
            val header=icon.parent as android.widget.LinearLayout
            assertSame(icon,header.getChildAt(header.childCount-1));icon.performClick()
        }
        click("Начать тренировку")
        assertFalse(all().filterIsInstance<Button>().any { it.text.toString()=="На главную" })
        all().filterIsInstance<android.widget.Spinner>()[1].setSelection(Mode.WRITE.ordinal)
        all().filterIsInstance<android.widget.ImageButton>().single { it.contentDescription=="Настроить количество слов" }.performClick();close("Вернуться к выбору тренировки")
        assertEquals(Mode.WRITE.ordinal,all().filterIsInstance<android.widget.Spinner>()[1].selectedItemPosition)
        close("На главную");click("Мой словарь")
        assertFalse(all().filterIsInstance<Button>().any { it.text.toString()=="На главную" })
        close("На главную")
        assertTrue(all().filterIsInstance<TextView>().any { it.text.toString()=="Греческий каждый день" })
        controller.pause().stop().destroy()
    }
    @Test fun homeSettingsAndDictionaryAreFunctional() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        fun all() = views(activity.window.decorView)
        fun click(text: String) = all().filterIsInstance<Button>().first { it.text.toString()==text }.performClick()
        assertTrue(all().filterIsInstance<TextView>().any { it.text.toString()=="Греческий каждый день" })
        assertFalse(all().filterIsInstance<Button>().any { it.text.toString()=="Настройки тренировок" })
        click("Начать тренировку");all().filterIsInstance<android.widget.ImageButton>().single { it.contentDescription=="Настроить количество слов" }.performClick()
        val fields=all().filterIsInstance<EditText>().filter { it.tag!="daily-goal-input" };assertEquals(7,fields.size)
        fields.first().setText("1");fields.last().setText("100");click("Сохранить")
        assertEquals(1,activity.getSharedPreferences("settings",0).getInt(Mode.TO_RU.name,0))
        assertEquals(100,activity.getSharedPreferences("settings",0).getInt(Mode.SPEAK.name,0))
        all().filterIsInstance<android.widget.ImageButton>().single { it.contentDescription=="На главную" }.performClick()
        click("Мой словарь")
        assertTrue(all().filterIsInstance<Button>().any { it.text.toString()=="Следующая страница" })
        assertEquals(40,all().count { it.tag?.toString()?.startsWith("dictionary:")==true })
        val search=all().filterIsInstance<EditText>().single()
        search.setText("σπιτι");search.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
        assertTrue(all().filterIsInstance<TextView>().any { it.text.toString().contains("σπίτι") })
        controller.pause().stop().destroy()
    }
}
