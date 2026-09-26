package com.x3geolibre.app

import android.content.Context

/** Tiny settings store: Groq key (keyboard Mic) + Gemini key (AI Assistant). */
object GeoPrefs {
    private const val FILE = "x3geolibre_prefs"
    private const val KEY_GROQ = "groq_api_key"
    private const val KEY_GEMINI = "gemini_api_key"

    /** adb: am broadcast -a com.x3geolibre.app.SET_GROQ_KEY --es key gsk_... */
    const val ACTION_SET_GROQ_KEY = "com.x3geolibre.app.SET_GROQ_KEY"

    /** adb: am broadcast -a com.x3geolibre.app.SET_GEMINI_KEY --es key AIza... */
    const val ACTION_SET_GEMINI_KEY = "com.x3geolibre.app.SET_GEMINI_KEY"

    fun groqKey(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_GROQ, null)?.trim()?.takeIf { it.isNotBlank() }

    fun setGroqKey(context: Context, key: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_GROQ, key.trim()).apply()
    }

    // No seed key is bundled — set one over adb (see ACTION_SET_GEMINI_KEY above).
    fun geminiKey(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_GEMINI, null)?.trim()?.takeIf { it.isNotBlank() } ?: ""

    fun setGeminiKey(context: Context, key: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_GEMINI, key.trim()).apply()
    }
}
