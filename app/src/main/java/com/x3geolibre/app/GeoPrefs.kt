package com.x3geolibre.app

import android.content.Context

/** Tiny settings store. Currently just the Groq API key for dictation. */
object GeoPrefs {
    private const val FILE = "x3geolibre_prefs"
    private const val KEY_GROQ = "groq_api_key"

    /** adb: am broadcast -a com.x3geolibre.app.SET_GROQ_KEY --es key gsk_... */
    const val ACTION_SET_GROQ_KEY = "com.x3geolibre.app.SET_GROQ_KEY"

    fun groqKey(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_GROQ, null)?.trim()?.takeIf { it.isNotBlank() }

    fun setGroqKey(context: Context, key: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_GROQ, key.trim()).apply()
    }
}
