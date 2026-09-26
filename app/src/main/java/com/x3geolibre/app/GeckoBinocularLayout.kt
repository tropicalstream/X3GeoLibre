package com.x3geolibre.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs
import org.mozilla.geckoview.GeckoView

/**
 * Binocular compositor for a bundled GeckoView engine.
 *
 * Physical 1280x480: two live GeckoViews render the same page — [geckoView] fills
 * the LEFT eye, [rightView] the RIGHT — so neither eye lags the other. The shared
 * cursor's taps/drags dispatch to both views; the arrow cursor + ⚙ chrome are
 * drawn live over both eyes by [CursorView], which also blits the keyboard/settings
 * panels (real Views on the left eye) into the right eye.
 *
 * Right pad (cyttsp5) drives the cursor + tap-to-click; left pad (cyttsp6) is
 * volume; the temple click arrives as KEYCODE_BUTTON_A/DPAD_CENTER. Keyboard keys
 * go to [textInput].
 */
class GeckoBinocularLayout(
    context: Context,
    private val geckoView: GeckoView
) : FrameLayout(context), CustomKeyboardView.OnKeyboardActionListener {

    /** The activity implements this to type into the focused GeckoView field. */
    interface TextInput {
        fun commit(text: String)
        fun backspace()
        fun enter()
        fun clear()
        fun moveCaret(delta: Int)
    }

    var textInput: TextInput? = null

    /** Host-provided handler for the keyboard's Mic key (Groq dictation). */
    var micHandler: (() -> Unit)? = null

    /** Double tap → right-click at the cursor. Fractions are viewport-relative. */
    var doubleTapHandler: ((fx: Float, fy: Float) -> Unit)? = null

    /** Triple tap → toggle the temporary glasses command palette. */
    var tripleTapHandler: ((fx: Float, fy: Float) -> Unit)? = null

    /** Runs before a single tap resolves; return true to consume it (e.g. the
     *  host stops an active dictation instead of clicking the page). */
    var tapInterceptor: (() -> Boolean)? = null

    /** Host persists the Groq key when the settings panel saves. */
    var onSaveGroqKey: ((String) -> Unit)? = null

    /** Host supplies the current (masked-for-display) key on panel open. */
    var currentGroqKey: (() -> String?)? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val cursorView = CursorView(context)
    private lateinit var mirror: TextureView
    private val keyboardContainer: FrameLayout
    private var keyboardView: CustomKeyboardView? = null

    private var eyeW = 640
    private var eyeH = 480
    @Volatile private var cursorX = 320f
    @Volatile private var cursorY = 240f

    // Right eye = PixelCopy mirror of the single live GeckoView (its SurfaceView
    // content can't be dual-drawn on a Canvas, so we copy it). The cursor + chrome
    // are NOT mirrored — CursorView draws them live in both eyes so the pointer
    // never lags. Readback runs on its own thread (guide gotcha #7).
    private var mirrorBitmap: Bitmap? = null
    private var geckoSurface: SurfaceView? = null
    private val mirrorThread = HandlerThread("x3geo-mirror").apply { start() }
    private val mirrorHandler = Handler(mirrorThread.looper)
    @Volatile private var inFlight = false
    @Volatile private var keyboardBitmap: Bitmap? = null
    @Volatile private var keyboardTop = 0
    @Volatile private var settingsBitmapVar: Bitmap? = null
    @Volatile private var settingsTopVar = 0
    private var mirrorTick = 0

    private enum class Side { NONE, LEFT_VOLUME, RIGHT_CURSOR }
    private var activeSide = Side.NONE
    private var lastInputX = 0f
    private var lastInputY = 0f
    private var downInputX = 0f
    private var downInputY = 0f
    // TapInsight-style tracking: drop the first move of every fresh finger-down so
    // the delta that spans the lift gap can't teleport the cursor.
    private var dropNextDelta = false
    // Capacitive liftoff-jump correction: the finger's last sample on lift is
    // slightly off, kicking the cursor. If the gesture ends right after a small
    // move, undo that final micro-move so the cursor stays where the user aimed.
    private var lastMoveTimeMs = 0L
    private var preMoveCursorX = 0f
    private var preMoveCursorY = 0f
    private var lastMoveDist = 0f
    private var leftVolumeStartY = 0f
    private var leftVolumeStart = 0
    @Volatile private var lastInteractionMs = 0L
    private enum class TapSource { KEY, TOUCH }
    private var tapCount = 0
    private var lastTapAtMs = 0L
    private var lastRawTapAtMs = 0L
    private var lastTapSource: TapSource? = null
    private var pendingTapEventTime = 0L
    private val finishTapSequence = Runnable {
        val count = tapCount
        tapCount = 0
        when (count) {
            1 -> performCursorClick(pendingTapEventTime)
            2 -> doubleTapHandler?.invoke(
                cursorX / eyeW.coerceAtLeast(1),
                cursorY / eyeH.coerceAtLeast(1)
            )
        }
    }

    // Edge-pan: the map only pans when the cursor is pinned against the very edge
    // AND the finger is still pushing past it — i.e. a deliberate "pull". It does
    // NOT free-run from a band (that yanked the map whenever the cursor merely
    // passed near an edge on the way to a side menu). MapLibre pans on a real
    // one-finger drag, so we synthesise a DOWN→MOVE…→UP touch stream on GeckoView.
    private var panActive = false
    private var panDownTime = 0L
    private var panDragX = 0f
    private var panDragY = 0f

    init {
        clipChildren = false
        addView(geckoView, LayoutParams(eyeW, eyeH).apply { leftMargin = 0 })

        mirror = TextureView(context).apply {
            isOpaque = true
            layoutParams = LayoutParams(eyeW, eyeH).apply { leftMargin = eyeW }
        }
        mirror.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        addView(mirror)

        keyboardContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(eyeW, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { leftMargin = 0 }
            visibility = View.GONE
        }
        addView(keyboardContainer)
        // Overlay spans BOTH eyes: cursor + ⚙ chrome are drawn live in each so the
        // pointer never lags the mirror.
        addView(cursorView, LayoutParams(eyeW * 2, eyeH).apply { leftMargin = 0 })
        mirrorBitmap = Bitmap.createBitmap(eyeW, eyeH, Bitmap.Config.ARGB_8888)

        post {
            if (width > 0 && height > 0) {
                eyeW = width / 2
                eyeH = height
                geckoView.layoutParams = LayoutParams(eyeW, eyeH).apply { leftMargin = 0 }
                mirror.layoutParams = LayoutParams(eyeW, eyeH).apply { leftMargin = eyeW }
                keyboardContainer.layoutParams = LayoutParams(eyeW, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { leftMargin = 0 }
                cursorView.layoutParams = LayoutParams(eyeW * 2, eyeH).apply { leftMargin = 0 }
                cursorX = eyeW * 0.5f
                cursorY = eyeH * 0.5f
                mirrorBitmap = Bitmap.createBitmap(eyeW, eyeH, Bitmap.Config.ARGB_8888)
                requestLayout()
                Log.d(TAG, "eye = ${eyeW}x$eyeH")
            }
            startMirrorLoop()
        }
    }

    private fun findGeckoSurface(): SurfaceView? {
        geckoSurface?.let { return it }
        fun search(v: View): SurfaceView? {
            if (v is SurfaceView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) search(v.getChildAt(i))?.let { return it }
            return null
        }
        geckoSurface = search(geckoView)
        return geckoSurface
    }

    // Fast during/just-after interaction (right eye tracks the map within ~1 frame),
    // throttled to a trickle when the map is idle (identical frames — saves heat).
    private fun startMirrorLoop() {
        mirrorHandler.post(object : Runnable {
            override fun run() {
                val audioActive = runCatching {
                    audioManager.isMusicActive || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
                }.getOrDefault(false)
                if (!audioActive) {
                    val active = SystemClock.uptimeMillis() - lastInteractionMs < MIRROR_ACTIVE_WINDOW_MS
                    mirrorTick++
                    if (active || mirrorTick % MIRROR_IDLE_SKIP == 0) mirrorFrame()
                    mirrorHandler.postDelayed(this, if (active) MIRROR_ACTIVE_MS else MIRROR_IDLE_MS)
                } else {
                    mirrorHandler.postDelayed(this, AUDIO_POLL_MS)
                }
            }
        })
    }

    /** Runs on the mirror thread. */
    private fun mirrorFrame() {
        if (inFlight) return
        val bmp = mirrorBitmap ?: return
        if (!mirror.isAvailable) return
        val sv = findGeckoSurface() ?: return
        if (sv.holder.surface?.isValid != true) return
        inFlight = true
        val ok = runCatching {
            PixelCopy.request(sv, bmp, { result ->
                if (result == PixelCopy.SUCCESS && mirror.isAvailable) {
                    val canvas = runCatching { mirror.lockCanvas() }.getOrNull()
                    if (canvas != null) {
                        canvas.drawBitmap(bmp, 0f, 0f, null)
                        settingsBitmapVar?.let { canvas.drawBitmap(it, 40f, settingsTopVar.toFloat(), null) }
                        keyboardBitmap?.let { canvas.drawBitmap(it, 0f, keyboardTop.toFloat(), null) }
                        // cursor + chrome are drawn live by CursorView, not blitted here.
                        runCatching { mirror.unlockCanvasAndPost(canvas) }
                    }
                }
                inFlight = false
            }, mirrorHandler)
        }.isSuccess
        if (!ok) inFlight = false
    }

    // ------------------------------------------------------------------
    //  On-screen keyboard
    // ------------------------------------------------------------------

    private val keyboardSnapshotter = object : Runnable {
        override fun run() {
            snapshotKeyboard()
            if (isKeyboardVisible()) postDelayed(this, 120L)
        }
    }

    fun showKeyboard() {
        if (keyboardView == null) {
            keyboardView = CustomKeyboardView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
                )
                setOnKeyboardActionListener(this@GeckoBinocularLayout)
            }
            keyboardContainer.addView(keyboardView)
        }
        keyboardView?.visibility = View.VISIBLE
        keyboardContainer.visibility = View.VISIBLE
        keyboardContainer.bringToFront()
        cursorView.bringToFront()
        // Snapshot for the right-eye mirror (can't draw a live View off the UI thread).
        removeCallbacks(keyboardSnapshotter)
        post(keyboardSnapshotter)
    }

    fun hideKeyboard() {
        keyboardContainer.visibility = View.GONE
        removeCallbacks(keyboardSnapshotter)
        keyboardBitmap = null
    }

    fun isKeyboardVisible() = keyboardContainer.visibility == View.VISIBLE

    /** UI thread: rasterize the keyboard for the mirror thread to blit. */
    private fun snapshotKeyboard() {
        val kb = keyboardContainer
        if (kb.visibility != View.VISIBLE || kb.width <= 0 || kb.height <= 0) { keyboardBitmap = null; return }
        val b = runCatching {
            Bitmap.createBitmap(kb.width, kb.height, Bitmap.Config.ARGB_8888).also { kb.draw(Canvas(it)) }
        }.getOrNull()
        keyboardTop = kb.top
        keyboardBitmap = b
    }

    override fun onKeyPressed(key: String) {
        if (settingsOpen) { settingsBuffer.append(key); refreshSettingsPanel(); return }
        textInput?.commit(key)
    }
    override fun onBackspacePressed() {
        if (settingsOpen) {
            if (settingsBuffer.isNotEmpty()) settingsBuffer.deleteCharAt(settingsBuffer.length - 1)
            refreshSettingsPanel(); return
        }
        textInput?.backspace()
    }
    override fun onEnterPressed() {
        if (settingsOpen) { saveSettingsAndClose(); return }
        textInput?.enter()
    }
    override fun onHideKeyboard() {
        if (settingsOpen) { closeSettings(); return }
        hideKeyboard()
    }
    override fun onClearPressed() {
        if (settingsOpen) { settingsBuffer.setLength(0); refreshSettingsPanel(); return }
        textInput?.clear()
    }
    override fun onMoveCursorLeft() { if (!settingsOpen) textInput?.moveCaret(-1) }
    override fun onMoveCursorRight() { if (!settingsOpen) textInput?.moveCaret(1) }
    override fun onMicrophonePressed() {
        if (settingsOpen) return
        micHandler?.invoke()
    }

    /** Mirror the dictation recording state onto the keyboard's Mic key. */
    fun setMicActive(active: Boolean) {
        keyboardView?.setMicActive(active)
    }

    /**
     * Live-voice avatar states, drawn top-centre of BOTH eyes while a voice
     * session is active — so the user always knows whether they're being heard.
     */
    enum class VoiceState { OFF, CONNECTING, LISTENING, HEARING, THINKING, SPEAKING }

    fun setVoiceState(state: VoiceState) {
        if (cursorView.voiceState == state) return
        cursorView.voiceState = state
        cursorView.invalidate()
    }

    /** Transient one-line status (dictation errors etc.), drawn on BOTH eyes. */
    fun showStatus(msg: String) {
        cursorView.statusText = msg
        cursorView.statusUntilMs = SystemClock.uptimeMillis() + 3500L
        cursorView.invalidate()
    }

    // ------------------------------------------------------------------
    //  Settings panel (Groq key) — opened via the ⚙ hotspot, typed with the
    //  on-screen keyboard, mirrored to the right eye like the keyboard is.
    // ------------------------------------------------------------------

    private var settingsOpen = false
    private val settingsBuffer = StringBuilder()
    private var settingsContainer: FrameLayout? = null
    private var settingsText: android.widget.TextView? = null

    private val settingsSnapshotter = object : Runnable {
        override fun run() {
            snapshotSettings()
            if (settingsOpen) postDelayed(this, 160L)
        }
    }

    fun isSettingsOpen() = settingsOpen

    fun openSettings() {
        if (settingsOpen) return
        settingsOpen = true
        settingsBuffer.setLength(0)
        currentGroqKey?.invoke()?.let { settingsBuffer.append(it) }
        if (settingsContainer == null) {
            val tv = android.widget.TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(18, 14, 18, 14)
                setLineSpacing(0f, 1.25f)
            }
            settingsText = tv
            settingsContainer = FrameLayout(context).apply {
                setBackgroundColor(0xF2101820.toInt())
                layoutParams = LayoutParams(eyeW - 80, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT)
                    .apply { leftMargin = 40; topMargin = 24 }
                addView(tv)
                visibility = View.GONE
            }
            addView(settingsContainer)
        }
        settingsContainer?.visibility = View.VISIBLE
        settingsContainer?.bringToFront()
        cursorView.bringToFront()
        refreshSettingsPanel()
        showKeyboard()
        removeCallbacks(settingsSnapshotter)
        post(settingsSnapshotter)
    }

    fun closeSettings() {
        settingsOpen = false
        settingsContainer?.visibility = View.GONE
        settingsBitmapVar = null
        removeCallbacks(settingsSnapshotter)
        hideKeyboard()
    }

    private fun saveSettingsAndClose() {
        val key = settingsBuffer.toString().trim()
        onSaveGroqKey?.invoke(key)
        closeSettings()
        showStatus(if (key.isBlank()) "Groq key cleared" else "Groq key saved (${key.length} chars)")
    }

    private fun refreshSettingsPanel() {
        val masked = settingsBuffer.toString().let {
            when {
                it.isEmpty() -> "(empty)"
                it.length <= 10 -> it
                else -> it.take(6) + "…" + it.takeLast(4) + "   [${it.length}]"
            }
        }
        settingsText?.text =
            "TAPGEMINI SETTINGS — Groq API key (for the keyboard Mic key)\n" +
            "Key: $masked\n" +
            "Type/paste with the keyboard · Enter = save · Clear = wipe · Hide = cancel\n" +
            "Or over adb: am broadcast -n com.x3geolibre.app/.SetKeyReceiver " +
            "-a com.x3geolibre.app.SET_GROQ_KEY --es key gsk_..."
        snapshotSettings()
    }

    /** UI thread: rasterize the settings panel for the mirror thread to blit. */
    private fun snapshotSettings() {
        val sc = settingsContainer ?: return
        if (!settingsOpen || sc.visibility != View.VISIBLE || sc.width <= 0 || sc.height <= 0) {
            settingsBitmapVar = null; return
        }
        settingsBitmapVar = runCatching {
            Bitmap.createBitmap(sc.width, sc.height, Bitmap.Config.ARGB_8888).also { sc.draw(Canvas(it)) }
        }.getOrNull()
        settingsTopVar = sc.top
    }

    /** Gear hotspot (top-right of the eye). */
    private fun gearHit(x: Float, y: Float): Boolean =
        x >= eyeW - 40f && y <= 40f

    // ------------------------------------------------------------------
    //  Input → cursor / gecko
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val kc = event.keyCode
        if (kc == KeyEvent.KEYCODE_BUTTON_A || kc == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (event.action == KeyEvent.ACTION_UP) {
                cursorView.visibility = View.VISIBLE
                registerTap(event.eventTime, TapSource.KEY)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val logicalWidth = eyeW.coerceAtLeast(1)
        val rawX = ev.getX(0)
        val localX = if (rawX >= logicalWidth) rawX - logicalWidth else rawX
        val rawY = ev.getY(0)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_HOVER_ENTER -> {
                activeSide = classifySide(ev, logicalWidth)
                lastInputX = localX; lastInputY = rawY
                downInputX = localX; downInputY = rawY
                dropNextDelta = true
                lastInteractionMs = SystemClock.uptimeMillis()
                if (activeSide == Side.LEFT_VOLUME) {
                    leftVolumeStartY = rawY
                    leftVolumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    return true
                }
                cursorView.visibility = View.VISIBLE
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                if (activeSide == Side.LEFT_VOLUME) { adjustVolume(rawY); return true }
                if (activeSide == Side.NONE) activeSide = classifySide(ev, logicalWidth)
                var dx = localX - lastInputX
                var dy = rawY - lastInputY
                lastInputX = localX; lastInputY = rawY
                lastInteractionMs = SystemClock.uptimeMillis()
                // TapInsight re-anchor: the first delta of a fresh finger-down can
                // span the lift gap and teleport the cursor, so drop just that one.
                if (dropNextDelta) { dropNextDelta = false; return true }
                if (abs(dx) < 0.35f && abs(dy) < 0.35f) return true
                // Safety net against a stray single-frame jump.
                dx = dx.coerceIn(-MAX_CURSOR_STEP_PX, MAX_CURSOR_STEP_PX)
                dy = dy.coerceIn(-MAX_CURSOR_STEP_PX, MAX_CURSOR_STEP_PX)
                preMoveCursorX = cursorX; preMoveCursorY = cursorY
                moveCursor(dx, dy)
                lastMoveDist = abs(cursorX - preMoveCursorX) + abs(cursorY - preMoveCursorY)
                lastMoveTimeMs = ev.eventTime
                if (isKeyboardVisible()) stopEdgePan() else edgePanStep(dx, dy)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_HOVER_EXIT -> {
                if (activeSide == Side.LEFT_VOLUME) { activeSide = Side.NONE; return true }
                lastInteractionMs = SystemClock.uptimeMillis()
                // Undo the liftoff micro-jump so the cursor stays where it was aimed.
                if (lastMoveTimeMs != 0L &&
                    ev.eventTime - lastMoveTimeMs <= LIFT_JUMP_WINDOW_MS &&
                    lastMoveDist <= LIFT_JUMP_MAX_PX
                ) { cursorX = preMoveCursorX; cursorY = preMoveCursorY; cursorView.cursorX = cursorX; cursorView.cursorY = cursorY; cursorView.invalidate() }
                lastMoveTimeMs = 0L
                val moved = abs(localX - downInputX) > touchSlop || abs(rawY - downInputY) > touchSlop
                val ended = ev.actionMasked != MotionEvent.ACTION_CANCEL && ev.actionMasked != MotionEvent.ACTION_HOVER_EXIT
                if (!moved && ended) registerTap(ev.eventTime, TapSource.TOUCH)
                stopEdgePan()
                activeSide = Side.NONE
                return true
            }
        }
        return true
    }

    private fun classifySide(event: MotionEvent, logicalWidth: Int): Side {
        val name = runCatching { event.device?.name ?: InputDevice.getDevice(event.deviceId)?.name }
            .getOrNull().orEmpty()
        return when {
            name.contains("cyttsp6", true) -> Side.LEFT_VOLUME
            name.contains("cyttsp5", true) -> Side.RIGHT_CURSOR
            event.isFromSource(InputDevice.SOURCE_MOUSE) ||
                event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) -> Side.RIGHT_CURSOR
            else -> if (event.getX(0) < logicalWidth) Side.LEFT_VOLUME else Side.RIGHT_CURSOR
        }
    }

    /**
     * Resolve the only reliable X3 right-arm gestures without using a hold.
     * Some firmware emits both a touch-up and BUTTON_A for one physical tap;
     * only cross-source events inside the duplicate window are collapsed, so
     * genuinely fast double/triple taps from one source still count.
     */
    private fun registerTap(eventTime: Long, source: TapSource) {
        val now = SystemClock.uptimeMillis()
        lastInteractionMs = now
        val sinceRaw = now - lastRawTapAtMs
        val duplicate = lastTapSource != null && lastTapSource != source &&
            sinceRaw <= CROSS_SOURCE_DUPLICATE_MS
        lastRawTapAtMs = now
        lastTapSource = source
        if (duplicate) return

        if (now - lastTapAtMs > MULTI_TAP_WINDOW_MS) tapCount = 0
        tapCount += 1
        lastTapAtMs = now
        pendingTapEventTime = eventTime
        removeCallbacks(finishTapSequence)
        if (tapCount >= 3) {
            tapCount = 0
            tripleTapHandler?.invoke(
                cursorX / eyeW.coerceAtLeast(1),
                cursorY / eyeH.coerceAtLeast(1)
            )
        } else {
            postDelayed(finishTapSequence, MULTI_TAP_WINDOW_MS)
        }
    }

    private fun adjustVolume(rawY: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val steps = ((leftVolumeStartY - rawY) / 34f).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (leftVolumeStart + steps).coerceIn(0, max), 0)
    }

    private fun moveCursor(dx: Float, dy: Float) {
        cursorX = (cursorX + dx * CURSOR_GAIN).coerceIn(1f, eyeW - 1f)
        cursorY = (cursorY + dy * CURSOR_GAIN).coerceIn(1f, eyeH - 1f)
        cursorView.cursorX = cursorX
        cursorView.cursorY = cursorY
        cursorView.invalidate()
    }

    /**
     * Called on every cursor MOVE with the raw finger delta. Pans the map only
     * when the cursor is pinned to the very edge (fingerDx/fingerDy is the wasted
     * push past the coerced boundary) AND the finger is actively moving past that
     * edge — so it never triggers just by moving the cursor near a side panel, and
     * it stops the instant the finger stops pushing.
     */
    private fun edgePanStep(fingerDx: Float, fingerDy: Float) {
        if (gearHit(cursorX, cursorY)) { stopEdgePan(); return }
        var dirX = 0f
        if (cursorX <= EDGE_PIN_PX && fingerDx < -EDGE_PUSH_MIN) dirX = -1f              // camera west
        else if (cursorX >= eyeW - EDGE_PIN_PX && fingerDx > EDGE_PUSH_MIN) dirX = 1f    // camera east
        var dirY = 0f
        if (cursorY <= EDGE_PIN_PX && fingerDy < -EDGE_PUSH_MIN) dirY = -1f              // camera north
        else if (cursorY >= eyeH - EDGE_PIN_PX && fingerDy > EDGE_PUSH_MIN) dirY = 1f    // camera south
        if (dirX == 0f && dirY == 0f) { stopEdgePan(); return }

        val now = SystemClock.uptimeMillis()
        if (!panActive) {
            panActive = true
            panDownTime = now
            panDragX = eyeW / 2f; panDragY = eyeH / 2f
            dragGecko(MotionEvent.ACTION_DOWN, panDragX, panDragY, now)
        }
        // Drag the map opposite the camera direction, proportional to the push.
        var nx = panDragX - dirX * abs(fingerDx) * EDGE_PAN_GAIN
        var ny = panDragY - dirY * abs(fingerDy) * EDGE_PAN_GAIN
        val m = EDGE_PAN_MARGIN_PX
        if (nx < m || nx > eyeW - m || ny < m || ny > eyeH - m) {
            dragGecko(MotionEvent.ACTION_UP, panDragX, panDragY, now)
            panDownTime = now
            panDragX = eyeW / 2f; panDragY = eyeH / 2f
            dragGecko(MotionEvent.ACTION_DOWN, panDragX, panDragY, now)
            nx = panDragX - dirX * abs(fingerDx) * EDGE_PAN_GAIN
            ny = panDragY - dirY * abs(fingerDy) * EDGE_PAN_GAIN
        }
        dragGecko(MotionEvent.ACTION_MOVE, nx, ny, now)
        panDragX = nx; panDragY = ny
        lastInteractionMs = now
    }

    private fun stopEdgePan() {
        if (panActive) {
            panActive = false
            dragGecko(MotionEvent.ACTION_UP, panDragX, panDragY, SystemClock.uptimeMillis())
        }
    }

    /** One touch event of the synthetic map-drag, sent to BOTH eyes' GeckoViews. */
    private fun dragGecko(action: Int, x: Float, y: Float, eventTime: Long) {
        val e = MotionEvent.obtain(panDownTime, eventTime, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        geckoView.dispatchTouchEvent(e); e.recycle()
    }

    private fun performCursorClick(eventTime: Long) {
        // Host-level intercept (e.g. a tap stops an active voice dictation).
        if (tapInterceptor?.invoke() == true) return
        // ⚙ hotspot toggles the settings panel (checked before everything so
        // it stays reachable even with the keyboard up).
        if (gearHit(cursorX, cursorY)) {
            if (settingsOpen) closeSettings() else openSettings()
            return
        }
        if (settingsOpen) {
            // While settings are open, clicks only land on the keyboard; a
            // click on the page area is swallowed (the panel owns the screen).
            val kb = keyboardView
            val top = keyboardContainer.top.toFloat()
            val bottom = keyboardContainer.bottom.toFloat()
            if (kb != null && isKeyboardVisible() && cursorY in top..bottom) {
                kb.handleAnchoredTap(cursorX, cursorY - top)
            }
            return
        }
        if (isKeyboardVisible()) {
            val kb = keyboardView
            val top = keyboardContainer.top.toFloat()
            val bottom = keyboardContainer.bottom.toFloat()
            if (kb != null && cursorY in top..bottom) {
                kb.handleAnchoredTap(cursorX, cursorY - top)
                return
            }
            hideKeyboard()  // tapped the page above the keyboard
        }
        // Single tap = plain left click at the cursor, nothing more.
        clickGecko(eventTime)
    }

    /**
     * Zoom the map in ONE eye with a trusted mouse-wheel event at the eye centre
     * (dir > 0 = in). Each eye's content script drives its own view, so the bridge
     * passes the originating [view] to keep the two eyes zooming in lock-step.
     */
    fun zoomGecko(dir: Int) {
        val view = geckoView
        lastInteractionMs = SystemClock.uptimeMillis()
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            x = eyeW / 2f; y = eyeH / 2f
            // One wheel "line" ≈ 0.1 zoom in MapLibre, so scale up for a ~0.7
            // step that feels like a real zoom button.
            setAxisValue(MotionEvent.AXIS_VSCROLL, dir * 7f)
        }
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        view.dispatchGenericMotionEvent(ev); ev.recycle()
    }

    /**
     * Land a trusted single tap on ONE eye's GeckoView at a viewport fraction
     * (0..1), used by the bridge to drive GeoLibre's Radix menus (which ignore
     * synthetic DOM clicks). Fractions keep it independent of viewport scale; the
     * originating [view] keeps each eye's menu driven by its own content script.
     */
    fun tapGeckoFraction(fx: Float, fy: Float) {
        val view = geckoView
        val x = (fx * eyeW).coerceIn(1f, eyeW - 1f)
        val y = (fy * eyeH).coerceIn(1f, eyeH - 1f)
        val t = SystemClock.uptimeMillis()
        lastInteractionMs = t
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        view.dispatchTouchEvent(down); down.recycle()
        postDelayed({
            val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            view.dispatchTouchEvent(up); up.recycle()
        }, 40L)
    }

    // The shared cursor taps BOTH eyes so their maps react identically.
    private fun clickGecko(eventTime: Long) {
        lastInteractionMs = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
        geckoView.dispatchTouchEvent(down); down.recycle()
        postDelayed({
            val up = MotionEvent.obtain(eventTime, eventTime + 48L, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
            geckoView.dispatchTouchEvent(up); up.recycle()
        }, 48L)
    }

    private class CursorView(context: Context) : View(context) {
        var cursorX = 320f
        var cursorY = 240f
        /** Transient status line ("Groq key saved", dictation errors). */
        var statusText: String? = null
        var statusUntilMs = 0L
        /** Live-voice avatar state (drawn only while a session is active). */
        var voiceState = VoiceState.OFF
        // TapInsight-style arrow pointer: white fill under a black outline so it
        // reads over any basemap. Tip (hotspot) sits at the cursor coordinate.
        private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
        private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.BLACK
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        private val arrowPath = Path()
        private val chromeFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val chromeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 15f; textAlign = Paint.Align.CENTER
        }
        private val voiceStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND
        }
        private val voiceFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val voiceArc = RectF()
        init { isClickable = false; isFocusable = false }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // This view spans both eyes; draw chrome + cursor live in each so the
            // right-eye pointer never lags the left (was blitted via the mirror).
            val eye = width / 2
            drawChrome(canvas, eye, height)
            drawCursorOnto(canvas, cursorX, cursorY)
            canvas.save(); canvas.translate(eye.toFloat(), 0f)
            drawChrome(canvas, eye, height)
            canvas.restore()
            drawCursorOnto(canvas, cursorX + eye, cursorY)
            // keep animating: fast tick for the voice pulse, slow for status expiry
            if (voiceState != VoiceState.OFF) postInvalidateDelayed(90L)
            else if (statusText != null) postInvalidateDelayed(400L)
        }
        /** Gear hotspot + transient status; also blitted onto the right-eye mirror. */
        fun drawChrome(canvas: Canvas, w: Int, h: Int) {
            // ⚙ hotspot, top-right
            chromeFill.color = 0x59000000
            canvas.drawCircle(w - 22f, 22f, 15f, chromeFill)
            chromeText.textSize = 16f
            chromeText.color = 0xCCFFFFFF.toInt()
            canvas.drawText("⚙", w - 22f, 28f, chromeText)
            // Live-voice avatar, top-centre: mic orb + state word + pulse ring.
            // Green = we hear you, amber = thinking, cyan = speaking. No red —
            // red subpixels are dim on this waveguide.
            val vs = voiceState
            if (vs != VoiceState.OFF) {
                val cx = w / 2f
                val cy = 26f
                val tint = when (vs) {
                    VoiceState.CONNECTING -> 0xFFB8C2CC.toInt() // grey
                    VoiceState.LISTENING -> 0xFF39D98A.toInt()  // green
                    VoiceState.HEARING -> 0xFF8CFFC2.toInt()    // bright green
                    VoiceState.THINKING -> 0xFFFFC24D.toInt()   // amber
                    VoiceState.SPEAKING -> 0xFF4DC9FF.toInt()   // cyan
                    else -> Color.WHITE
                }
                voiceFill.color = 0x66000000
                canvas.drawCircle(cx, cy, 15f, voiceFill)
                // pulse ring for every active-ish state (steady while just listening)
                if (vs != VoiceState.LISTENING) {
                    val pulse = (SystemClock.uptimeMillis() % 1100L) / 1100f
                    voiceStroke.color = tint
                    voiceStroke.alpha = (210 * (1f - pulse)).toInt()
                    canvas.drawCircle(cx, cy, 15f + 7f * pulse, voiceStroke)
                }
                // mic glyph: capsule + cradle arc + stem
                voiceFill.color = tint
                canvas.drawRoundRect(cx - 3.5f, cy - 9f, cx + 3.5f, cy + 2f, 3.5f, 3.5f, voiceFill)
                voiceStroke.color = tint
                voiceStroke.alpha = 255
                voiceArc.set(cx - 7f, cy - 6f, cx + 7f, cy + 6f)
                canvas.drawArc(voiceArc, 25f, 130f, false, voiceStroke)
                canvas.drawLine(cx, cy + 6f, cx, cy + 10f, voiceStroke)
                // state word under the orb
                chromeText.textSize = 11f
                chromeText.color = tint
                canvas.drawText(
                    when (vs) {
                        VoiceState.CONNECTING -> "connecting…"
                        VoiceState.LISTENING -> "listening"
                        VoiceState.HEARING -> "hearing you"
                        VoiceState.THINKING -> "thinking…"
                        VoiceState.SPEAKING -> "speaking"
                        else -> ""
                    },
                    cx, cy + 26f, chromeText
                )
            }
            // status line, bottom-center
            val msg = statusText
            if (msg != null) {
                if (SystemClock.uptimeMillis() > statusUntilMs) { statusText = null; return }
                chromeText.textSize = 14f
                val tw = chromeText.measureText(msg)
                chromeFill.color = 0xCC101820.toInt()
                canvas.drawRect(w / 2f - tw / 2f - 12f, h - 52f, w / 2f + tw / 2f + 12f, h - 26f, chromeFill)
                chromeText.color = Color.WHITE
                canvas.drawText(msg, w / 2f, h - 34f, chromeText)
            }
        }
        fun drawCursorOnto(canvas: Canvas, x: Float, y: Float) {
            // Classic top-left pointer, hotspot at (x, y). Points are the standard
            // arrow outline scaled up (~26px tall) for legibility on the waveguide.
            val p = arrowPath
            p.rewind()
            p.moveTo(x, y)
            p.lineTo(x, y + 24f)
            p.lineTo(x + 6f, y + 18f)
            p.lineTo(x + 10.5f, y + 27f)
            p.lineTo(x + 14.5f, y + 25f)
            p.lineTo(x + 10f, y + 16.5f)
            p.lineTo(x + 17f, y + 16.5f)
            p.close()
            canvas.drawPath(p, arrowStroke)
            canvas.drawPath(p, arrowFill)
        }
    }

    companion object {
        private const val TAG = "X3GeoLibre-Gecko"
        // Cursor feel copied from TapInsight: gentle 0.45 gain, per-frame step cap.
        private const val CURSOR_GAIN = 0.45f
        private const val MAX_CURSOR_STEP_PX = 160f
        private const val MULTI_TAP_WINDOW_MS = 330L
        private const val CROSS_SOURCE_DUPLICATE_MS = 110L
        // Edge-pan: only pans while the cursor is pinned within PIN px of the very
        // edge AND the finger is pushing past it by at least PUSH_MIN per move.
        // GAIN scales the wasted push into map-drag pixels; MARGIN keeps the
        // synthetic drag inside the canvas before it re-grabs at centre.
        private const val EDGE_PIN_PX = 3f
        private const val EDGE_PUSH_MIN = 0.6f
        private const val EDGE_PAN_GAIN = 1.6f
        private const val EDGE_PAN_MARGIN_PX = 90f
        // Liftoff-jump correction (see dispatchTouchEvent ACTION_UP): undo a final
        // move that lands right at lift — the capacitive pad's last sample spikes.
        private const val LIFT_JUMP_WINDOW_MS = 150L
        private const val LIFT_JUMP_MAX_PX = 110f
        // Right-eye mirror cadence: ~30fps while interacting (lag ~1–2 frames,
        // barely perceptible and identical when still) — halves the PixelCopy
        // readback heat vs 60fps. A trickle when idle, frozen while audio plays.
        private const val MIRROR_ACTIVE_MS = 33L
        private const val MIRROR_IDLE_MS = 55L
        private const val MIRROR_ACTIVE_WINDOW_MS = 1200L
        private const val MIRROR_IDLE_SKIP = 6
        private const val AUDIO_POLL_MS = 250L
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(finishTapSequence)
        tapCount = 0
        mirrorHandler.removeCallbacksAndMessages(null)
        runCatching { mirrorThread.quitSafely() }
        super.onDetachedFromWindow()
    }
}
