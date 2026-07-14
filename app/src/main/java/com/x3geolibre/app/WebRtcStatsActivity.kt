package com.x3geolibre.app

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Diagnostic: open Firefox's built-in about:webrtc in the SAME runtime as the main
 * app, so after a voice call drops we can read the (recently-closed) PeerConnection's
 * ICE/DTLS state and close reason — no page injection needed (sidesteps ChatGPT CSP).
 * Reproduce the drop in GeckoTestActivity, then:
 *   adb shell am start -n com.x3geolibre.app/.WebRtcStatsActivity
 */
class WebRtcStatsActivity : Activity() {
    private lateinit var session: GeckoSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val geckoView = GeckoView(this)
        setContentView(geckoView)
        session = GeckoSession()
        session.open(Gecko.runtime(this))
        geckoView.setSession(session)
        session.loadUri("about:webrtc")
    }

    override fun onDestroy() {
        runCatching { session.close() }
        super.onDestroy()
    }
}
