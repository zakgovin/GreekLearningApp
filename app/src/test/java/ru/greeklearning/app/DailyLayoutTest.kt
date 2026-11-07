package ru.greeklearning.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.ProgressBar
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
class DailyLayoutTest {
    private fun views(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    @Test fun homeShowsBothProgressCardsAndHistoryAndGoalSettingsWork() {
        val context=RuntimeEnvironment.getApplication();context.getSharedPreferences("runtime",0).edit().clear().commit()
        context.deleteDatabase("learning.db");val store=Store(context);store.seed();store.setDailyGoal(10000)
        val today=store.today();val old=today.minusDays(45)
        store.writableDatabase.execSQL("INSERT INTO daily_counts VALUES (?,15000)",arrayOf(today.toString()))
        store.writableDatabase.execSQL("INSERT INTO daily_counts VALUES (?,3)",arrayOf(old.toString()))
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        fun all()=views(activity.window.decorView)
        fun click(text: String)=all().filterIsInstance<Button>().first { it.text.toString()==text }.performClick()
        fun text(tag: String)=all().filterIsInstance<TextView>().single { it.tag==tag }.text.toString()
        val root=activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,360,800)
        val day=all().first { it.tag=="daily-progress" };val total=all().first { it.tag=="overall-progress" }
        assertTrue(total.right<=day.left);assertEquals("15000",text("daily-count"))
        assertEquals(10000,views(day).filterIsInstance<ProgressBar>().single().progress)
        all().forEach { it.clearAnimation() }
        val bitmap=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
        File("build/reports/ux/daily-home.png").apply { parentFile?.mkdirs();outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
        click("История по дням")
        assertTrue(all().any { it.tag=="daily-history:$today" })
        assertTrue(all().any { it.tag=="daily-history:${today.minusDays(1)}" })
        click("Более ранние дни");assertTrue(all().any { it.tag=="daily-history:$old" })
        click("Настроить дневную цель")
        var field=all().filterIsInstance<EditText>().single()
        field.setText("9");click("Сохранить");assertNotNull(field.error)
        field.setText("10001");click("Сохранить");assertEquals(10000,store.dailyGoal())
        field.setText("10");click("Сохранить");assertEquals(10,store.dailyGoal())
        activity.onBackPressed();assertEquals("15000",text("daily-count"))
        store.writableDatabase.execSQL("UPDATE daily_counts SET count=15001 WHERE day=?",arrayOf(today.toString()))
        context.sendBroadcast(Intent(Intent.ACTION_DATE_CHANGED));shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("15001",text("daily-count"))
        assertEquals(3L,store.dailyProgress(old).count)
        controller.pause().stop().destroy();store.close()
    }
}
