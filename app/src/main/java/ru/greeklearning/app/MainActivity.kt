package ru.greeklearning.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.provider.Settings
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import android.view.animation.DecelerateInterpolator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val wordDetails by lazy { WordDetails(this) }
    private var dailyPageIndex: Int? = null
    private var homeVisible=false
    private val dailyHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val dayRefresh=Runnable { refreshDailyScreen() }
    private var dailyReceiverRegistered=false
    private val dailyReceiver=object: android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?,intent: Intent?) { refreshDailyScreen() }
    }
    private fun refreshDailyScreen() {
        if(!activityActive) return
        if(homeVisible) home() else dailyPageIndex?.let { dailyHistory(it) }
    }
    private var detailBack: (() -> Unit)? = null
    private lateinit var store: Store
    private var fixedFooter: LinearLayout? = null
    private lateinit var page: LinearLayout
    private val prefs by lazy { getSharedPreferences("settings", 0) }
    private val runtime by lazy { getSharedPreferences("runtime", 0) }
    private var tts: TextToSpeech? = null
    private var voiceReady = false
    private var recognizer: SpeechRecognizer? = null
    private var recording = false
    private var speechToken: String? = null
    private var answerAudioToken: String? = null
    private var trainingVisible = false
    private var matchingFeedbackActive = false
    private var lastScreenKey: String? = null
    private var activityActive = false
    private fun isChoice(mode: Mode) = mode in listOf(Mode.TO_RU, Mode.TO_EL, Mode.LISTEN_EASY)
    private var session: JSONObject? = null
    private var group = "noun"
    private var chosenMode = Mode.TO_RU
    private val accent = Color.rgb(11, 94, 168)
    private val success = Color.rgb(24, 112, 78)
    private val ink = Color.rgb(7, 46, 83)
    private val muted = Color.rgb(91, 108, 128)
    private val background = Color.rgb(244, 247, 252)
    private var cachedWords: List<Word>? = null
    private val words get() = cachedWords ?: store.words().also { cachedWords = it }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun message(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = Store(this); store.seed()
        session = runtime.getString("session", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        tts = TextToSpeech(this, this)
        home()
        runtime.getString("word-card",null)?.let { saved ->
            runCatching { JSONObject(saved) }.getOrNull()?.let { route ->
                words.firstOrNull { it.id==route.optString("id") }?.let { wordCard(it,route) }
            }
        }
    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            var available = tts?.setLanguage(Locale("el", "GR")) ?: TextToSpeech.LANG_NOT_SUPPORTED
            tts?.let { engine ->
                // Use the engine's default Greek voice; advertised quality does not guarantee a usable model.
                if(available < TextToSpeech.LANG_AVAILABLE) {
                    val installed=runCatching { SpeechSettings.chooseGreekVoice(engine.voices.orEmpty()) }.getOrNull()
                    if(installed != null && engine.setVoice(installed)==TextToSpeech.SUCCESS) available=TextToSpeech.LANG_AVAILABLE
                }
                engine.setSpeechRate(SpeechSettings.RATE);engine.setPitch(SpeechSettings.PITCH)
                engine.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            }
            voiceReady = available >= TextToSpeech.LANG_AVAILABLE
            tts?.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { runOnUiThread { onUtteranceFinished(id) } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { runOnUiThread { onUtteranceFailed(id) } }
            })
            if (activityActive && trainingVisible && session?.optBoolean("answerAudioPending") == true && answerAudioToken == null) {
                session?.put("answerAudioError", false); save(); training()
            }
        }
    }
    override fun onResume() {
        super.onResume()
        activityActive = true
        if(!dailyReceiverRegistered) {
            val filter=android.content.IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED);addAction(Intent.ACTION_TIME_CHANGED);addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            if(android.os.Build.VERSION.SDK_INT>=33) registerReceiver(dailyReceiver,filter,android.content.Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(dailyReceiver,filter)
            dailyReceiverRegistered=true
        }
        if(homeVisible) { home();return }
        dailyPageIndex?.let { dailyHistory(it);return }
        if (trainingVisible && matchingFeedbackActive) { training(); return }
        if (trainingVisible && session?.optBoolean("answerAudioPending") == true && answerAudioToken == null) training()
    }
    override fun onStop() { if(dailyReceiverRegistered) { unregisterReceiver(dailyReceiver);dailyReceiverRegistered=false };dailyHandler.removeCallbacks(dayRefresh); activityActive = false; super.onStop(); stopRecording(); suspendAnswerAudio(); tts?.stop() }
    override fun onDestroy() { activityActive = false; recognizer?.destroy(); tts?.shutdown(); store.close(); super.onDestroy() }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        detailBack?.let { it();return }
        if (session != null) AlertDialog.Builder(this).setTitle("Выйти из тренировки?")
            .setMessage("Ответы и текущий ввод сохранятся. Продолжить можно с главного экрана.")
            .setPositiveButton("Выйти") { _, _ -> stopRecording(); tts?.stop(); home() }.setNegativeButton("Остаться", null).show()
        else home()
    }
    private fun shape(color: Int, radius: Int = 12) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun screen(title: String, subtitle: String = "", onClose: (() -> Unit)? = null, closeDescription: String = "Выйти и сохранить", pinnedActions: Boolean = false, onAdd: (() -> Unit)? = null) {
        dailyHandler.removeCallbacks(dayRefresh)
        homeVisible=false
        dailyPageIndex=null
        detailBack = null
        trainingVisible = false
        matchingFeedbackActive = false
        val screenKey = "$title|$subtitle"
        val animateTransition = lastScreenKey != null && lastScreenKey != screenKey
        lastScreenKey = screenKey
        val scroll = ScrollView(this).apply {
            setBackgroundColor(this@MainActivity.background); isFillViewport = true
            if(!pinnedActions) setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom); insets
            }
        }
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)); tag="screen-content" }
        scroll.addView(page)
        fixedFooter = null
        if(pinnedActions) {
            val container = LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;setBackgroundColor(this@MainActivity.background)
                setOnApplyWindowInsetsListener { view,insets ->
                    view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom);insets
                }
            }
            container.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
            fixedFooter=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(12));tag="summary-actions"
            }
            container.addView(fixedFooter,LinearLayout.LayoutParams(-1,-2))
            setContentView(container)
        } else setContentView(scroll)
        if (onClose == null) label("αω  /  ΕΛΛΗΝΙΚΑ  A2", 12, accent, true) else {
            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            page.addView(header, LinearLayout.LayoutParams(-1, -2))
            val close = ImageButton(this).apply {
                setImageResource(ru.greeklearning.app.R.drawable.ic_close)
                contentDescription = closeDescription
                val attributes = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                setBackgroundResource(attributes.getResourceId(0, 0)); attributes.recycle()
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { onClose() }
            }
            label("αω  /  ΕΛΛΗΝΙΚΑ  A2", 12, accent, true, header).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            if(onAdd!=null) header.addView(actionIcon(android.R.drawable.ic_input_add,"Добавить слово",onAdd),LinearLayout.LayoutParams(dp(48),dp(48)))
            header.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        label(title, 26, ink, true).setPadding(0, dp(10), 0, dp(4))
        if (subtitle.isNotBlank()) label(subtitle, 15, muted)
        gap(12)
        val motionScale=Settings.Global.getFloat(contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)
        if (animateTransition && motionScale>0f) {
            // Visual-only transition: navigation, scoring and TTS callbacks never wait for animation.
            val transition=AnimationSet(true).apply {
                addAnimation(AlphaAnimation(0.82f,1f))
                addAnimation(TranslateAnimation(0f,0f,dp(6).toFloat(),0f))
                duration=(160*motionScale.coerceAtMost(2f)).toLong()
                interpolator=DecelerateInterpolator()
            }
            page.startAnimation(transition)
        }
    }
    private fun actionIcon(resource: Int,description: String,action: () -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(resource);contentDescription=description
        imageTintList=ColorStateList.valueOf(muted)
        val attrs=obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        setBackgroundResource(attrs.getResourceId(0,0));attrs.recycle()
        setPadding(dp(12),dp(12),dp(12),dp(12));setOnClickListener { action() }
    }
    private fun gap(size: Int = 10) { page.addView(View(this), LinearLayout.LayoutParams(1, dp(size))) }
    private fun label(value: String, size: Int = 17, color: Int = ink, bold: Boolean = false, parent: LinearLayout = page): TextView =
        TextView(this).apply {
            text = value; textSize = size.toFloat(); setTextColor(color); includeFontPadding=false
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4)); parent.addView(this)
        }
    private fun button(title: String, primary: Boolean = false, parent: LinearLayout = page, action: () -> Unit): Button =
        Button(this).apply {
            text = title; isAllCaps = false; textSize = 16f; minHeight = dp(48); minimumHeight=dp(48)
            stateListAnimator=null; elevation=0f; setPadding(dp(16),dp(10),dp(16),dp(10))
            setTextColor(if (primary) Color.WHITE else accent)
            val surface=shape(if (primary) ink else Color.WHITE,10).apply {
                if(!primary) setStroke(dp(1),Color.rgb(219,229,240))
            }
            background = RippleDrawable(ColorStateList.valueOf(if(primary) Color.argb(40,255,255,255) else Color.argb(24,11,94,168)),surface,shape(Color.WHITE,10))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            setOnClickListener { action() }
        }
    private fun input(hint: String, initial: String = "", parent: LinearLayout = page): EditText = EditText(this).apply {
        this.hint = hint; setText(initial); textSize = 18f; setSingleLine(true); setTextColor(ink)
        setPadding(dp(14), dp(14), dp(14), dp(14)); background = shape(Color.WHITE, 12)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }
    private fun progress(value: Points, parent: LinearLayout = page) {
        parent.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 10000; progress = (value.approximate() * 100).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
        }, LinearLayout.LayoutParams(-1, dp(9)).apply { topMargin = dp(8); bottomMargin = dp(12) })
    }
    private fun home() {
        suspendAnswerAudio()
        screen("Греческий каждый день", "Четыре навыка. Одно слово за раз.")
        val list = words
        val scores = store.allPoints()
        val average = if (list.isEmpty()) Points.ZERO else scores.values.fold(Points.ZERO) { a, score -> a + score }.let { Points.of(it.n, it.d * list.size.toBigInteger()) }
        val today=store.dailyProgress()
        val goal=today.goal ?: store.dailyGoal()
        val panels=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        page.addView(panels,LinearLayout.LayoutParams(-1,-2))
        fun panel(tagValue: String): LinearLayout = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(12));background=shape(Color.WHITE);tag=tagValue
            panels.addView(this,LinearLayout.LayoutParams(0,-2,1f).apply { if(panels.childCount>0) leftMargin=dp(8) })
        }
        val totalPanel=panel("overall-progress")
        label("Весь словарь",13,muted,parent=totalPanel)
        label("${average.text()}%",28,accent,true,totalPanel)
        label("${list.size} слов",13,muted,parent=totalPanel);progress(average,totalPanel)
        val dayPanel=panel("daily-progress")
        label("Сегодня",13,muted,parent=dayPanel)
        label(today.count.toString(),28,accent,true,dayPanel).tag="daily-count"
        label("из $goal · слов",13,muted,parent=dayPanel)
        progress(Points.of(today.count.toBigInteger()*100.toBigInteger(),goal.toBigInteger()),dayPanel)
        dayPanel.isClickable=true;dayPanel.isFocusable=true
        dayPanel.contentDescription="Сегодня ${today.count} слов из $goal. Открыть историю по дням"
        dayPanel.setOnClickListener { dailyHistory() }
        gap()
        if (session != null) button("Продолжить тренировку", true) { training() }
        button("Начать тренировку", session == null) { setup() }
        button("Мой словарь") { dictionary() }
        gap(18)
        label("СЛОВАРЬ ΚΛΙΚ A2", 12, accent, true)
        label("Полный глоссарий из PDF и сохранённые стартовые слова. Русские переводы подготовлены для приложения.", 14, muted)
        label("Прогресс хранится на устройстве. Перед удалением приложения сохраните резервную копию.", 14, muted)
        button("Экспорт словаря и прогресса") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE, "greek-learning-backup.json").addCategory(Intent.CATEGORY_OPENABLE), 21)
        }
        button("Импорт JSON / восстановление") {
            if (session != null) { message("Сначала завершите или отмените текущую тренировку"); return@button }
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), 22)
        }
        homeVisible=true;scheduleDayRefresh()
    }
    private fun scheduleDayRefresh() {
        dailyHandler.removeCallbacks(dayRefresh)
        if(activityActive && (homeVisible || dailyPageIndex!=null)) {
            val now=java.time.ZonedDateTime.now()
            val next=now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            dailyHandler.postDelayed(dayRefresh,java.time.Duration.between(now,next).toMillis().coerceAtLeast(1))
        }
    }
    private fun dailyGoalSettings(onSaved: () -> Unit) {
        screen("Дневная цель","От 10 до 10 000 слов в день.",onClose=onSaved,closeDescription="Вернуться назад")
        detailBack=onSaved
        val field=input("10–10000",store.dailyGoal().toString()).apply { inputType=android.text.InputType.TYPE_CLASS_NUMBER;tag="daily-goal-input" }
        label("Одно завершённое задание со словом — один плюс. Ошибки до правильного выбора в сопоставлении и выборе ответа не добавляют повторных плюсов. В остальных режимах ошибка или пропуск завершают задание и учитываются один раз. Повтор слова в новом задании считается заново. Подсказки и технические сбои не добавляют плюсов.",15,muted)
        label("Цель действует сегодня и в следующие дни. Можно тренироваться и после её достижения.",15,muted)
        button("Сохранить",true) {
            val goal=field.text.toString().toIntOrNull()
            if(goal==null || goal !in 10..10000) { field.error="Введите целое число от 10 до 10000";return@button }
            store.setDailyGoal(goal);onSaved()
        }
    }
    private fun dailyHistory(pageIndex: Int=0) {
        val today=store.today();val first=minOf(store.firstActivityDay(),today)
        val maxPage=(java.time.temporal.ChronoUnit.DAYS.between(first,today)/30).toInt()
        val index=pageIndex.coerceIn(0,maxPage)
        val end=today.minusDays(index*30L);val start=maxOf(first,end.minusDays(29))
        screen("История по дням","Каждое повторение слова учитывается. День — по времени телефона.",onClose=::home,closeDescription="На главную")
        dailyPageIndex=index;detailBack=::home
        button("Настроить дневную цель") { dailyGoalSettings { dailyHistory(index) } }
        val format=java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy",Locale("ru"))
        var date=end
        while(!date.isBefore(start)) {
            val result=store.dailyProgress(date)
            val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(10));background=shape(Color.WHITE);tag="daily-history:$date" }
            page.addView(card,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
            label(date.format(format),15,ink,true,card)
            label(if(result.goal!=null) "${result.count} / ${result.goal} слов" else "${result.count} слов · цель ещё не была задана",16,accent,parent=card)
            if(result.goal!=null) {
                progress(Points.of(result.count.toBigInteger()*100.toBigInteger(),result.goal.toBigInteger()),card)
                if(result.count>=result.goal) label("Цель достигнута",13,success,parent=card)
            }
            date=date.minusDays(1)
        }
        if(index>0) button("Более новые дни") { dailyHistory(index-1) }
        if(index<maxPage) button("Более ранние дни") { dailyHistory(index+1) }
        scheduleDayRefresh()
    }
    private fun dictionary(query: String = "", pageIndex: Int = 0, category: String? = null) {
        screen("Мой словарь", "Каждый навык — до 25%. Вместе — до 100%.",onClose=::home,closeDescription="На главную",
            onAdd={ addWord { dictionary(query,pageIndex,category) } })
        val categories=listOf<String?>(null)+Rules.parts.keys.toList()
        val filter=spinner(listOf("Все категории")+Rules.parts.values.toList())
        filter.setSelection(categories.indexOf(category).coerceAtLeast(0))
        filter.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) {
                val selected=categories[position]
                if(selected!=category) dictionary(query,0,selected)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        input("Поиск по-русски или по-гречески", query).apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> dictionary(text.toString(),category=category); true }
        }
        val matches = words.filter { category==null || it.category==category }.filter { query.isBlank() || Rules.searchKey(it.display).contains(Rules.searchKey(query)) || it.russian.any { r -> Rules.normalize(r,true).contains(Rules.normalize(query,true)) } }
            .sortedBy { it.greek.lowercase(Locale.ROOT) }
        val pageSize = 40
        val index = pageIndex.coerceIn(0, ((matches.size-1)/pageSize).coerceAtLeast(0))
        label("Найдено: ${matches.size} · страница ${index+1} / ${((matches.size+pageSize-1)/pageSize).coerceAtLeast(1)}",14,muted)
        val scores=store.allPoints()
        matches.drop(index*pageSize).take(pageSize).forEach { w ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(12)); background = shape(Color.WHITE);tag="dictionary:${w.id}" }
            page.addView(card, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(12) })
            fun score(skill: Int)=scores[w.id to skill] ?: Points.ZERO
            val total=(0..3).fold(Points.ZERO) { value,skill -> value+score(skill) }
            label(w.display, 24, ink, true, card); label(w.russian.joinToString(", "), 16, muted, parent=card)
            label("${Rules.parts[w.category]} · ${total.text()}%", 13, accent, parent=card)
            label("Перевод ${score(0).text()} · Написание ${score(1).text()}\nСлух ${score(2).text()} · Произношение ${score(3).text()}", 13, muted, parent=card)
            if(w.inflections.isNotEmpty()) label("Формы: ${w.inflections.joinToString(", ")}",13,muted,parent=card)
            if(w.sourcePages.isNotEmpty()) label("ΚΛΙΚ A2 · стр. ${w.sourcePages.sorted().joinToString(", ")}",12,muted,parent=card)
            button("Послушать", parent=card) { speak(w.greek) }
            card.isClickable=true;card.isFocusable=true
            card.contentDescription="${w.display}. ${w.russian.joinToString(", ")}. Открыть полную карточку"
            card.setOnClickListener {
                val route=JSONObject().put("id",w.id).put("origin","dictionary").put("query",query).put("page",index)
                    .put("category",category).put("scroll",(page.parent as? ScrollView)?.scrollY ?: 0)
                wordCard(w,route)
            }
        }
        if(index>0) button("Предыдущая страница") { dictionary(query,index-1,category) }
        if((index+1)*pageSize<matches.size) button("Следующая страница") { dictionary(query,index+1,category) }
    }
    private fun wordCard(word: Word, route: JSONObject) {
        runtime.edit().putString("word-card",route.toString()).commit()
        fun closeCard() {
            runtime.edit().remove("word-card").commit()
            tts?.stop()
            if(route.optString("origin")=="summary" && session!=null) summary()
            else dictionary(route.optString("query"),route.optInt("page"),route.optString("category").takeIf { it.isNotBlank() && it!="null" })
            (page.parent as? ScrollView)?.let { scroll -> scroll.post { scroll.scrollTo(0,route.optInt("scroll")) } }
        }
        screen(word.display,word.russian.joinToString(", "),onClose=::closeCard,closeDescription="Вернуться к списку слов")
        detailBack=::closeCard
        label(Rules.parts[word.category] ?: word.pos,14,muted)
        label("Изучено ${store.total(word).text()}%",22,accent,true)
        label("Перевод ${store.points(word.id,0).text()}% · Написание ${store.points(word.id,1).text()}%\nСлух ${store.points(word.id,2).text()}% · Произношение ${store.points(word.id,3).text()}%",13,muted)
        button("▶ Произнести слово") { speak(word.greek) }
        val details=wordDetails.get(word.id)
        gap(8);label("Примеры употребления",20,ink,true)
        val examples=details?.optJSONArray("examples")
        if(examples==null || examples.length()==0) label("Для добавленного слова примеры ещё не подготовлены.",15,muted)
        else for(i in 0 until minOf(3,examples.length())) {
            val e=examples.getJSONObject(i)
            val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(10));background=shape(Color.WHITE) }
            page.addView(card,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
            label(e.getString("greek"),19,ink,parent=card)
            label(e.getString("russian"),16,muted,parent=card)
        }
        val grammar=details?.optJSONObject("grammar")
        if(grammar!=null) {
            gap(16);label("Формы слова · A2",20,ink,true)
            fun table(data: JSONObject) {
                val headers=data.getJSONArray("headers");val rows=data.getJSONArray("rows")
                val container=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8));background=shape(Color.WHITE) }
                page.addView(container,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
                for(r in -1 until rows.length()) {
                    val values=if(r<0) headers else rows.getJSONArray(r)
                    val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
                    container.addView(line,LinearLayout.LayoutParams(-1,-2))
                    for(c in 0 until values.length()) label(
                        values.getString(c).let { value -> if(c==0 && grammar.optString("kind")=="noun") value.replace("Именительный · ","Именит.\n").replace("Родительный · ","Родит.\n").replace("Винительный · ","Винит.\n") else value },
                        if(r<0) 12 else 15,if(c==0 || r<0) muted else ink,r<0,line).apply {
                        layoutParams=LinearLayout.LayoutParams(0,-2,1f);setPadding(dp(3),dp(7),dp(3),dp(7))
                    }
                }
            }
            if(grammar.has("rows")) table(grammar)
            grammar.optJSONArray("tenses")?.let { tenses ->
                val holder=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
                val tabs=LinearLayout(this).apply {
                    orientation=LinearLayout.HORIZONTAL;setPadding(dp(4),dp(4),dp(4),dp(4))
                    background=shape(Color.rgb(228,238,250),12);tag="tense-tabs"
                }
                page.addView(tabs,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
                page.addView(holder)
                val tabButtons=mutableListOf<Button>()
                fun render(position: Int) {
                    holder.removeAllViews()
                    val outer=page;page=holder
                    table(tenses.getJSONObject(position));page=outer
                    tabButtons.forEachIndexed { index,b ->
                        b.isSelected=index==position
                        b.background=shape(if(b.isSelected) ink else Color.TRANSPARENT,9)
                        b.setTextColor(if(b.isSelected) Color.WHITE else accent)
                    }
                    route.put("tense",position)
                    runtime.edit().putString("word-card",route.toString()).commit()
                }
                listOf("Настоящее","Прошедшее","Будущее").take(tenses.length()).forEachIndexed { index,title ->
                    val tab=Button(this).apply {
                        text=title;isAllCaps=false;textSize=13f;minHeight=dp(48);minimumWidth=0
                        setPadding(dp(4),dp(8),dp(4),dp(8));tag="tense-tab:$index"
                        contentDescription="$title, вкладка ${index+1} из ${tenses.length()}"
                        setOnClickListener { render(index) }
                    }
                    tabs.addView(tab,LinearLayout.LayoutParams(0,-2,1f))
                    tabButtons.add(tab)
                }
                render(route.optInt("tense").coerceIn(0,tenses.length()-1))
            }
            label(grammar.optString("note"),14,muted)
        }
        gap(16);label("Источники",16,ink,true)
        label("Готовые примеры: участники Tatoeba (CC BY 2.0). Учебные примеры подготовлены для приложения. Формы: Modern Greek Inflexion (MIT).",12,muted)
    }
    private fun settings() {
        screen("Настройки тренировок", "Дневная цель и число слов для каждого режима.",onClose=::setup,closeDescription="Вернуться к выбору тренировки")
        label("Дневная цель · 10–10000",17,ink,true)
        val dailyGoalField=input("10–10000",store.dailyGoal().toString()).apply { inputType=android.text.InputType.TYPE_CLASS_NUMBER;tag="daily-goal-input" }
        label("Слов в одной тренировке · 1–100",17,ink,true)
        val fields = Mode.entries.associateWith { mode ->
            label(mode.label, 17, ink, true)
            input("1–100", prefs.getInt(mode.name,10).coerceIn(1,100).toString()).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        }
        button("Сохранить", true) {
            val goal=dailyGoalField.text.toString().toIntOrNull()
            if(goal==null || goal !in 10..10000) { dailyGoalField.error="Введите целое число от 10 до 10000";return@button }
            val values = fields.mapValues { it.value.text.toString().toIntOrNull() }
            if (values.values.any { it == null || it !in 1..100 }) { message("Каждое значение должно быть целым от 1 до 100"); return@button }
            store.setDailyGoal(goal)
            prefs.edit().apply { values.forEach { (m,v) -> putInt(m.name,v!!) } }.commit()
            message("Настройки сохранены"); setup()
        }
    }
    private fun spinner(items: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        page.addView(this, LinearLayout.LayoutParams(-1, dp(54)))
    }
    private fun addWord(onBack: () -> Unit) {
        screen("Новое слово", "Базовая форма и русские переводы. Синонимы разделяйте точкой с запятой.",onClose=onBack,closeDescription="Вернуться в словарь")
        detailBack=onBack
        val greek = input("Греческое слово"); val article = input("Артикль, если есть: ο / η / το")
        val russian = input("Перевод; другой допустимый перевод")
        val keys = Rules.parts.keys.toList(); val part = spinner(Rules.parts.values.toList())
        button("Добавить", true) {
            runCatching { store.add(greek.text.toString(), russian.text.toString(), keys[part.selectedItemPosition], article.text.toString()) }
                .onSuccess { cachedWords=null; message("Слово добавлено"); onBack() }.onFailure { message(it.message ?: "Проверьте поля") }
        }
    }
    private fun setup() {
        screen("Выберите тренировку",onClose=::home,closeDescription="На главную")
        val availableGroups = Rules.parts.keys.filter { key -> words.any { it.category == key } }
        label("Группа слов", 15, muted)
        val part = spinner(availableGroups.map { Rules.parts.getValue(it) })
        part.setSelection(availableGroups.indexOf(group).coerceAtLeast(0))
        label("Тренировка", 15, muted)
        val mode = spinner(Mode.entries.map { it.label }); mode.setSelection(chosenMode.ordinal)
        fun description(selected: Mode) = when(selected) {
            Mode.TO_RU -> "Выберите русский перевод греческого слова. Неверный вариант подсветится красным — пробуйте до правильного ответа. +1% за верный ответ, −1% за каждый неверный выбор."
            Mode.TO_EL -> "Выберите греческое слово по русскому переводу. Неверный вариант подсветится красным — пробуйте до правильного ответа. +1% за верный ответ, −1% за каждый неверный выбор."
            Mode.MATCH -> "Соедините греческие слова с русскими переводами. На экране до 5 пар. Верная пара: +1%; неверная: −1% обоим словам. После верной пары прозвучит греческое слово."
            Mode.WRITE -> "Напишите греческое слово по русскому переводу, в базовой форме с правильным артиклем или без него. +1% за верный ответ, −1% за ошибку. Подсказка открывает по букве и пропорционально уменьшает награду; полностью открытое слово даёт 0%."
            Mode.LISTEN_EASY -> "Послушайте греческое слово и выберите русский перевод. +1% за верный ответ, −1% за каждый неверный выбор. После правильного выбора появится греческое написание."
            Mode.LISTEN_HARD -> "Послушайте греческое слово и введите перевод по-русски. +1% за верный ответ, −1% за ошибку или пропуск. При ошибке можно сравнить свой ответ с правильным."
            Mode.SPEAK -> "Произнесите греческое слово по русскому переводу, в базовой форме. Существительное можно произнести с правильным артиклем или без него. +1% за верный ответ, −1% за ошибку или пропуск. Подсказок нет."
        }
        val countRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        page.addView(countRow,LinearLayout.LayoutParams(-1,-2))
        fun countText()="Слов за тренировку: ${prefs.getInt(chosenMode.name,10).coerceIn(1,100)}"
        val selectedCount=label(countText(),16,ink,parent=countRow).apply {
            layoutParams=LinearLayout.LayoutParams(0,-2,1f);tag="training-count"
        }
        countRow.addView(actionIcon(android.R.drawable.ic_menu_manage,"Настроить количество слов") {
            group=availableGroups[part.selectedItemPosition];chosenMode=Mode.entries[mode.selectedItemPosition]
            settings()
        }.apply { tag="training-settings" },LinearLayout.LayoutParams(dp(48),dp(48)))
        val explanation = label(description(chosenMode),14,muted)
        mode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                chosenMode = Mode.entries[position]
                explanation.text = description(chosenMode)
                selectedCount.text=countText()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        button("Начать тренировку", true) {
            group = availableGroups[part.selectedItemPosition]; chosenMode = Mode.entries[mode.selectedItemPosition]
            val pool = Rules.prioritizeReviews(Rules.trainingPool(words, group),store.reviewIds(chosenMode))
            val eligible = when(chosenMode) {
                Mode.TO_RU,Mode.TO_EL,Mode.LISTEN_EASY -> pool.filter { Rules.options(it,pool,chosenMode != Mode.TO_EL).size >= 2 }
                Mode.MATCH -> Rules.matchingWords(pool)
                else -> pool
            }
            val size = prefs.getInt(chosenMode.name,10).coerceIn(1,100)
            val selected = eligible.take(size).shuffled()
            if (selected.isEmpty()) { message("Недостаточно однозначных вариантов. Добавьте слова или выберите свободный ввод."); return@button }
            if (chosenMode in listOf(Mode.LISTEN_EASY, Mode.LISTEN_HARD) && !voiceReady) { message("Греческий голос ещё не готов. Установите его в настройках синтеза речи Android."); return@button }
            session = JSONObject().put("sessionId",UUID.randomUUID().toString()).put("mode",chosenMode.name).put("ids",JSONArray(selected.map { it.id })).put("index",0)
                .put("hints",0).put("draft","").put("attempt",UUID.randomUUID().toString()).put("right",0).put("wrong",0).put("assisted",0)
                .put("done",JSONArray()).put("feedback","")
                .put("initialTotals",JSONObject().apply { selected.forEach { put(it.id,store.total(it).toString()) } })
            save(); training()
            if (selected.size < size) message("В группе доступно ${selected.size} слов — все включены в тренировку.")
        }

    }
    private fun save() { runtime.edit().apply { if (session == null) remove("session") else putString("session",session.toString()) }.commit() }
    private fun currentWords(): List<Word> {
        val map = words.associateBy { it.id }; val ids = session!!.getJSONArray("ids")
        return (0 until ids.length()).mapNotNull { map[ids.getString(it)] }
    }
    private fun training() {
        val s = session ?: return home(); val mode = Mode.valueOf(s.getString("mode")); val list = currentWords(); val index = s.getInt("index")
        if (index >= list.size) return summary()
        // Resume feedback saved by earlier versions without charging the old answer again.
        if (isChoice(mode) && s.optString("feedback").isNotBlank()) {
            if (s.optBoolean("correct")) {
                s.put("feedback", "").put("answerAudioPending", true)
            } else if (s.optString("feedback").startsWith("Неверно")) {
                s.put("feedback", "").put("choiceError", true).put("attempt", UUID.randomUUID().toString())
            }
            save()
        }
        screen(mode.label, "${index+1} / ${list.size} · ${Rules.parts[list[index].category]}", onClose = ::exitTraining)
        trainingVisible = true
        progress(Points.fraction(index*100,list.size))
        if (mode == Mode.MATCH) { matching(list); return }
        val w = list[index]; val listening = mode in listOf(Mode.LISTEN_EASY, Mode.LISTEN_HARD)
        label(if (listening) "Послушайте слово" else if (mode == Mode.TO_RU) w.display else w.russian.first(), 32, ink, true)
        if (mode in listOf(Mode.WRITE,Mode.SPEAK)) label(if(mode==Mode.SPEAK) "Базовая форма; артикль можно произнести" else "Базовая форма; артикль можно добавить",14,muted)
        val feedback = s.optString("feedback")
        if (feedback.isNotEmpty()) {
            if (s.optBoolean("correct") && mode != Mode.SPEAK) { next(); return }
            label(feedback,20,if(s.optBoolean("correct")) success else Color.rgb(166,66,40),true)
            if(mode == Mode.SPEAK && s.optString("recognized").isNotBlank()) label("Распознано: ${s.optString("recognized")}",17,muted)
            if(mode in listOf(Mode.WRITE,Mode.LISTEN_HARD) && feedback.startsWith("Неверно") && s.optString("draft").isNotBlank()) label("Ваш ответ: ${s.optString("draft")}",17,muted)
            label("${w.display} — ${w.russian.joinToString(", ")}",22)
            button("Послушать правильный ответ") { speak(w.greek) }
            button("Далее",true) { next() }; return
        }
        if (listening) {
            val revealed=mode==Mode.LISTEN_EASY && s.optBoolean("answerAudioPending")
            val audioAttempt=s.getString("attempt")
            val audioButton=button(if(revealed) w.greek else "▶ Прослушать слово",!revealed) {
                if(s.optString("attempt")==audioAttempt && !s.optBoolean("answerAudioPending")) speak(w.greek,true)
            }
            if(mode==Mode.LISTEN_EASY) {
                val paint=android.text.TextPaint().apply { textSize=20f*resources.displayMetrics.scaledDensity }
                val slotHeight=kotlin.math.ceil(paint.fontSpacing*2+dp(12)).toInt().coerceAtLeast(dp(56))
                audioButton.layoutParams.height=slotHeight;audioButton.minHeight=slotHeight;audioButton.minimumHeight=slotHeight
                audioButton.setPadding(dp(12),dp(6),dp(12),dp(6));audioButton.maxLines=2
                audioButton.ellipsize=android.text.TextUtils.TruncateAt.END;audioButton.tag="audio-word-slot"
            }
            if(s.optBoolean("answerAudioPending")) { audioButton.isEnabled=false;audioButton.isClickable=false }
            if(revealed) {
                audioButton.textSize=20f;audioButton.setTextColor(ink);audioButton.background=shape(Color.WHITE,10)
                audioButton.isEnabled=false;audioButton.isClickable=false
                audioButton.contentDescription="Греческое слово: ${w.greek}"
            }
            if (!s.optBoolean("answerAudioPending") && !s.optBoolean("played") && voiceReady) { s.put("played",true); save(); speak(w.greek, true) }
        }
        when(mode) {
            Mode.TO_RU, Mode.TO_EL, Mode.LISTEN_EASY -> {
                val options = s.optJSONArray("options")?.let { a -> (0 until a.length()).mapNotNull { i -> words.find { it.id == a.getString(i) } } }
                    ?.takeIf { list -> list.all { it.category==w.category } }
                    ?: Rules.options(w, words.filter { it.category == w.category }, mode != Mode.TO_EL).also { s.put("options",JSONArray(it.map { x -> x.id })); save() }
                val rejected = s.optJSONArray("wrongOptions")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
                val pending = s.optBoolean("answerAudioPending")
                val renderedAttempt = s.getString("attempt")
                options.forEach { option ->
                    val text = if(mode == Mode.TO_EL) option.display else option.russian.first()
                    val choice = button(text) {
                        if (s.optString("attempt") == renderedAttempt && !pending && option.id !in rejected) answer(option.id == w.id, selectedOptionId = option.id)
                    }
                    choice.isEnabled = !pending && option.id !in rejected
                    if (option.id in rejected) {
                        choice.background = shape(Color.rgb(252, 218, 215))
                        choice.setTextColor(Color.rgb(164, 40, 34)); choice.contentDescription = "$text — неверный ответ"
                    } else if (pending && option.id == w.id) {
                        choice.background = shape(Color.rgb(211, 233, 215)); choice.setTextColor(success)
                    }
                }
                if (s.optBoolean("choiceError") && !pending) label("Неверно · −1%. Выберите другой ответ.", 16, Color.rgb(164,40,34))
            }
            Mode.WRITE, Mode.LISTEN_HARD -> {
                val field = input(if(mode == Mode.WRITE) "Ответ по-гречески" else "Перевод по-русски",s.optString("draft"))
                field.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                val fieldAttempt=s.getString("attempt")
                val inputPending=s.optBoolean("answerAudioPending")
                if(inputPending) {
                    field.background=shape(Color.rgb(211,233,215));field.setTextColor(success)
                    field.isEnabled=false;field.isFocusable=false
                    field.contentDescription="Правильный ответ: ${s.optString("draft")}. Слушаем произношение."
                }
                field.imeOptions = EditorInfo.IME_ACTION_DONE
                field.imeHintLocales = android.os.LocaleList(Locale(if(mode == Mode.WRITE) "el" else "ru"))
                field.addTextChangedListener(object: TextWatcher {
                    override fun beforeTextChanged(a: CharSequence?,b: Int,c: Int,d: Int) {}
                    override fun onTextChanged(a: CharSequence?,b: Int,c: Int,d: Int) { if(session===s && s.optString("attempt")==fieldAttempt && !s.optBoolean("answerAudioPending")) { s.put("draft",a.toString()); save() } }
                    override fun afterTextChanged(a: Editable?) {}
                })
                fun submit() { if(inputPending || s.optBoolean("answerAudioPending") || s.optString("attempt")!=fieldAttempt) return; if (field.text.isBlank()) message(if(mode==Mode.WRITE) "Введите ответ" else "Введите ответ или нажмите «Пропустить»") else answer(if(mode == Mode.WRITE && s.optInt("hints")>0) Rules.correctGreekAnswer(w,field.text.toString(),baseOnly=true) else if(mode == Mode.WRITE) Rules.correctGreekForPrompt(w,field.text.toString(),words) else Rules.correctRussianForAudio(w,field.text.toString(),words)) }
                field.setOnEditorActionListener { _,action,_ -> if(action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
                if(!inputPending) button("Проверить",true) { submit() }
                if (mode == Mode.WRITE && !inputPending) {
                    val count = Rules.letterCount(w.greek); val hints = s.optInt("hints")
                    label("Возможная награда: +${Rules.delta(true,hints=hints,length=count).text()}%",14,muted)
                    button("? Подсказать следующую букву") {
                        val n = (s.optInt("hints")+1).coerceAtMost(count)
                        s.put("hints",n).put("draft",Rules.prefix(w.greek,n)); save(); training()
                    }.isEnabled = hints < count
                }
            }
            Mode.SPEAK -> {
                label(s.optString("recognized").ifBlank { "Нажмите микрофон и произнесите перевод на греческом." },17,muted)
                button("● Записать ответ",true) { startRecording() }
            }
            else -> Unit
        }
        if (s.optBoolean("answerAudioPending")) {
            if (s.optBoolean("answerAudioError")) {
                label("Ответ сохранён. Не удалось воспроизвести греческое слово.", 16, muted)
                button("Повторить озвучивание") { s.put("answerAudioError", false); save(); training() }
                button("Продолжить без звука") { suspendAnswerAudio(); next() }
            } else label("Верно · слушаем произношение…", 16, success)
        } else if (mode in listOf(Mode.LISTEN_HARD, Mode.SPEAK)) button("Пропустить · −1%") { answer(false,true) }
        if (s.optBoolean("answerAudioPending") && !s.optBoolean("answerAudioError") && answerAudioToken == null) playCorrectChoice(w,if(mode==Mode.WRITE) s.optString("draft").ifBlank { w.greek } else w.greek)
    }
    private fun exitTraining() { stopRecording(); tts?.stop(); home() }
    private fun scoringSessionId(mode: Mode): String {
        val s=session!!
        if(s.has("sessionId")) return s.getString("sessionId")
        val id=UUID.randomUUID().toString();s.put("sessionId",id);save()
        // Recover an unfinished choice question from versions that dropped penalties at zero.
        val rejected=s.optJSONArray("wrongOptions")?.length() ?: 0
        if(isChoice(mode) && rejected>0 && !s.optBoolean("answerAudioPending")) {
            val w=currentWords()[s.getInt("index")]
            val before=s.optJSONObject("initialTotals")?.optString(w.id)?.takeIf { it.isNotBlank() }?.let { Points.parse(it) }
            if(before != null) {
                val initialSkill=before-(store.total(w)-store.points(w.id,mode.skill))
                store.seedSessionBalance(id,w.id,mode.skill,initialSkill-Points.fraction(rejected,1))
            }
        }
        return id
    }
    private fun answer(correct: Boolean, skipped: Boolean = false, selectedOptionId: String? = null) {
        val s = session ?: return
        if (s.optString("feedback").isNotEmpty() || s.optBoolean("answerAudioPending")) return
        stopRecording()
        val mode = Mode.valueOf(s.getString("mode")); val w = currentWords()[s.getInt("index")]
        if(mode in listOf(Mode.LISTEN_EASY,Mode.LISTEN_HARD) && !skipped && !s.optBoolean("audioHeard")) {
            message("Сначала прослушайте вопрос полностью. Если нет звука, проверьте греческий голос Android.");return
        }
        val hints = if(mode == Mode.WRITE) s.optInt("hints") else 0
        val delta = Rules.delta(correct,skipped,hints,Rules.letterCount(w.greek))
        val runId=scoringSessionId(mode)
        store.apply(s.getString("attempt"),mode,mapOf(w.id to delta),if(skipped) "skipped" else if(correct) "correct" else "incorrect",runId,"$runId:${mode.name}:${s.getInt("index")}:${w.id}")
        val key = if(!correct) "wrong" else if(hints>0) "assisted" else "right"
        s.put(key,s.getInt(key)+1)
        if(mode in listOf(Mode.WRITE,Mode.LISTEN_HARD) && correct && !skipped) {
            s.put("answerAudioPending",true).put("answerAudioError",false);save();training();return
        }
        if (isChoice(mode) && !skipped) {
            if (correct) {
                s.put("answerAudioPending", true).put("answerAudioError", false)
            } else {
                val rejected = s.optJSONArray("wrongOptions") ?: JSONArray()
                selectedOptionId?.let { rejected.put(it) }
                s.put("wrongOptions", rejected).put("choiceError", true).put("attempt", UUID.randomUUID().toString())
            }
            save(); training(); return
        }
        s.put("correct",correct).put("feedback",if(correct) "Верно · +${delta.text()}%" else "${if(skipped) "Пропущено" else "Неверно"} · −1%")
        save()
        if (correct && mode != Mode.SPEAK) next() else training()
        if (skipped && mode == Mode.SPEAK) speak(w.greek)
    }
    private fun next() {
        val s = session ?: return
        s.put("index",s.getInt("index")+1).put("hints",0).put("draft","").put("feedback","").put("attempt",UUID.randomUUID().toString()).put("played",false).put("audioHeard",false)
        answerAudioToken = null
        listOf("options", "recognized", "wrongOptions", "choiceError", "answerAudioPending", "answerAudioError", "answerAudioUtterance").forEach { s.remove(it) }
        save(); training()
    }
    private fun matching(list: List<Word>) {
        val s=session!!
        if(s.optBoolean("answerAudioPending") && s.optBoolean("answerAudioError")) {
            silenceMatchingAudio();return
        }
        val offset=s.getInt("index"); val block=list.drop(offset).take(5).takeWhile { it.category == list[offset].category }
        val done=s.getJSONArray("done").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val blockIds = block.map { it.id }.toSet()
        // A session saved with the previous eight-pair layout may already contain solved words in the next block.
        if (block.all { it.id in done } && !s.optBoolean("answerAudioPending")) {
            s.put("index",offset+block.size).put("done",JSONArray((done-blockIds).toList()))
            s.remove("leftOrder");s.remove("rightOrder");save();training();return
        }
        label("Соедините слово и перевод",21,ink,true)
        val pending=s.optBoolean("answerAudioPending")
        val renderedAttempt=s.getString("attempt")
        val matchPage=page
        var feedbackActive=false
        var left: Word? = null; var right: Word? = null
        var leftButton: Button? = null; var rightButton: Button? = null
        fun canChoose() = !feedbackActive && !pending && !s.optBoolean("answerAudioPending") && session===s && page===matchPage && s.optString("attempt")==renderedAttempt
        val row=LinearLayout(this); page.addView(row)
        val lc=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; val rc=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        row.addView(lc,LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(5) }); row.addView(rc,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(5) })
        fun evaluate() {
            if(!canChoose()) return
            val l=left ?: return; val r=right ?: return
            val ok=l.id==r.id; val changes=if(ok) mapOf(l.id to Points.fraction(1,1)) else mapOf(l.id to Points.fraction(-1,1),r.id to Points.fraction(-1,1))
            val runId=scoringSessionId(Mode.MATCH)
            store.apply(s.getString("attempt"),Mode.MATCH,changes,if(ok) "correct" else "incorrect",runId,if(ok) "$runId:MATCH:$offset:${l.id}" else null)
            val key=if(ok) "right" else "wrong"; s.put(key,s.getInt(key)+1)
            s.put("attempt",UUID.randomUUID().toString())
            s.remove("matchFeedback")
            if(ok) {
                val newDone=done+l.id
                s.put("done",JSONArray(newDone.toList()))
                if(!s.optBoolean("matchAudioDisabled"))
                    s.put("answerAudioPending",true).put("answerAudioError",false).put("matchSpokenId",l.id)
            }
            save()
            if(ok) training() else {
                feedbackActive=true;matchingFeedbackActive=true
                val red=Color.rgb(252,218,215);val redText=Color.rgb(164,40,34)
                listOfNotNull(leftButton,rightButton).forEach { it.background=shape(red);it.setTextColor(redText) }
                for(column in listOf(lc,rc)) for(i in 0 until column.childCount) column.getChildAt(i).isEnabled=false
                val afterAttempt=s.getString("attempt")
                row.postDelayed({
                    if(activityActive && trainingVisible && session===s && page===matchPage && s.optString("attempt")==afterAttempt) training()
                },500L)
            }
        }
        fun order(key: String): List<Word> {
            val a=s.optJSONArray(key) ?: JSONArray(block.shuffled().map { it.id }).also { s.put(key,it); save() }
            return (0 until a.length()).mapNotNull { i -> block.find { it.id==a.getString(i) } }
        }
        order("leftOrder").forEach { w ->
            lateinit var b: Button
            b=button(w.display,parent=lc) {
                if(!canChoose() || w.id in done) return@button
                left=w;leftButton?.background=shape(Color.WHITE);b.background=shape(Color.rgb(218,236,253));leftButton=b;evaluate()
            }
            b.minHeight=dp(72);if(pending) b.isEnabled=false; if(w.id in done) { b.isEnabled=false; b.background=shape(Color.rgb(211,233,215)) }
        }
        order("rightOrder").forEach { w ->
            lateinit var b: Button
            b=button(w.russian.first(),parent=rc) {
                if(!canChoose() || w.id in done) return@button
                right=w;rightButton?.background=shape(Color.WHITE);b.background=shape(Color.rgb(218,236,253));rightButton=b;evaluate()
            }
            b.minHeight=dp(72);if(pending) b.isEnabled=false; if(w.id in done) { b.isEnabled=false; b.background=shape(Color.rgb(211,233,215)) }
        }
        if(pending) {
            label("Слушаем произношение…",14,muted)
            if(answerAudioToken==null) {
                val spoken=list.first { it.id==s.getString("matchSpokenId") }
                playCorrectChoice(spoken)
            }
        }
    }
    private fun silenceMatchingAudio() {
        val s=session ?: return
        val firstFailure = !s.optBoolean("matchAudioDisabled")
        s.put("matchAudioDisabled",true)
        answerAudioToken=null
        tts?.stop()
        if(firstFailure) message("Озвучивание недоступно. Эта тренировка продолжится без звука.")
        completeMatchAudio()
    }
    private fun completeMatchAudio() {
        val s=session ?: return
        answerAudioToken=null
        listOf("answerAudioPending","answerAudioError","answerAudioUtterance","matchSpokenId").forEach { s.remove(it) }
        save();training()
    }
    private fun summary() {
        val s=session ?: return home()
        screen("Тренировка завершена",Mode.valueOf(s.getString("mode")).label,pinnedActions=true)
        val initial = s.optJSONObject("initialTotals")
        val results = currentWords()
        label("${results.size} слов · прогресс из 100%",13,muted)
        val columns=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;setPadding(dp(12),dp(12),dp(12),dp(6));layoutDirection=View.LAYOUT_DIRECTION_LTR }
        page.addView(columns,LinearLayout.LayoutParams(-1,-2))
        label("Слово",12,muted,parent=columns).layoutParams=LinearLayout.LayoutParams(0,-2,1f)
        label("Изменение",12,muted,parent=columns).apply { gravity=Gravity.END;layoutParams=LinearLayout.LayoutParams(dp(80),-2) }
        label("Прогресс",12,muted,parent=columns).apply { gravity=Gravity.END;layoutParams=LinearLayout.LayoutParams(dp(64),-2) }
        val table=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;background=shape(Color.WHITE,12) }
        page.addView(table,LinearLayout.LayoutParams(-1,-2))
        if (results.any { initial?.has(it.id) != true }) label("Для тренировок из прошлых версий изменение не записывалось.",14,muted)
        results.forEach { word ->
            val current = store.total(word)
            val before = initial?.optString(word.id)?.takeIf { it.isNotBlank() }?.let { Points.parse(it) }
            val change = before?.let { current - it }
            val color = when { change == null || change == Points.ZERO -> muted; change > Points.ZERO -> success; else -> Color.rgb(164,40,34) }
            val deltaText = when { change == null -> "Изменение недоступно"; change > Points.ZERO -> "+${change.text()}%"; change < Points.ZERO -> "−${(Points.ZERO-change).text()}%"; else -> "0%" }
            if(table.childCount>0) table.addView(View(this).apply { setBackgroundColor(Color.rgb(222,231,242)) },LinearLayout.LayoutParams(-1,dp(1)).apply { leftMargin=dp(12);rightMargin=dp(12) })
            val row=LinearLayout(this).apply {
                orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_LTR
                minimumHeight=dp(56);setPadding(dp(12),dp(8),dp(12),dp(8));tag="result:${word.id}"
                contentDescription="${word.display}. Текущий прогресс ${current.text()} процентов. ${before?.let { "Было ${it.text()} процентов." } ?: ""} Изменение за тренировку: $deltaText"
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            table.addView(row,LinearLayout.LayoutParams(-1,-2))
            row.isClickable=true;row.isFocusable=true
            row.setOnClickListener {
                wordCard(word,JSONObject().put("id",word.id).put("origin","summary").put("scroll",(page.parent as? ScrollView)?.scrollY ?: 0))
            }
            label(word.display,18,ink,parent=row).apply {
                layoutParams=LinearLayout.LayoutParams(0,-2,1f);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            label(if(change==null) "—" else deltaText,14,color,true,row).apply {
                tag="change:${word.id}";gravity=Gravity.END;layoutParams=LinearLayout.LayoutParams(dp(80),-2)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            label("${current.text()}%",16,ink,true,row).apply {
                tag="current:${word.id}";gravity=Gravity.END;layoutParams=LinearLayout.LayoutParams(dp(64),-2)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        val actions = fixedFooter!!
        button("Завершить",true,parent=actions) { session=null;save();home() }
        button("Новая тренировка",parent=actions) { session=null;save();setup() }
    }
    /** Receives the real TTS completion event; never advances on speak() submission alone. */
    internal fun onUtteranceFinished(id: String?) {
        if (!activityActive || !trainingVisible) return
        val current = session ?: return
        if (id != null && id == answerAudioToken && id == current.optString("answerAudioUtterance") && current.optBoolean("answerAudioPending") && trainingVisible) {
            answerAudioToken = null
            if(current.optString("mode")==Mode.MATCH.name) completeMatchAudio() else next()
            return
        }
        if (id == "question:"+current.optString("attempt") && current.optString("feedback").isBlank()) {
            current.put("audioHeard", true); save()
        }
    }
    internal fun onUtteranceFailed(id: String?) {
        if (!activityActive || !trainingVisible) return
        val current = session ?: return
        if (id != null && id == answerAudioToken && id == current.optString("answerAudioUtterance") && current.optBoolean("answerAudioPending")) {
            if(current.optString("mode")==Mode.MATCH.name) { silenceMatchingAudio();return }
            answerAudioToken = null; current.put("answerAudioError", true); current.remove("answerAudioUtterance"); save()
            if (trainingVisible) training()
        } else if (id == "question:"+current.optString("attempt")) {
            message("Не удалось озвучить вопрос. Повторите воспроизведение; прогресс не изменён.")
        }
    }
    private fun suspendAnswerAudio() {
        if (answerAudioToken != null) {
            answerAudioToken = null; session?.remove("answerAudioUtterance"); save(); tts?.stop()
        }
    }
    private fun playCorrectChoice(word: Word, spokenText: String = word.greek) {
        val s = session ?: return
        val token = "answer:"+UUID.randomUUID()
        answerAudioToken = token; s.put("answerAudioUtterance", token); save()
        if (!voiceReady || tts?.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, token) != TextToSpeech.SUCCESS) onUtteranceFailed(token)
        else if(s.optString("mode")==Mode.MATCH.name) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if(session===s && activityActive && trainingVisible && answerAudioToken==token) onUtteranceFailed(token)
        },15000L)
    }
    private fun speak(text: String, question: Boolean = false) {
        if (!voiceReady) { message("Греческий голос недоступен. Установите голос el-GR в настройках Android."); return }
        val current=session
        val isQuestion=question && current != null && current.optString("mode") in listOf(Mode.LISTEN_EASY.name,Mode.LISTEN_HARD.name) && current.optString("feedback").isBlank()
        val utterance=if(isQuestion) "question:"+current!!.getString("attempt") else UUID.randomUUID().toString()
        if(tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,utterance) == TextToSpeech.ERROR) message("Не удалось воспроизвести звук. Прогресс не изменён.")
    }
    private fun stopRecording() { speechToken=null; if(recording) recognizer?.cancel(); recording=false }
    private fun startRecording() {
        if (recording) return
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),31); return }
        if(!SpeechRecognizer.isRecognitionAvailable(this)) { message("На устройстве нет сервиса распознавания речи"); return }
        if(!prefs.getBoolean("speechConsent",false)) {
            AlertDialog.Builder(this).setTitle("Распознавание речи")
                .setMessage("Системный сервис может отправлять аудио в интернет, если офлайн-распознавание недоступно. Приложение аудио не сохраняет. Разрешить использование сервиса?")
                .setPositiveButton("Разрешить") { _,_ -> prefs.edit().putBoolean("speechConsent",true).apply(); startRecording() }
                .setNegativeButton("Отмена",null).show();return
        }
        val token=session?.getString("attempt") ?: return
        tts?.stop(); speechToken=token; recording=true
        recognizer?.destroy(); recognizer=SpeechRecognizer.createSpeechRecognizer(this)
        recognizer!!.setRecognitionListener(object: RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { message("Говорите по-гречески…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(code: Int) {
                if(speechToken != token) return
                recording=false;speechToken=null
                if(code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                    AlertDialog.Builder(this@MainActivity).setTitle("Греческое распознавание недоступно")
                        .setMessage(if(code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
                            "Выбранный системный сервис не поддерживает греческий язык. Обновите или выберите сервис распознавания с поддержкой греческого (например, Google), затем повторите запись с подключением к интернету. Прогресс не изменён."
                        else "Греческая модель распознавания сейчас недоступна. Проверьте интернет и загрузите греческий язык в настройках сервиса распознавания. Прогресс не изменён.")
                        .setPositiveButton("Настройки") { _,_ ->
                            runCatching { startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                                .onFailure { message("Откройте настройки Android → Язык и ввод → Голосовой ввод") }
                        }.setNegativeButton("Закрыть",null).show()
                } else message(when(code) {
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет связи с сервисом распознавания. Проверьте интернет и повторите запись; прогресс не изменён."
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Речь не распознана. Повторите слово по-гречески; прогресс не изменён."
                    else -> "Не удалось распознать речь (код $code). Повторите запись; прогресс не изменён."
                })
            }
            override fun onResults(result: Bundle?) {
                if(speechToken != token || session?.optString("attempt") != token || session?.optString("feedback").orEmpty().isNotEmpty()) return
                recording=false;speechToken=null
                val recognized=result?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if(recognized.isNullOrBlank()) { message("Пустой результат. Повторите запись.");return }
                val s=session!!; val w=currentWords()[s.getInt("index")]
                s.put("recognized",recognized);save()
                answer(Rules.correctSpokenGreekForPrompt(w,recognized,words))
                message("Распознано: $recognized")
            }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int,p: Bundle?) {}
        })
        recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"el-GR").putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,false).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1))
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==31) { if(results.firstOrNull()==PackageManager.PERMISSION_GRANTED) startRecording() else message("Без микрофона произношение недоступно. Остальные тренировки работают.") }
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(request: Int,result: Int,data: Intent?) {
        super.onActivityResult(request,result,data)
        if(result!=RESULT_OK) return
        val uri=data?.data ?: return
        if(request==21) runCatching {
            contentResolver.openOutputStream(uri,"wt")!!.bufferedWriter().use { it.write(store.backup()) }
        }.onSuccess { message("Резервная копия сохранена") }.onFailure { message("Не удалось сохранить файл: ${it.message}") }
        if(request==22) runCatching {
            contentResolver.openInputStream(uri)!!.use { input ->
                val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                while(true) { val read=input.read(buffer); if(read<0) break; require(output.size()+read<=8_000_000) { "Файл больше 8 МБ" }; output.write(buffer,0,read) }
                output.toString("UTF-8")
            }
        }.onSuccess { raw ->
            AlertDialog.Builder(this).setTitle("Импортировать данные?")
                .setMessage("Записи объединятся по стабильным ID. Если в файле есть прогресс, он заменит текущий прогресс совпадающих слов. Сначала сохраните резервную копию.")
                .setPositiveButton("Импортировать") { _,_ -> runCatching { store.importData(raw) }.onSuccess { cachedWords=null; message("Импортировано слов: $it");home() }.onFailure { message("Импорт отклонён: ${it.message}") } }
                .setNegativeButton("Отмена",null).show()
        }.onFailure { message("Не удалось прочитать файл: ${it.message}") }
    }
}
