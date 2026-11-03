package ru.greeklearning.app

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice

/** Keep Greek pronunciation and natural pitch; prefer installed local voices, without requesting downloads. */
object SpeechSettings {
    const val RATE = 0.90f
    const val PITCH = 1.0f
    fun chooseGreekVoice(voices: Collection<Voice>): Voice? = voices.asSequence()
        .filter { it.locale.language == "el" && !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
        .sortedWith(compareByDescending<Voice> { it.locale.country == "GR" }
            .thenByDescending { it.quality }.thenBy { it.latency }.thenBy { it.name })
        .firstOrNull()
}
