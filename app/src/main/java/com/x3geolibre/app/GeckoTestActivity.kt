package com.x3geolibre.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

/**
 * PHASE 4 (keyboard + text input): binocular GeckoView with trackpad cursor,
 * click/scroll, and the custom on-screen keyboard wired into GeckoView's text
 * input (so no physical keyboard / system IME is needed). Launch:
 *   adb shell am start -n com.x3geolibre.app/.GeckoTestActivity
 */
class GeckoTestActivity : Activity() {
    private lateinit var session: GeckoSession
    private lateinit var binocular: GeckoBinocularLayout
    private var inputConnection: InputConnection? = null
    private var dictation: GroqDictation? = null
    private var keyReceiver: android.content.BroadcastReceiver? = null

    // ── x3geolibre bridge state ─────────────────────────────────────
    private var bridgePort: WebExtension.Port? = null
    @Volatile private var lastFix: IpLocator.Fix? = null
    @Volatile private var pageLoadedOk = false
    @Volatile private var loadRequested = false
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "X3GeoLibre:Gecko").apply { setReferenceCounted(false) }
    }
    private val wifiLock: WifiManager.WifiLock? by lazy {
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.run {
            // LOW_LATENCY disables Wi-Fi power-save for real-time traffic — without it
            // the radio naps and drops the WebRTC STUN keepalives, so the voice call
            // dies at exactly WebRTC's 30s ICE consent-freshness timeout.
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            createWifiLock(mode, "X3GeoLibre:Gecko").apply { setReferenceCounted(false) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersive()

        // Keep the mic session alive + the process at max priority (a foreground
        // microphone service + wakelocks) — without this the voice call gets
        // throttled/revoked after a short window on these glasses.
        acquireLocks()
        startPlaybackService()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 3001)
        }

        val runtime = Gecko.runtime(this)
        setLowCpuVoicePrefs()
        val settings = GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
            .build()
        session = GeckoSession(settings)
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(s: GeckoSession, success: Boolean) {
                Log.d(TAG, "onPageStop success=$success")
                if (success) pageLoadedOk = true
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, title: String?) {
                if (title != null && title.startsWith("WRTC|")) Log.d("X3GeoLibre-WRTC", title.removePrefix("WRTC|"))
            }
        }
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                s: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): org.mozilla.geckoview.GeckoResult<Int> {
                // Grant content permissions (notably autoplay-audible, so ChatGPT's
                // spoken voice reply plays).
                return org.mozilla.geckoview.GeckoResult.fromValue(
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                )
            }

            override fun onAndroidPermissionsRequest(
                s: GeckoSession, permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback
            ) { callback.grant() }
            override fun onMediaPermissionRequest(
                s: GeckoSession, uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) { callback.grant(null, audio?.firstOrNull()) }
        }

        val geckoView = GeckoView(this)
        session.open(runtime)
        geckoView.setSession(session)

        binocular = GeckoBinocularLayout(this, geckoView)
        binocular.textInput = object : GeckoBinocularLayout.TextInput {
            override fun commit(text: String) { ic()?.commitText(text, 1) }
            override fun backspace() { ic()?.deleteSurroundingText(1, 0) }
            override fun enter() {
                val c = ic() ?: return
                c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            override fun clear() { ic()?.deleteSurroundingText(100000, 100000) }
            override fun moveCaret(delta: Int) { /* not wired for gecko yet */ }
        }
        setContentView(binocular)

        // ── Groq dictation on the keyboard's Mic key ─────────────────────
        // Tap Mic to record, tap again to stop; the Whisper transcript is
        // typed into whatever Gemini field is focused. Key lives in Settings
        // (the ⚙ hotspot, top-right) or arrives over adb (SET_GROQ_KEY).
        val groq = GroqDictation(
            context = this,
            keyProvider = { GeoPrefs.groqKey(this) },
            onState = { rec -> runOnUiThread { binocular.setMicActive(rec) } },
            onResult = { text ->
                runOnUiThread {
                    binocular.textInput?.commit(text)
                    binocular.showStatus("Dictated ${text.length} chars")
                }
            },
            onError = { msg -> runOnUiThread { binocular.showStatus(msg) } }
        )
        dictation = groq
        binocular.micHandler = {
            if (GeoPrefs.groqKey(this) == null && !groq.isRecording()) {
                binocular.showStatus("No Groq key — opening Settings")
                binocular.openSettings()
            } else {
                groq.toggle()
            }
        }
        binocular.onSaveGroqKey = { key -> GeoPrefs.setGroqKey(this, key) }
        binocular.currentGroqKey = { GeoPrefs.groqKey(this) }

        // Runtime receiver so the plain implicit adb broadcast also works
        // while the app is up (manifest SetKeyReceiver covers the cold case).
        keyReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val k = i.getStringExtra("key")?.trim().orEmpty()
                if (k.isNotBlank()) {
                    GeoPrefs.setGroqKey(this@GeckoTestActivity, k)
                    binocular.showStatus("Groq key set via adb (${k.length} chars)")
                }
            }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this, keyReceiver,
                android.content.IntentFilter(GeoPrefs.ACTION_SET_GROQ_KEY),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        }

        // Gecko drives our on-screen keyboard (not the system IME) through its
        // text-input delegate: when a field focuses, showSoftInput fires.
        session.textInput.setDelegate(object : GeckoSession.TextInputDelegate {
            override fun restartInput(s: GeckoSession, reason: Int) {
                inputConnection = null
                if (reason == GeckoSession.TextInputDelegate.RESTART_REASON_BLUR) {
                    runOnUiThread { binocular.hideKeyboard() }
                }
            }
            override fun showSoftInput(s: GeckoSession) { runOnUiThread { binocular.showKeyboard() } }
            override fun hideSoftInput(s: GeckoSession) { runOnUiThread { binocular.hideKeyboard() } }
        })

        // ── bridge extension: geolocation feed / right-click / icon rail ──
        runtime.webExtensionController
            .ensureBuiltIn("resource://android/assets/geolibre-ext/", "bridge@x3geolibre.app")
            .accept({ ext ->
                if (ext == null) { Log.w(TAG, "bridge ext null"); return@accept }
                runOnUiThread {
                    session.webExtensionController.setMessageDelegate(ext, portDelegate, "x3geolibre")
                    Log.i(TAG, "bridge extension installed")
                }
            }, { e -> Log.w(TAG, "bridge ext install failed: ${e?.message}") })

        // Long-press on the right pad = right-click at the cursor.
        binocular.longPressHandler = { fx, fy ->
            val sent = runCatching {
                bridgePort?.postMessage(
                    org.json.JSONObject().put("type", "contextmenu")
                        .put("fx", fx.toDouble()).put("fy", fy.toDouble())
                ) != null
            }.getOrDefault(false)
            if (!sent) binocular.showStatus("Context menu unavailable (page still loading?)")
        }

        // The glasses' Wi-Fi takes ~5s to come up after wake — gate the first
        // load on a validated network instead of showing a dead error page.
        loadWhenOnline()
        // Mark the session foreground so GeckoView keeps its content process at high
        // priority — otherwise Android can freeze/throttle that child process and
        // WebRTC dies on a fixed timer regardless of load.
        session.setActive(true)
    }

    override fun onResume() {
        super.onResume()
        if (this::session.isInitialized) session.setActive(true)
    }

    /**
     * Cut the CPU cost of real-time voice on this 4-core chip. Firefox does echo
     * cancellation / noise suppression / auto-gain in software (heavy), and the
     * user's outbound audio comes through choppy while the call also starves
     * WebRTC's keepalive and dies at the 30s ICE-consent timeout. Turning that
     * processing off (and the unused video path) frees the cores for clean audio.
     */
    private fun setLowCpuVoicePrefs() {
        val B = GeckoPreferenceController.PREF_BRANCH_USER
        val prefs = listOf(
            "media.getusermedia.aec_enabled" to false,
            "media.getusermedia.noise_enabled" to false,
            "media.getusermedia.agc_enabled" to false,
            "media.getusermedia.hpf_enabled" to false,
            "media.peerconnection.video.enabled" to false
        )
        prefs.forEach { (k, v) ->
            runCatching {
                GeckoPreferenceController.setGeckoPref(k, v, B)
                    .accept({ Log.d(TAG, "pref $k=$v set") }, { e -> Log.w(TAG, "pref $k failed: ${e?.message}") })
            }
        }
    }

    /** Fresh-or-cached InputConnection onto GeckoView's currently focused field. */
    private fun ic(): InputConnection? {
        if (inputConnection == null) {
            inputConnection = runCatching {
                session.textInput.onCreateInputConnection(EditorInfo())
            }.getOrNull()
        }
        return inputConnection
    }

    private fun enableImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            this::binocular.isInitialized && binocular.isKeyboardVisible() -> binocular.hideKeyboard()
            this::session.isInitialized -> session.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun acquireLocks() {
        runCatching { if (!wakeLock.isHeld) wakeLock.acquire() }
        runCatching { wifiLock?.let { if (!it.isHeld) it.acquire() } }
    }

    private fun releaseLocks() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
    }

    private fun startPlaybackService() {
        val intent = Intent(this, GeoPlaybackService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }.onFailure { Log.w(TAG, "start service failed: ${it.message}") }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { enableImmersive(); acquireLocks() }
    }

    override fun onDestroy() {
        runCatching { dictation?.cancel() }
        runCatching { keyReceiver?.let { unregisterReceiver(it) } }
        keyReceiver = null
        releaseLocks()
        runCatching { stopService(Intent(this, GeoPlaybackService::class.java)) }
        runCatching { session.close() }
        super.onDestroy()
    }

    // ────────────────────────────────────────────────────────────────
    //  x3geolibre bridge: Wi-Fi gate, IP position, native port
    // ────────────────────────────────────────────────────────────────

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Poll for a network for up to ~20s (Wi-Fi needs ~5s after wake), then
     *  load; on timeout load anyway and re-load when connectivity arrives. */
    private fun loadWhenOnline(waitedMs: Long = 0L) {
        if (loadRequested) return
        if (isOnline()) {
            loadRequested = true
            binocular.showStatus("Loading GeoLibre…")
            session.loadUri(HOME)
            startIpLocate()
            watchForReconnect()
            return
        }
        if (waitedMs == 0L) binocular.showStatus("Waiting for Wi-Fi (takes ~5s)…")
        if (waitedMs >= 20_000L) {
            loadRequested = true
            binocular.showStatus("No network yet — will retry when Wi-Fi connects")
            session.loadUri(HOME)
            watchForReconnect()
            return
        }
        ui.postDelayed({ loadWhenOnline(waitedMs + 700L) }, 700L)
    }

    /** If the first load failed (no network), reload when a network appears. */
    private fun watchForReconnect() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    ui.post {
                        if (!pageLoadedOk) {
                            Log.i(TAG, "network arrived — reloading GeoLibre")
                            binocular.showStatus("Wi-Fi connected — loading GeoLibre…")
                            session.loadUri(HOME)
                        }
                        if (lastFix == null) startIpLocate()
                    }
                }
            })
        }
    }

    private fun startIpLocate() {
        Thread({
            val fix = IpLocator.locate()
            if (fix != null) {
                lastFix = fix
                Log.i(TAG, "IP fix: ${fix.label} (${fix.lat}, ${fix.lon})")
                ui.post {
                    sendPosition(fix)
                    binocular.showStatus("Located: ${fix.label}")
                }
            } else {
                Log.w(TAG, "IP locate failed")
            }
        }, "x3geo-iploc").start()
    }

    private fun sendPosition(fix: IpLocator.Fix) {
        runCatching {
            bridgePort?.postMessage(
                org.json.JSONObject()
                    .put("type", "position")
                    .put("lat", fix.lat)
                    .put("lon", fix.lon)
                    .put("acc", fix.accuracyM)
                    .put("label", fix.label)
            )
        }
    }

    /** Content-script port: receives ready/want-position, feeds positions. */
    private val portDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            Log.i(TAG, "bridge port connected")
            bridgePort = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, p: WebExtension.Port) {
                    val obj = message as? org.json.JSONObject ?: return
                    when (obj.optString("type")) {
                        "ready" -> lastFix?.let { runOnUiThread { sendPosition(it) } }
                        "probe" -> Log.i(TAG, "X3PROBE $obj")
                        "want-position" -> {
                            val fix = lastFix
                            if (fix != null) runOnUiThread { sendPosition(fix) }
                            else startIpLocate()
                        }
                    }
                }
                override fun onDisconnect(p: WebExtension.Port) {
                    if (bridgePort == p) bridgePort = null
                }
            })
            lastFix?.let { sendPosition(it) }
        }
    }

    companion object {
        private const val TAG = "X3GeoLibre-Gecko"
        private const val HOME = "https://web.geolibre.app/"
    }
}
