package ru.greeklearning.app

import org.junit.Assert.*
import org.junit.Test

class LearningTest {
    @Test fun exactHintFractionsAccumulateWithoutRounding() {
        val third = Rules.delta(true,hints=2,length=3)
        assertEquals(Points.fraction(1,1), third+third+third)
        val seventh = Points.fraction(1,7)
        assertEquals(Points.fraction(1,1), (1..7).fold(Points.ZERO) { p,_ -> p+seventh })
    }
    @Test fun fourLetterHintsHaveExpectedRewards() {
        for(h in 0..4) assertEquals(Points.fraction(4-h,4),Rules.delta(true,hints=h,length=4))
        assertEquals(Points.fraction(-1,1),Rules.delta(false,hints=3,length=4))
        assertEquals(Points.fraction(-1,1),Rules.delta(true,skipped=true,hints=4,length=4))
    }
    @Test fun scoreLimitsAndExampleSequences() {
        fun score(sequence: List<Boolean>) = sequence.fold(Points.ZERO) { p, right -> (p+Rules.delta(right)).clamped() }
        assertEquals(Points.fraction(1,1),score(listOf(true,true,true,false,false)))
        assertEquals(Points.ZERO,score(listOf(true,true,false,false,false)))
        assertEquals(Points.MAX,score(List(30){true}))
        assertEquals(Points.fraction(24,1),(Points.MAX+Rules.delta(false)).clamped())
        assertEquals(Points.ZERO,(Points.ZERO+Rules.delta(false)).clamped())
    }
    @Test fun greekNormalizationPreservesToneAndAcceptsDecomposedUnicode() {
        val w=Word("x","νερό",listOf("вода"),"noun")
        assertTrue(Rules.correct(w," ΝΕΡΟ\u0301 ",false))
        assertFalse(Rules.correct(w,"νερο",false))
        assertEquals(4,Rules.letterCount("νερο\u0301"))
        assertEquals("νερ",Rules.prefix("νερό",3))
        assertEquals("νερό",Rules.prefix("νερό",4))
    }
    @Test fun writtenAndSpokenNounsAcceptOnlyTheirOwnOptionalArticle() {
        val friend=Word("friend","φίλος",listOf("друг"),"noun",article="ο")
        val water=Word("water","νερό",listOf("вода"),"noun",article="το")
        val love=Word("love","αγάπη",listOf("любовь"),"noun",article="η")
        val pool=listOf(friend,water,love)
        pool.forEach { w ->
            assertTrue(Rules.correctSpokenGreekForPrompt(w,w.greek,pool))
            assertTrue(Rules.correctSpokenGreekForPrompt(w,"  ${w.display.uppercase(java.util.Locale.ROOT)}. ",pool))
            assertTrue(Rules.correctGreekForPrompt(w,w.display,pool))
            assertTrue(Rules.correctGreekAnswer(w,w.display,baseOnly=true))
            assertTrue(Rules.correctGreekAnswer(w,w.greek,baseOnly=true))
        }
        assertFalse(Rules.correctSpokenGreekForPrompt(friend,"η φίλος",pool))
        assertFalse(Rules.correctGreekForPrompt(friend,"η φίλος",pool))
        assertFalse(Rules.correctGreekForPrompt(friend,"ο φιλος",pool))
        assertFalse(Rules.correctGreekAnswer(friend,"η φίλος",baseOnly=true))
        assertFalse(Rules.correctSpokenGreekForPrompt(friend,"ο νερό",pool))
        assertFalse(Rules.correctSpokenGreekForPrompt(friend,"ο φιλος",pool))
        assertFalse(Rules.correctSpokenGreekForPrompt(water,friend.display,pool))
    }
    @Test fun russianAcceptedSynonymsAndYo() {
        val w=Word("x","παιδί",listOf("ребёнок","дитя"),"noun")
        assertTrue(Rules.correct(w," РЕБЕНОК ",true))
        assertTrue(Rules.correct(w,"дитя",true))
        assertFalse(Rules.correct(w,"ребенокк",true))
    }
    @Test fun synonymousGreekAnswersAndAmbiguousAudioAreNotMarkedWrong() {
        val a=Word("a","ξέρω",listOf("знать"),"verb")
        val b=Word("b","γνωρίζω",listOf("знать"),"verb")
        val c=Word("c","γράφω",listOf("писать"),"verb")
        assertTrue(Rules.correctGreekForPrompt(a,"γνωρίζω",listOf(a,b,c)))
        assertFalse(Rules.correctGreekForPrompt(a,"γράφω",listOf(a,b,c)))
        val actor=Word("actor","ηθοποιός",listOf("актёр"),"noun",article="ο")
        val actress=Word("actress","ηθοποιός",listOf("актриса"),"noun",article="η")
        val chair=Word("chair","καρέκλα",listOf("стул"),"noun")
        assertTrue(Rules.correctRussianForAudio(actor,"актриса",listOf(actor,actress,chair)))
        repeat(10) { assertFalse(Rules.options(actor,listOf(actor,actress,chair),true).any { it.id==actress.id }) }
    }
    @Test fun matchingDoesNotIncludeAmbiguousPairsAndSearchIgnoresToneWithoutRelaxingSpelling() {
        val a=Word("1","μαγαζί",listOf("магазин"),"noun")
        val b=Word("2","κατάστημα",listOf("магазин"),"noun")
        val c=Word("3","νερό",listOf("вода"),"noun")
        assertEquals(listOf(a,c),Rules.matchingWords(listOf(a,b,c)))
        assertEquals(Rules.searchKey("νερό"),Rules.searchKey("ΝΕΡΟ"))
        assertFalse(Rules.correct(c,"νερο",false))
        assertTrue(Rules.correct(Word("phrase","Γεια!",listOf("Привет!"),"phrase"),"привет",true))
    }
    @Test fun trainingPoolShufflesEntireGroupBeforeTakingWordsWithoutDuplicates() {
        val nouns=(0 until 90).map { i -> Word("noun-$i",listOf("α","β","γ")[i/30]+i,listOf("значение-$i"),"noun") }
        val verb=Word("verb","γράφω",listOf("писать"),"verb")
        val original=nouns+verb+nouns.first()
        val first=Rules.trainingPool(original,"noun",kotlin.random.Random(2026)).take(10)
        val next=Rules.trainingPool(original,"noun",kotlin.random.Random(2027)).take(10)
        assertEquals(10,first.size);assertEquals(10,first.map { it.id }.distinct().size)
        assertTrue(first.all { it.category=="noun" })
        assertTrue(first.map { it.greek.first() }.distinct().size>1)
        assertNotEquals(nouns.take(10),first);assertNotEquals(first,next)
        assertEquals(90,Rules.trainingPool(original,"noun",kotlin.random.Random(1)).size)
    }
    @Test fun reviewSelectionHasPriorityWithinGroupAndDoesNotLoseOverflowOrDuplicateWords() {
        val a=Word("a","νερό",listOf("вода"),"noun");val b=Word("b","σπίτι",listOf("дом"),"noun")
        val c=Word("c","γράφω",listOf("писать"),"verb")
        val pool=Rules.trainingPool(listOf(a,b,c),"noun",kotlin.random.Random(9))
        val ordered=Rules.prioritizeReviews(pool,listOf("b","a","c","b"))
        assertEquals(listOf(b,a),ordered);assertEquals(listOf(b),ordered.take(1))
    }
    @Test fun properNamesAreSeparateFromNounsInQuestionsOptionsAndAcceptedSynonyms() {
        val noun=Word("noun","φως",listOf("свет"),"noun")
        val name=Word("name","Φωτεινή",listOf("свет"),"noun",properName=true)
        val chair=Word("chair","καρέκλα",listOf("стул"),"noun")
        val pool=listOf(noun,name,chair)
        assertEquals(setOf(noun,chair),Rules.trainingPool(pool,"noun").toSet())
        assertEquals(listOf(name),Rules.trainingPool(pool,"proper_noun"))
        repeat(10) { assertTrue(Rules.options(noun,pool,true).all { it.category=="noun" }) }
        assertFalse(Rules.correctGreekForPrompt(noun,name.greek,pool))
    }
    @Test fun distractorsStayInGroupAndExcludeOverlappingTranslations() {
        val target=Word("1","σπίτι",listOf("дом"),"noun")
        val sameMeaning=Word("2","οικία",listOf("дом"),"noun")
        val verb=Word("3","γράφω",listOf("писать"),"verb")
        val noun=Word("4","νερό",listOf("вода"),"noun")
        repeat(20) {
            val options=Rules.options(target,listOf(target,sameMeaning,verb,noun),true)
            assertEquals(setOf("1","4"),options.map { it.id }.toSet())
        }
    }
}
