package ru.greeklearning.app

import android.content.Context
import org.json.JSONObject

/** Read-only reference content, deliberately separate from words/progress and answer validation. */
class WordDetails(context: Context) {
    private val entries: JSONObject by lazy {
        JSONObject(context.assets.open("word-details.json").bufferedReader().use { it.readText() }).getJSONObject("words")
    }
    fun get(id: String): JSONObject? = entries.optJSONObject(id)
}
