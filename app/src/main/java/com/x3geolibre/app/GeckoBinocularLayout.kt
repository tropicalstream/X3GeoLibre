package com.x3geolibre.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import kotlin.math.hypot
import org.mozilla.geckoview.GeckoView

/**
 * Binocular compositor for a bundled GeckoView engine.
 *
 * Physical 1280x480: the live [geckoView] fills the LEFT eye; a [TextureView]
 * mirror fills the RIGHT eye, refreshed by copying GeckoView's own child
 * SurfaceView via [PixelCopy] (PixelCopy of the Window returns black for
 * SurfaceView content — must copy the surface layer). The cross-hair cursor and
 * the on-screen keyboard are drawn as left-eye overlays AND repainted onto the
 * right-eye mirror each frame (surface PixelCopy doesn't capture Android overlays).
 *
 * Right pad (cyttsp5) drives the cursor + tap-to-click; left pad (cyttsp6) is
 * volume; the temple click arrives as KEYCODE_BUTTON_A/DPAD_CENTER. Clicks/scrolls
 * dispatch as synthetic MotionEvents into [geckoView]; keyboard keys go to [textInput].
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

    /** Long-press (right pad, cursor stationary) → right-click at the
     *  cursor. Args are viewport FRACTIONS (0..1) so the consumer maps
     *  them to CSS pixels regardless of zoom. */
    var longPressHandler: ((fx: Float, fy: Float) -> Unit)? = null

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

    private var mirrorBitmap: Bitmap? = null
    private var geckoSurface: SurfaceView? = null

    // The mirror (PixelCopy readback + TextureView blit) runs on its own thread so
    // it never competes with the main thread / audio (guide gotcha #7).
    private val mirrorThread = HandlerThread("tgpt-mirror").apply { start() }
    private val mirrorHandler = Handler(mirrorThread.looper)
    @Volatile private var inFlight = false
    @Volatile private var keyboardBitmap: Bitmap? = null
    @Volatile private var keyboardTop = 0

    private enum class Side { NONE, LEFT_VOLUME, RIGHT_CURSOR }
    private var activeSide = Side.NONE
    private var lastInputX = 0f
    private var lastInputY = 0f
    private var downInputX = 0f
    private var downInputY = 0f
    private var lastMoveTimeMs = 0L
    private var preMoveCursorX = 0f
    private var preMoveCursorY = 0f
    private var lastMoveDist = 0f
    private var leftVolumeStartY = 0f
    private var leftVolumeStart = 0
    private var lastClickTime = 0L

    private var longPressFired = false
    private val longPressCheck = Runnable {
        // Only when the finger is down on the right pad and hasn't moved.
        if (activeSide == Side.RIGHT_CURSOR && !longPressFired) {
            longPressFired = true
            longPressHandler?.invoke(cursorX / eyeW.coerceAtLeast(1), cursorY / eyeH.coerceAtLeast(1))
        }
    }

    private var edgeScrollDy = 0
    private var edgeScrollActive = false
    private val edgeScrollRunnable = object : Runnable {
        override fun run() {
            if (!edgeScrollActive) return
            if (edgeScrollDy != 0) { scrollGecko(edgeScrollDy); postDelayed(this, EDGE_SCROLL_INTERVAL_MS) }
            else edgeScrollActive = false
        }
    }

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
        addView(cursorView, LayoutParams(eyeW, eyeH).apply { leftMargin = 0 })
        mirrorBitmap = Bitmap.createBitmap(eyeW, eyeH, Bitmap.Config.ARGB_8888)

        post {
            if (width > 0 && height > 0) {
                eyeW = width / 2
                eyeH = height
                geckoView.layoutParams = LayoutParams(eyeW, eyeH).apply { leftMargin = 0 }
                mirror.layoutParams = LayoutParams(eyeW, eyeH).apply { leftMargin = eyeW }
                keyboardContainer.layoutParams = LayoutParams(eyeW, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { leftMargin = 0 }
                cursorView.layoutParams = LayoutParams(eyeW, eyeH).apply { leftMargin = 0 }
                cursorX = eyeW * 0.5f
                cursorY = eyeH * 0.5f
                mirrorBitmap = Bitmap.createBitmap(eyeW, eyeH, Bitmap.Config.ARGB_8888)
                requestLayout()
                Log.d(TAG, "eye = ${eyeW}x$eyeH")
            }
            startMirrorLoop()
        }
    }

    // ------------------------------------------------------------------
    //  Right-eye mirror
    // ------------------------------------------------------------------

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

    private fun startMirrorLoop() {
        mirrorHandler.post(object : Runnable {
            override fun run() {
                // Freeze the right-eye readback while voice audio is active to hand
                // the GPU/CPU to WebRTC (the drop/choppiness is audio-pipeline
                // starvation). Left eye stays live; right eye holds its last frame.
                val audioActive = runCatching {
                    audioManager.isMusicActive || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
                }.getOrDefault(false)
                if (!audioActive) mirrorFrame()
                mirrorHandler.postDelayed(this, if (audioActive) AUDIO_POLL_INTERVAL_MS else MIRROR_INTERVAL_MS)
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
                // callback also on the mirror thread
                if (result == PixelCopy.SUCCESS && mirror.isAvailable) {
                    val canvas = runCatching { mirror.lockCanvas() }.getOrNull()
                    if (canvas != null) {
                        canvas.drawBitmap(bmp, 0f, 0f, null)
                        settingsBitmap?.let { canvas.drawBitmap(it, 40f, settingsTop.toFloat(), null) }
                        keyboardBitmap?.let { canvas.drawBitmap(it, 0f, keyboardTop.toFloat(), null) }
                        cursorView.drawChrome(canvas, eyeW, eyeH)
                        cursorView.drawCursorOnto(canvas, cursorX, cursorY)
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
    @Volatile private var settingsBitmap: Bitmap? = null
    @Volatile private var settingsTop = 0

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
        settingsBitmap = null
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

    /** UI thread: rasterize the settings panel for the right-eye mirror. */
    private fun snapshotSettings() {
        val sc = settingsContainer ?: return
        if (!settingsOpen || sc.visibility != View.VISIBLE || sc.width <= 0 || sc.height <= 0) {
            settingsBitmap = null; return
        }
        settingsBitmap = runCatching {
            Bitmap.createBitmap(sc.width, sc.height, Bitmap.Config.ARGB_8888).also { sc.draw(Canvas(it)) }
        }.getOrNull()
        settingsTop = sc.top
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
            if (event.action == KeyEvent.ACTION_UP) { cursorView.visibility = View.VISIBLE; performCursorClick(event.eventTime) }
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
                lastMoveTimeMs = 0L; lastMoveDist = 0f
                if (activeSide == Side.LEFT_VOLUME) {
                    leftVolumeStartY = rawY
                    leftVolumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    return true
                }
                cursorView.visibility = View.VISIBLE
                longPressFired = false
                removeCallbacks(longPressCheck)
                postDelayed(longPressCheck, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                if (activeSide == Side.LEFT_VOLUME) { adjustVolume(rawY); return true }
                if (activeSide == Side.NONE) activeSide = classifySide(ev, logicalWidth)
                val dx = localX - lastInputX
                val dy = rawY - lastInputY
                if (abs(dx) < 0.35f && abs(dy) < 0.35f) return true
                // Real movement = a cursor drag, not a long-press.
                if (abs(localX - downInputX) > touchSlop || abs(rawY - downInputY) > touchSlop) {
                    removeCallbacks(longPressCheck)
                }
                preMoveCursorX = cursorX; preMoveCursorY = cursorY
                moveCursor(dx, dy)
                lastMoveDist = hypot(cursorX - preMoveCursorX, cursorY - preMoveCursorY)
                lastMoveTimeMs = ev.eventTime
                lastInputX = localX; lastInputY = rawY
                if (isKeyboardVisible()) stopEdgeScroll() else updateEdgeScroll()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_HOVER_EXIT -> {
                if (activeSide == Side.LEFT_VOLUME) { activeSide = Side.NONE; return true }
                if (lastMoveTimeMs != 0L &&
                    ev.eventTime - lastMoveTimeMs <= LIFT_JUMP_WINDOW_MS &&
                    lastMoveDist <= LIFT_JUMP_MAX_PX
                ) { cursorX = preMoveCursorX; cursorY = preMoveCursorY; cursorView.invalidate() }
                removeCallbacks(longPressCheck)
                val moved = abs(localX - downInputX) > touchSlop || abs(rawY - downInputY) > touchSlop
                val ended = ev.actionMasked != MotionEvent.ACTION_CANCEL && ev.actionMasked != MotionEvent.ACTION_HOVER_EXIT
                if (!moved && ended && !longPressFired) performCursorClick(ev.eventTime)
                longPressFired = false
                stopEdgeScroll()
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
            event.isFromSource(InputDevice.SOURCE_MOUSE) -> Side.RIGHT_CURSOR
            else -> if (event.getX(0) < logicalWidth) Side.LEFT_VOLUME else Side.RIGHT_CURSOR
        }
    }

    private fun adjustVolume(rawY: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val steps = ((leftVolumeStartY - rawY) / 34f).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (leftVolumeStart + steps).coerceIn(0, max), 0)
    }

    private fun moveCursor(dx: Float, dy: Float) {
        cursorX = (cursorX + dx * CURSOR_SENSITIVITY).coerceIn(1f, eyeW - 1f)
        cursorY = (cursorY + dy * CURSOR_SENSITIVITY).coerceIn(1f, eyeH - 1f)
        cursorView.cursorX = cursorX
        cursorView.cursorY = cursorY
        cursorView.invalidate()
    }

    private fun updateEdgeScroll() {
        val band = EDGE_SCROLL_BAND_PX
        val up = ((band - cursorY) / band).coerceIn(0f, 1f)
        val down = ((cursorY - (eyeH - band)) / band).coerceIn(0f, 1f)
        edgeScrollDy = ((down - up) * EDGE_SCROLL_MAX_STEP).toInt()
        if (edgeScrollDy == 0) { stopEdgeScroll(); return }
        if (!edgeScrollActive) { edgeScrollActive = true; removeCallbacks(edgeScrollRunnable); post(edgeScrollRunnable) }
    }

    private fun stopEdgeScroll() { edgeScrollActive = false; edgeScrollDy = 0; removeCallbacks(edgeScrollRunnable) }

    private fun performCursorClick(eventTime: Long) {
        val now = SystemClock.uptimeMillis()
        if (now - lastClickTime < CLICK_DEBOUNCE_MS) return
        lastClickTime = now
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
        clickGecko(eventTime)
    }

    private fun clickGecko(eventTime: Long) {
        val down = MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
        geckoView.dispatchTouchEvent(down); down.recycle()
        postDelayed({
            val up = MotionEvent.obtain(eventTime, eventTime + 48L, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
            geckoView.dispatchTouchEvent(up); up.recycle()
        }, 48L)
    }

    private fun scrollGecko(dy: Int) {
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            x = cursorX; y = cursorY
            setAxisValue(MotionEvent.AXIS_VSCROLL, -dy / EDGE_SCROLL_MAX_STEP.toFloat())
        }
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        geckoView.dispatchGenericMotionEvent(ev); ev.recycle()
    }

    private class CursorView(context: Context) : View(context) {
        var cursorX = 320f
        var cursorY = 240f
        /** Transient status line ("Groq key saved", dictation errors). */
        var statusText: String? = null
        var statusUntilMs = 0L
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.BLACK }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.WHITE }
        private val chromeFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val chromeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 15f; textAlign = Paint.Align.CENTER
        }
        init { isClickable = false; isFocusable = false }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawChrome(canvas, width, height)
            drawCursorOnto(canvas, cursorX, cursorY)
            if (statusText != null) postInvalidateDelayed(400L)
        }
        /** Gear hotspot + transient status; also blitted onto the right-eye mirror. */
        fun drawChrome(canvas: Canvas, w: Int, h: Int) {
            // ⚙ hotspot, top-right
            chromeFill.color = 0x59000000
            canvas.drawCircle(w - 22f, 22f, 15f, chromeFill)
            chromeText.textSize = 16f
            chromeText.color = 0xCCFFFFFF.toInt()
            canvas.drawText("⚙", w - 22f, 28f, chromeText)
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
            canvas.drawCircle(x, y, 7f, outer); canvas.drawCircle(x, y, 7f, inner)
            canvas.drawLine(x - 12f, y, x + 12f, y, outer); canvas.drawLine(x, y - 12f, x, y + 12f, outer)
            canvas.drawLine(x - 12f, y, x + 12f, y, inner); canvas.drawLine(x, y - 12f, x, y + 12f, inner)
        }
    }

    companion object {
        private const val TAG = "X3GeoLibre-Gecko"
        private const val CURSOR_SENSITIVITY = 0.86f
        /** Stationary hold on the right pad before a right-click fires. */
        private const val LONG_PRESS_MS = 650L
        private const val CLICK_DEBOUNCE_MS = 450L
        private const val LIFT_JUMP_WINDOW_MS = 140L
        private const val LIFT_JUMP_MAX_PX = 48f
        private const val EDGE_SCROLL_BAND_PX = 44f
        private const val EDGE_SCROLL_MAX_STEP = 22
        private const val EDGE_SCROLL_INTERVAL_MS = 33L
        // Right-eye mirror refresh ~22 fps normally; frozen while audio is active,
        // polled every 250ms only to notice when voice ends.
        private const val MIRROR_INTERVAL_MS = 45L
        private const val AUDIO_POLL_INTERVAL_MS = 250L
    }

    override fun onDetachedFromWindow() {
        mirrorHandler.removeCallbacksAndMessages(null)
        runCatching { mirrorThread.quitSafely() }
        super.onDetachedFromWindow()
    }
}
