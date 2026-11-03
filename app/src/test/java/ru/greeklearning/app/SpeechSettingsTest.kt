package ru.greeklearning.app

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SpeechSettingsTest {
    @Test fun choosesHighQualityInstalledGreekVoiceWithoutForeignOrUninstalledFallback() {
        fun voice(name: String,locale: Locale,quality: Int,network: Boolean=false,features: Set<String> = emptySet()) =
            Voice(name,locale,quality,Voice.LATENCY_LOW,network,features)
        val low=voice("greek-low",Locale("el","GR"),Voice.QUALITY_LOW)
        val high=voice("greek-high",Locale("el","GR"),Voice.QUALITY_HIGH)
        val network=voice("greek-network",Locale("el","GR"),Voice.QUALITY_VERY_HIGH,true)
        val english=voice("english",Locale.US,Voice.QUALITY_VERY_HIGH)
        val missing=voice("missing",Locale("el","GR"),Voice.QUALITY_VERY_HIGH,false,setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        assertEquals(high,SpeechSettings.chooseGreekVoice(listOf(low,network,english,missing,high)))
        assertNull(SpeechSettings.chooseGreekVoice(listOf(network,english,missing)))
        assertEquals(0.9f,SpeechSettings.RATE,0f);assertEquals(1f,SpeechSettings.PITCH,0f)
    }
}
