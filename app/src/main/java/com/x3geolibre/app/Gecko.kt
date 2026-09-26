package com.x3geolibre.app

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Process-wide [GeckoRuntime] holder. GeckoView allows exactly one runtime per
 * process, so every session (both eyes, future windows) shares this one. Using a
 * single runtime + profile also means cookies / login persist across restarts.
 */
object Gecko {
    @Volatile private var runtime: GeckoRuntime? = null

    fun runtime(context: Context): GeckoRuntime {
        return runtime ?: synchronized(this) {
            runtime ?: GeckoRuntime.create(
                context.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .remoteDebuggingEnabled(true)   // Firefox remote debugging over adb
                    .consoleOutput(true)            // pipe page console.log to logcat
                    .aboutConfigEnabled(true)
                    // Render at 1x device-pixel-ratio. The X3 eye is only 640x480; a
                    // higher DPR makes Gecko paint/composite far more pixels than it
                    // shows, which starves voice audio while ChatGPT streams output.
                    // 1x cuts that cost sharply (and speeds up rendering generally).
                    .displayDensityOverride(1.0f)
                    .displayDpiOverride(160)
                    // Force dark. GeoLibre (like most modern web apps) picks its
                    // theme from prefers-color-scheme; the X3's GeckoView reports
                    // "light" by default, so without this the map loads light. This
                    // makes the media query resolve dark engine-wide, which is both
                    // the requested default and easier on the eyes on a waveguide.
                    .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
                    .build()
            ).also { runtime = it }
            // Autoplay is allowed per-request in the session PermissionDelegate's
            // onContentPermissionRequest (VALUE_ALLOW) so ChatGPT's spoken reply
            // plays without a per-response user gesture.
        }
    }
}
