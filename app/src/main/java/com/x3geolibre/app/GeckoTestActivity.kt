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
    // One live GeckoView (left eye), PixelCopy-mirrored to the right eye.
    private lateinit var session: GeckoSession
    private lateinit var geckoView: GeckoView
    private lateinit var binocular: GeckoBinocularLayout
    private var inputConnection: InputConnection? = null
    private var dictation: GroqDictation? = null
    private var geminiDictation: GeminiDictation? = null
    /** Full-duplex voice loop (reads GeoLibre's answers aloud + hands-free replies). */
    private var geminiLive: GeminiLiveSession? = null
    /** Set while we're opening the assistant so assistant-ready can start Live. */
    private var liveWanted = false
    /** Transcript awaiting the page's prompt-focus ack before being typed. */
    private var pendingQuery: String? = null
    private var keyReceiver: android.content.BroadcastReceiver? = null

    // ── x3geolibre bridge state ─────────────────────────────────────
    private var bridgePort: WebExtension.Port? = null
    @Volatile private var lastFix: IpLocator.Fix? = null
    @Volatile private var loadRequested = false
    @Volatile private var ipLocateStarted = false
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val networkPoll = Runnable { loadWhenOnline() }
    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "X3GeoLibre:Gecko").apply { setReferenceCounted(false) }
    }
    private val wifiLock: WifiManager.WifiLock? by lazy {
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.run {
            // WIFI_MODE_FULL, not HIGH_PERF: this app only fetches map tiles in
            // bursts and does one-shot dictation uploads — nothing that needs the
            // radio pinned out of power-save. HIGH_PERF held the PHY at full power
            // continuously, a real chunk of the glasses' heat; FULL keeps Wi-Fi
            // connected while letting it drop into power-save between fetches.
            @Suppress("DEPRECATION")
            createWifiLock(WifiManager.WIFI_MODE_FULL, "X3GeoLibre:Gecko")
                .apply { setReferenceCounted(false) }
        }
    }

    // HIGH_PERF lock held ONLY while a Live voice session is up: the long-lived
    // voice WebSocket dies with "Software caused connection abort" when the radio
    // drops into power-save between turns. Sessions are short and user-bounded,
    // so this doesn't reintroduce the always-on heat the FULL lock avoided.
    private val liveWifiLock: WifiManager.WifiLock? by lazy {
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.run {
            @Suppress("DEPRECATION")
            createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "X3GeoLibre:Live")
                .apply { setReferenceCounted(false) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersive()

        // CPU/GPU wakelock only. The foreground microphone service is NOT started
        // here anymore — running it continuously was a major heat source. Dictation
        // is short and user-initiated, so the service is spun up on demand only for
        // the seconds a recording is actually in flight (see startPlaybackService /
        // stopPlaybackService wired into the dictation onState callbacks).
        acquireLocks()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 3001)
        }

        val runtime = Gecko.runtime(this)
        setLowCpuVoicePrefs()
        session = newSession(runtime)
        geckoView = GeckoView(this)
        geckoView.setSession(session)

        binocular = GeckoBinocularLayout(this, geckoView)
        // Type via synthesized KeyEvents dispatched straight into the GeckoView —
        // the InputConnection commitText path silently drops text here because we
        // bypass the system IME, so Gecko never activates that connection. Key
        // events are what `adb shell input text` uses, verified working on-device.
        binocular.textInput = object : GeckoBinocularLayout.TextInput {
            override fun commit(text: String) { typeText(text) }
            override fun backspace() { sendKey(KeyEvent.KEYCODE_DEL) }
            override fun enter() { sendKey(KeyEvent.KEYCODE_ENTER) }
            override fun clear() {
                // Select-all + delete clears the focused field.
                sendKey(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
                sendKey(KeyEvent.KEYCODE_FORWARD_DEL)
            }
            override fun moveCaret(delta: Int) {
                sendKey(if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
            }
        }
        setContentView(binocular)

        // ── Groq dictation on the keyboard's Mic key ─────────────────────
        // Tap Mic to record, tap again to stop; the Whisper transcript is
        // typed into whatever Gemini field is focused. Key lives in Settings
        // (the ⚙ hotspot, top-right) or arrives over adb (SET_GROQ_KEY).
        val groq = GroqDictation(
            context = this,
            keyProvider = { GeoPrefs.groqKey(this) },
            onState = { rec -> runOnUiThread { binocular.setMicActive(rec); micService(rec) } },
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

        // ── Gemini voice STT for the AI Assistant ────────────────────────
        // Triple tap opens the assistant and starts listening; a single tap
        // stops recording → Gemini transcribes → the transcript is typed into
        // the assistant's prompt and submitted (Ctrl+Enter).
        geminiDictation = GeminiDictation(
            context = this,
            keyProvider = { GeoPrefs.geminiKey(this) },
            onState = { rec ->
                runOnUiThread {
                    binocular.setMicActive(rec)
                    micService(rec)
                    if (rec) binocular.showStatus("Listening… tap once to send")
                }
            },
            onResult = { text ->
                runOnUiThread {
                    pendingQuery = text
                    binocular.showStatus("Heard: ${text.take(40)}…")
                    // Ask the page to focus the assistant prompt; we type on ack.
                    runCatching { bridgePort?.postMessage(org.json.JSONObject().put("type", "focus-prompt")) }
                }
            },
            onError = { msg -> runOnUiThread { binocular.showStatus("Voice: $msg") } }
        )

        // Runtime receiver so the plain implicit adb broadcast also works
        // while the app is up (manifest SetKeyReceiver covers the cold case).
        keyReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val k = i.getStringExtra("key")?.trim().orEmpty()
                if (k.isBlank()) return
                if (i.action == GeoPrefs.ACTION_SET_GEMINI_KEY) {
                    GeoPrefs.setGeminiKey(this@GeckoTestActivity, k)
                    binocular.showStatus("Gemini key set via adb (${k.length} chars)")
                } else {
                    GeoPrefs.setGroqKey(this@GeckoTestActivity, k)
                    binocular.showStatus("Groq key set via adb (${k.length} chars)")
                }
            }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this, keyReceiver,
                android.content.IntentFilter(GeoPrefs.ACTION_SET_GEMINI_KEY),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this, keyReceiver,
                android.content.IntentFilter(GeoPrefs.ACTION_SET_GROQ_KEY),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        }

        // ── bridge extension: geolocation feed / right-click / What's-here ──
        runtime.webExtensionController
            .ensureBuiltIn("resource://android/assets/geolibre-ext/", "bridge@x3geolibre.app")
            .accept({ ext ->
                if (ext == null) { Log.w(TAG, "bridge ext null"); return@accept }
                runOnUiThread {
                    session.webExtensionController.setMessageDelegate(ext, portDelegate, "x3geolibre")
                    Log.i(TAG, "bridge extension installed")
                }
            }, { e -> Log.w(TAG, "bridge ext install failed: ${e?.message}") })

        // Double tap = context menu (right-click); triple tap = GeoAgent assistant;
        // single tap on the map = "What's here?".
        binocular.doubleTapHandler = { fx, fy ->
            val sent = runCatching {
                bridgePort?.postMessage(
                    org.json.JSONObject().put("type", "contextmenu")
                        .put("fx", fx.toDouble()).put("fy", fy.toDouble())
                ) != null
            }.getOrDefault(false)
            if (!sent) binocular.showStatus("Context menu unavailable (page still loading?)")
        }
        // Triple tap toggles the hands-free Live voice conversation: it opens the
        // AI Assistant, then reads its answers aloud and listens for your reply,
        // looping until you tap to end.
        binocular.tripleTapHandler = { _, _ -> toggleLive() }
        // During a Live conversation single taps stay NORMAL clicks — GeoLibre pops
        // a "Run assistant code?" dialog whose Run button must be clickable, and
        // eating the tap here killed the session mid-approval (the "it stopped
        // hearing me" bug). Ending Live is the double tap, which closes the AI
        // window (assistant-closed → stopLive). One-shot dictation keeps tap=stop.
        binocular.tapInterceptor = {
            val d = geminiDictation
            if (d != null && d.isRecording()) { d.toggle(); true } else false
        }

        // The glasses' Wi-Fi takes ~5s to come up after wake — gate the first
        // load on a validated network instead of showing a cached shell with a
        // permanently blank map.
        watchForNetwork()
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
            "media.peerconnection.video.enabled" to false,
            // Don't zoom-to-input on focus: the zoom re-layout blurs the field,
            // which closes our on-screen keyboard before the user can type.
            "formhelper.autozoom" to false,
            "apz.zoom-to-focused-input.enabled" to false
        )
        prefs.forEach { (k, v) ->
            runCatching {
                GeckoPreferenceController.setGeckoPref(k, v, B)
                    .accept({ Log.d(TAG, "pref $k=$v set") }, { e -> Log.w(TAG, "pref $k failed: ${e?.message}") })
            }
        }
    }

    // ── keyboard → Gecko via synthesized KeyEvents ───────────────────
    private val keyCharacterMap: android.view.KeyCharacterMap by lazy {
        android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
    }

    /** Type a string into the focused Gecko field as raw key events. */
    private fun typeText(text: String) {
        val events = keyCharacterMap.getEvents(text.toCharArray())
        if (events != null) {
            events.forEach { ev -> geckoView.dispatchKeyEvent(ev) }
            return
        }
        // Unmappable char(s) (emoji etc.) — best-effort via the InputConnection.
        runCatching {
            session.textInput.onCreateInputConnection(EditorInfo())?.commitText(text, 1)
        }
    }

    /** Press-and-release one key (optionally with meta, e.g. Ctrl+A). */
    private fun sendKey(keyCode: Int, meta: Int = 0) {
        val now = android.os.SystemClock.uptimeMillis()
        geckoView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        geckoView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }
    /** Build a fully-configured (but not-yet-loaded) session. */
    private fun newSession(runtime: org.mozilla.geckoview.GeckoRuntime): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
            .build()
        val s = GeckoSession(settings)
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(sess: GeckoSession, success: Boolean) { Log.d(TAG, "onPageStop success=$success") }
        }
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(sess: GeckoSession, title: String?) {
                if (title != null && title.startsWith("WRTC|")) Log.d("X3GeoLibre-WRTC", title.removePrefix("WRTC|"))
            }
        }
        s.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                sess: GeckoSession, perm: GeckoSession.PermissionDelegate.ContentPermission
            ): org.mozilla.geckoview.GeckoResult<Int> =
                org.mozilla.geckoview.GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            override fun onAndroidPermissionsRequest(
                sess: GeckoSession, permissions: Array<out String>?, callback: GeckoSession.PermissionDelegate.Callback
            ) { callback.grant() }
            override fun onMediaPermissionRequest(
                sess: GeckoSession, uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) { callback.grant(null, audio?.firstOrNull()) }
        }
        // On-screen keyboard driven by Gecko's text-input focus. A page-field blur
        // must not pull the keyboard out from under our own open Settings panel.
        s.textInput.setDelegate(object : GeckoSession.TextInputDelegate {
            override fun restartInput(sess: GeckoSession, reason: Int) {
                inputConnection = null
                if (reason == GeckoSession.TextInputDelegate.RESTART_REASON_BLUR) {
                    runOnUiThread { if (!binocular.isSettingsOpen()) binocular.hideKeyboard() }
                }
            }
            override fun showSoftInput(sess: GeckoSession) {
                // During a Live voice conversation all input is spoken; focusing the
                // prompt to type the transcript would otherwise flash the on-screen
                // keyboard for an instant. Suppress it while Live is active.
                if (geminiLive?.isActive() == true) return
                runOnUiThread { binocular.showKeyboard() }
            }
            override fun hideSoftInput(sess: GeckoSession) {
                runOnUiThread { if (!binocular.isSettingsOpen()) binocular.hideKeyboard() }
            }
        })
        s.open(runtime)
        return s
    }

    /** Content-script port: geolocation feed, right-click, What's-here, zoom. */
    private val portDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            bridgePort = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, p: WebExtension.Port) {
                    val obj = message as? org.json.JSONObject ?: return
                    when (obj.optString("type")) {
                        "ready" -> lastFix?.let { runOnUiThread { sendPosition(it) } }
                        "probe" -> Log.i(TAG, "X3PROBE $obj")
                        "want-position" -> {
                            val fix = lastFix
                            if (fix != null) runOnUiThread { sendPosition(fix) } else startIpLocate()
                        }
                        // GeoLibre's Radix menus only open on trusted input, so the
                        // content script asks us to land a real GeckoView tap.
                        "tap-native" -> {
                            val fx = obj.optDouble("fx", 0.5).toFloat()
                            val fy = obj.optDouble("fy", 0.5).toFloat()
                            runOnUiThread { binocular.tapGeckoFraction(fx, fy) }
                        }
                        "zoom" -> {
                            val dir = obj.optInt("dir", 1)
                            runOnUiThread { binocular.zoomGecko(dir) }
                        }
                        // Assistant setup: the key input is focused — type the key.
                        "type-gemini-key" -> runOnUiThread {
                            typeText(GeoPrefs.geminiKey(this@GeckoTestActivity))
                        }
                        // Assistant open + configured. If we were opening it to
                        // start a Live conversation, kick that off now.
                        "assistant-ready" -> runOnUiThread {
                            binocular.hideKeyboard()
                            if (liveWanted) { liveWanted = false; actuallyStartLive() }
                        }
                        // Double tap closed the AI window — end the voice loop too.
                        "assistant-closed" -> runOnUiThread {
                            if (geminiLive?.isActive() == true) stopLive()
                        }
                        // [X3UI] voice commands that are keystrokes, not menus:
                        // undo/redo hit GeoLibre's store history; escape dismisses
                        // whatever dialog/menu is up. Sent natively = trusted.
                        "ui-key" -> runOnUiThread {
                            when (obj.optString("key")) {
                                "undo" -> sendKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
                                "redo" -> sendKey(
                                    KeyEvent.KEYCODE_Z,
                                    KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
                                )
                                "escape" -> sendKey(KeyEvent.KEYCODE_ESCAPE)
                            }
                        }
                        // Live voice: GeoLibre finished an answer — read it aloud.
                        "assistant-response" -> runOnUiThread {
                            val text = obj.optString("text")
                            Log.i(TAG, "X3ANSWER len=${text.length}: ${text.take(300)}")
                            if (text.isNotBlank()) geminiLive?.speak(text)
                        }
                        // Prompt focused → type the transcript and submit (Ctrl+Enter).
                        "prompt-focused" -> runOnUiThread {
                            val q = pendingQuery ?: return@runOnUiThread
                            pendingQuery = null
                            typeText(q)
                            binocular.postDelayed({
                                sendKey(KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON)
                                binocular.hideKeyboard()
                                binocular.showStatus("Query sent to AI Assistant")
                            }, 250)
                        }
                        "assistant-status" -> runOnUiThread {
                            binocular.showStatus(obj.optString("text", ""))
                        }
                    }
                }
                override fun onDisconnect(p: WebExtension.Port) {
                    if (bridgePort == p) bridgePort = null
                }
            })
            lastFix?.let { sendPosition(it) }
            // Push the Gemini key so the page can seed GeoLibre's AI Assistant
            // provider config (localStorage desktopSettings.aiProviderEnv).
            runCatching {
                port.postMessage(org.json.JSONObject().put("type", "gemini-key").put("key", GeoPrefs.geminiKey(this@GeckoTestActivity)))
            }
        }
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
        runCatching { if (liveWifiLock?.isHeld == true) liveWifiLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
    }

    /**
     * On-demand foreground microphone service: started only while a dictation is
     * actively recording (a few seconds), stopped the instant it ends. Keeping it
     * off the rest of the time is one of the heat fixes — it used to run for the
     * whole session. It still guards the mic against being throttled/revoked if
     * the display sleeps mid-recording.
     */
    private fun micService(on: Boolean) {
        val intent = Intent(this, GeoPlaybackService::class.java)
        runCatching {
            if (on) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            } else {
                stopService(intent)
            }
        }.onFailure { Log.w(TAG, "mic service ${if (on) "start" else "stop"} failed: ${it.message}") }
    }

    // ── Live voice conversation ─────────────────────────────────────────────
    /** Triple tap: start the Live loop, or end it if already running. */
    private fun toggleLive() {
        if (geminiLive?.isActive() == true) { stopLive(); return }
        liveWanted = true
        binocular.showStatus("Starting live voice…")
        val sent = runCatching {
            bridgePort?.postMessage(org.json.JSONObject().put("type", "ensure-assistant")) != null
        }.getOrDefault(false)
        if (!sent) {
            liveWanted = false
            binocular.showStatus("Assistant unavailable (page still loading?)")
        }
    }

    /** Assistant panel is open — open the Live session and begin listening. */
    private fun actuallyStartLive() {
        geminiLive?.stop()
        runCatching { liveWifiLock?.let { if (!it.isHeld) it.acquire() } }
        val live = GeminiLiveSession(
            context = this,
            keyProvider = { GeoPrefs.geminiKey(this) },
            onReady = {
                runOnUiThread {
                    micService(true)
                    binocular.showStatus("● Live — speak (double-tap to end)")
                    runCatching { bridgePort?.postMessage(org.json.JSONObject().put("type", "live-on")) }
                }
            },
            onVoiceState = { st -> runOnUiThread { binocular.setVoiceState(st) } },
            onUserTranscript = { text ->
                runOnUiThread {
                    pendingQuery = text
                    binocular.showStatus("Heard: ${text.take(40)}")
                    // Type + submit into GeoLibre via the existing focus→type→send path.
                    runCatching { bridgePort?.postMessage(org.json.JSONObject().put("type", "focus-prompt")) }
                }
            },
            onStatus = { text -> runOnUiThread { binocular.showStatus(text) } },
            onClosed = { reason ->
                runOnUiThread {
                    micService(false)
                    runCatching { liveWifiLock?.let { if (it.isHeld) it.release() } }
                    binocular.setVoiceState(GeckoBinocularLayout.VoiceState.OFF)
                    runCatching { bridgePort?.postMessage(org.json.JSONObject().put("type", "live-off")) }
                    binocular.showStatus(if (reason != null) "Live ended: $reason" else "Live ended")
                    geminiLive = null
                }
            }
        )
        geminiLive = live
        live.start()
    }

    private fun stopLive() {
        liveWanted = false
        geminiLive?.stop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { enableImmersive(); acquireLocks() }
    }

    override fun onDestroy() {
        runCatching { geminiLive?.stop() }
        runCatching { dictation?.cancel() }
        runCatching { keyReceiver?.let { unregisterReceiver(it) } }
        keyReceiver = null
        ui.removeCallbacks(networkPoll)
        networkCallback?.let { callback ->
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        releaseLocks()
        micService(false)
        runCatching { session.close() }
        super.onDestroy()
    }

    // ────────────────────────────────────────────────────────────────
    //  x3geolibre bridge: Wi-Fi gate, IP position, native port
    // ────────────────────────────────────────────────────────────────

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Wait for real Internet. An offline cached shell reports a successful page
     * load even though its MapLibre style failed, so it must never be the boot UI. */
    private fun loadWhenOnline() {
        if (loadRequested) return
        if (isOnline()) {
            loadRequested = true
            ui.removeCallbacks(networkPoll)
            binocular.showStatus("Loading GeoLibre…  1 tap select · 2 context · 3 tools")
            session.loadUri(HOME)
            startIpLocate()
            return
        }
        binocular.showStatus("Waiting for Wi-Fi… map will open automatically")
        ui.removeCallbacks(networkPoll)
        ui.postDelayed(networkPoll, 750L)
    }

    /** Wake the gate as soon as Android validates the reconnected Wi-Fi. */
    private fun watchForNetwork() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                ui.post { loadWhenOnline() }
            }

            override fun onCapabilitiesChanged(
                network: android.net.Network,
                capabilities: android.net.NetworkCapabilities
            ) {
                if (capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    ui.post { loadWhenOnline() }
                }
            }

            override fun onLost(network: android.net.Network) {
                if (!loadRequested) ui.post { binocular.showStatus("Waiting for Wi-Fi…") }
            }
        }
        networkCallback = callback
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
        }.onFailure {
            networkCallback = null
            Log.w(TAG, "network callback failed: ${it.message}")
        }
    }

    private fun startIpLocate() {
        if (ipLocateStarted) return
        ipLocateStarted = true
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
                ipLocateStarted = false
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

    companion object {
        private const val TAG = "X3GeoLibre-Gecko"
        private const val HOME = "https://web.geolibre.app/"
    }
}
