package com.x3geolibre.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manifest-declared receiver for the adb key push, so it works even when the
 * app is cold. Android 8+ won't deliver IMPLICIT broadcasts to manifest
 * receivers — target it explicitly (the X3Gemini lesson):
 *
 *   adb shell am broadcast -n com.x3geolibre.app/.SetKeyReceiver \
 *     -a com.x3geolibre.app.SET_GROQ_KEY --es key "gsk_..."
 */
class SetKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GeoPrefs.ACTION_SET_GROQ_KEY) return
        val key = intent.getStringExtra("key")?.trim().orEmpty()
        if (key.isBlank()) {
            Log.w("X3GeoLibre", "SET_GROQ_KEY broadcast without --es key")
            return
        }
        GeoPrefs.setGroqKey(context, key)
        Log.i("X3GeoLibre", "Groq key persisted from broadcast (${key.length} chars)")
    }
}
