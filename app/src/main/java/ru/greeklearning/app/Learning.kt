package ru.greeklearning.app

import java.math.BigInteger
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/** Exact arithmetic: partial hints must not round 1/3 on every answer. */
@ConsistentCopyVisibility
data class Points private constructor(val n: BigInteger, val d: BigInteger) : Comparable<Points> {
    operator fun plus(other: Points) = of(n * other.d + other.n * d, d * other.d)
    operator fun minus(other: Points) = of(n * other.d - other.n * d, d * other.d)
    override fun compareTo(other: Points) = (n * other.d).compareTo(other.n * d)
    fun clamped() = when { this < ZERO -> ZERO; this > MAX -> MAX; else -> this }
    fun approximate(): Double = n.toBigDecimal().divide(d.toBigDecimal(), 8, java.math.RoundingMode.HALF_UP).toDouble()
    fun text(): String = n.toBigDecimal().divide(d.toBigDecimal(), 2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')
    override fun toString() = "$n/$d"
    companion object {
        val ZERO = fraction(0, 1)
        val MAX = fraction(25, 1)
        fun fraction(n: Int, d: Int) = of(n.toBigInteger(), d.toBigInteger())
        fun of(n: BigInteger, d: BigInteger): Points {
            require(d.signum() > 0)
            val gcd = n.gcd(d)
            return Points(n / gcd, d / gcd)
        }
        fun parse(raw: String): Points {
            require(raw.length <= 2048)
            val a = raw.split('/')
            require(a.size == 2)
            return of(a[0].toBigInteger(), a[1].toBigInteger())
        }
    }
}

enum class Mode(val label: String, val skill: Int) {
    TO_RU("Выбрать перевод", 0), TO_EL("Выбрать слово", 0), MATCH("Сопоставление", 0),
    WRITE("Написание", 1), LISTEN_EASY("Аудирование · начальное", 2),
    LISTEN_HARD("Аудирование · продвинутое", 2), SPEAK("Произношение", 3)
}

data class Word(val id: String, val greek: String, val russian: List<String>, val pos: String,
                val article: String = "", val forms: List<String> = emptyList(), val source: String = "user",
                val sourceEntries: List<String> = emptyList(), val sourcePages: List<Int> = emptyList(),
                val inflections: List<String> = emptyList(), val english: List<String> = emptyList(), val properName: Boolean = false) {
    val category get() = if(properName) "proper_noun" else pos
    val display get() = listOf(article, greek).filter { it.isNotBlank() }.joinToString(" ")
    val answers get() = (listOf(greek) + forms).distinct()
}

object Rules {
    fun choiceTotal(errors: Int,correct: Boolean): Points {
        require(errors>=0)
        return when {
            errors>=2 -> Points.fraction(-1,1)
            !correct -> Points.ZERO
            errors==1 -> Points.fraction(1,2)
            else -> Points.fraction(1,1)
        }
    }
    val parts = linkedMapOf("noun" to "Существительные", "proper_noun" to "Имена собственные", "verb" to "Глаголы", "adjective" to "Прилагательные",
        "adverb" to "Наречия", "pronoun" to "Местоимения", "numeral" to "Числительные", "phrase" to "Выражения",
        "preposition" to "Предлоги", "conjunction" to "Союзы", "particle" to "Частицы", "article" to "Артикли")
    fun normalize(s: String, russian: Boolean = false): String {
        val text = Normalizer.normalize(s.trim(), Normalizer.Form.NFC)
            .trimEnd('!', '?', ';', '.', '·').trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        return if (russian) text.replace('ё', 'е') else text
    }
    fun searchKey(s: String): String = Normalizer.normalize(normalize(s), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace('ς','σ')
    fun correct(word: Word, input: String, russian: Boolean): Boolean =
        (if (russian) word.russian else word.answers).any { normalize(it, russian) == normalize(input, russian) }
    fun correctGreekAnswer(word: Word, input: String, baseOnly: Boolean = false): Boolean {
        val entered = normalize(input)
        val answers = if(baseOnly) listOf(word.greek) else word.answers
        return answers.any { entered == normalize(it) ||
            (word.pos == "noun" && word.article.isNotBlank() && entered == normalize("${word.article} $it")) }
    }
    fun correctGreekForPrompt(word: Word, input: String, pool: List<Word>): Boolean {
        val prompt = normalize(word.russian.first(), true)
        return pool.asSequence().filter { it.category == word.category && it.russian.any { ru -> normalize(ru,true) == prompt } }
            .any { candidate -> correctGreekAnswer(candidate,input) }
    }
    fun correctSpokenGreekForPrompt(word: Word, input: String, pool: List<Word>): Boolean =
        correctGreekForPrompt(word,input,pool)
    fun correctRussianForAudio(word: Word, input: String, pool: List<Word>): Boolean {
        val greek=word.answers.map { normalize(it) }.toSet()
        return pool.asSequence().filter { it.category == word.category && it.answers.any { el -> normalize(el) in greek } }
            .any { candidate -> correct(candidate,input,true) }
    }
    fun trainingPool(words: List<Word>, partOfSpeech: String, random: kotlin.random.Random = kotlin.random.Random.Default): List<Word> =
        words.filter { it.category == partOfSpeech }.distinctBy { it.id }.shuffled(random)
    fun prioritizeReviews(pool: List<Word>, queuedIds: List<String>): List<Word> {
        val byId=pool.associateBy { it.id }
        return (queuedIds.mapNotNull { byId[it] }+pool).distinctBy { it.id }
    }
    fun matchingWords(pool: List<Word>): List<Word> {
        val russian = mutableSetOf<String>(); val greek = mutableSetOf<String>()
        return pool.filter { w ->
            val ru = w.russian.map { normalize(it,true) }.toSet()
            val el = w.answers.map { normalize(it) }.toSet()
            if (ru.any { it in russian } || el.any { it in greek }) false
            else { russian += ru; greek += el; true }
        }
    }
    fun letters(answer: String): List<String> {
        val text = Normalizer.normalize(answer, Normalizer.Form.NFC)
        val iterator = BreakIterator.getCharacterInstance(Locale("el")); iterator.setText(text)
        val list = mutableListOf<String>(); var start = iterator.first(); var end = iterator.next()
        while (end != BreakIterator.DONE) { list += text.substring(start, end); start = end; end = iterator.next() }
        return list
    }
    fun letterCount(answer: String) = letters(answer).count { part -> part.any { it.isLetter() } }
    fun prefix(answer: String, count: Int): String {
        var seen = 0
        return letters(answer).takeWhile { part ->
            if (part.any { it.isLetter() }) seen++
            seen <= count
        }.joinToString("")
    }
    fun delta(correct: Boolean, skipped: Boolean = false, hints: Int = 0, length: Int = 1): Points {
        if (!correct || skipped) return Points.fraction(-1, 1)
        require(length > 0 && hints in 0..length)
        return Points.fraction(length - hints, length)
    }
    fun options(target: Word, pool: List<Word>, russian: Boolean): List<Word> {
        fun labels(w: Word): Set<String> = if (russian) w.russian.map { normalize(it, true) }.toSet()
            else w.answers.map { normalize(it) }.toSet()
        val seen = labels(target).toMutableSet()
        val seenGreek = target.answers.map { normalize(it) }.toMutableSet()
        val distractors = pool.shuffled().asSequence().filter { it.id != target.id && it.category == target.category }.filter { w ->
            val translationsOverlap = w.russian.any { r -> target.russian.any { normalize(it, true) == normalize(r, true) } }
            val greekLabels=w.answers.map { normalize(it) }
            if (translationsOverlap || labels(w).any { it in seen } || greekLabels.any { it in seenGreek }) false
            else { seen += labels(w);seenGreek += greekLabels;true }
        }.take(5).toList()
        return (distractors + target).shuffled()
    }
}
