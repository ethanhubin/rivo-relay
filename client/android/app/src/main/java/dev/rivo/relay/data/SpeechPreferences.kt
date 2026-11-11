package dev.rivo.relay.data

import android.content.Context

/** Device-wide speech vocabulary, independent of the selected Relay node. */
internal class SpeechPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("speech", Context.MODE_PRIVATE)
    var vocabulary: String
        get() = preferences.getString("vocabulary", null) ?: DEFAULT_SPEECH_VOCABULARY
        set(value) {
            require(value.length <= SPEECH_VOCABULARY_LIMIT)
            preferences.edit().putString("vocabulary", value).apply()
        }
}
