package com.x3geolibre.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Side-by-side binocular compositor (ported from TapGarden / TapInsight).
 *
 * The children (WebView + keyboard + cursor) are one logical viewport measured to
 * half the physical width, then drawn TWICE — left eye + right eye — in the same
 * dispatchDraw pass, so both lenses show the identical frame with zero lag. A
 * single WebView draws twice cleanly; GeckoView's SurfaceView could not (which is
 * why the old GeckoView mirror lagged / the eyes desynced).
 *
 * Right pad (cyttsp5) = cursor + tap; left pad (cyttsp6) = volume; temple firm
 * click = KEYCODE_BUTTON_A. 1 tap = click / "What's here?", 2 = context menu,
 * 3 = AI assistant. Cursor feel copied from TapInsight (0.45 gain, arrow pointer).
 */
class BinocularSbsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val cursorView = CursorView(context)
    private var webView: WebView? = null

    /** Returns true if it consumed the click (e.g. the keyboard did). */
    var logicalClickHandler: ((Float, Float) -> Boolean)? = null
    /** Edge-pan tick: dx/dy in logical px. */
    var edgePanHandler: ((Int, Int) -> Unit)? = null
    var edgePanStopHandler: (() -> Unit)? = null
    /** True while an app overlay (keyboard) owns the screen — suppresses edge-pan. */
    var contentInteractionBlocked: (() -> Boolean)? = null
    /** Single tap on the page (not keyboard) → host may add "What's here?". */
    var singleTapHandler: ((Float, Float) -> Unit)? = null
    /** Double tap → right-click / context menu at cursor (viewport fractions). */
    var doubleTapHandler: ((Float, Float) -> Unit)? = null
    /** Triple tap → toggle the AI assistant. */
    var tripleTapHandler: ((Float, Float) -> Unit)? = null

    private var cursorX = 320f
    private var cursorY = 240f
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
    private var dropNextDelta = false
    private var currentInputUsesMirroredCoordinates = false

    private var edgeScrollDx = 0
    private var edgeScrollDy = 0
    private var edgeScrollActive = false
    private val edgeScrollRunnable = object : Runnable {
        override fun run() {
            if (!edgeScrollActive || webView == null) return
            if (edgeScrollDx != 0 || edgeScrollDy != 0) {
                edgePanHandler?.invoke(edgeScrollDx, edgeScrollDy)
                postDelayed(this, EDGE_SCROLL_INTERVAL_MS)
            } else stopEdgeScroll()
        }
    }

    // Multi-tap: some firmware emits both a touch-up and a BUTTON_A for one tap,
    // so cross-source events inside a short window are collapsed.
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
            1 -> doSingleClick(pendingTapEventTime)
            2 -> doubleTapHandler?.invoke(cursorFx(), cursorFy())
        }
    }

    private enum class Side { NONE, LEFT_VOLUME, RIGHT_CURSOR }

    init {
        clipChildren = false
        clipToPadding = false
        addView(cursorView)
    }

    fun attachWebView(view: WebView) {
        webView = view
        if (view.parent !== this) addView(view, 0)
        cursorView.bringToFront()
        post {
            val lw = logicalViewportWidth(width).coerceAtLeast(1)
            cursorX = lw * 0.5f; cursorY = height * 0.5f; updateCursor()
        }
    }

    fun setWebViewTarget(view: WebView) {
        webView = view
        cursorView.bringToFront()
        post {
            val lw = logicalViewportWidth(width).coerceAtLeast(1)
            cursorX = lw * 0.5f; cursorY = height * 0.5f; updateCursor()
        }
    }

    private fun cursorFx() = cursorX / logicalViewportWidth(width).coerceAtLeast(1)
    private fun cursorFy() = cursorY / height.coerceAtLeast(1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val logicalWidth = logicalViewportWidth(measuredWidth)
        val logicalHeight = measuredHeight.coerceAtLeast(0)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(logicalWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(logicalHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return
        val drawTime = drawingTime
        canvas.save()
        canvas.clipRect(0, 0, logicalWidth, height)
        drawLogicalChildren(canvas, drawTime)
        canvas.restore()
        canvas.save()
        canvas.translate(logicalWidth.toFloat(), 0f)
        canvas.clipRect(0, 0, logicalWidth, height)
        drawLogicalChildren(canvas, drawTime)
        canvas.restore()
    }

    private fun drawLogicalChildren(canvas: Canvas, drawTime: Long) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != GONE) drawChild(canvas, child, drawTime)
        }
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        invalidate() // both halves must redraw when logical content changes
    }

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
        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return super.dispatchTouchEvent(ev)
        return handleGlassesInput(ev, logicalWidth)
    }

    private fun handleGlassesInput(event: MotionEvent, logicalWidth: Int): Boolean {
        val rawX = event.getX(0)
        val rawY = event.getY(0)
        val localX = if (currentInputUsesMirroredCoordinates && rawX >= logicalWidth) rawX - logicalWidth else rawX
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_HOVER_ENTER -> {
                activeSide = classifyInputSide(event, logicalWidth)
                currentInputUsesMirroredCoordinates = isUnknownMirroredCoordinateEvent(event, logicalWidth)
                val startX = if (currentInputUsesMirroredCoordinates && rawX >= logicalWidth) rawX - logicalWidth else rawX
                lastInputX = startX; lastInputY = rawY
                downInputX = startX; downInputY = rawY
                lastMoveTimeMs = 0L; lastMoveDist = 0f
                dropNextDelta = true
                if (activeSide == Side.LEFT_VOLUME) {
                    leftVolumeStartY = rawY
                    leftVolumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    return true
                }
                cursorView.visibility = View.VISIBLE; updateCursor()
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                if (activeSide == Side.LEFT_VOLUME) { adjustVolume(rawY); return true }
                if (activeSide == Side.NONE) activeSide = classifyInputSide(event, logicalWidth)
                var dx = localX - lastInputX
                var dy = rawY - lastInputY
                lastInputX = localX; lastInputY = rawY
                if (dropNextDelta) { dropNextDelta = false; return true }
                if (abs(dx) < 0.35f && abs(dy) < 0.35f) return true
                dx = dx.coerceIn(-MAX_STEP_PX, MAX_STEP_PX)
                dy = dy.coerceIn(-MAX_STEP_PX, MAX_STEP_PX)
                preMoveCursorX = cursorX; preMoveCursorY = cursorY
                moveCursor(dx, dy, logicalWidth)
                lastMoveDist = hypot(cursorX - preMoveCursorX, cursorY - preMoveCursorY)
                lastMoveTimeMs = event.eventTime
                if (contentInteractionBlocked?.invoke() == true) stopEdgeScroll() else updateEdgeScroll(logicalWidth)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_HOVER_EXIT -> {
                if (activeSide == Side.LEFT_VOLUME) { activeSide = Side.NONE; return true }
                val moved = abs(localX - downInputX) > touchSlop || abs(rawY - downInputY) > touchSlop
                val ended = event.actionMasked != MotionEvent.ACTION_CANCEL &&
                    event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
                if (!moved && ended) registerTap(event.eventTime, TapSource.TOUCH)
                currentInputUsesMirroredCoordinates = false
                stopEdgeScroll()
                activeSide = Side.NONE
                return true
            }
        }
        return true
    }

    /** Collapse cross-source duplicates, then dispatch by tap count. */
    private fun registerTap(eventTime: Long, source: TapSource) {
        val now = SystemClock.uptimeMillis()
        val duplicate = lastTapSource != null && lastTapSource != source &&
            now - lastRawTapAtMs <= CROSS_SOURCE_DUPLICATE_MS
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
            tripleTapHandler?.invoke(cursorFx(), cursorFy())
        } else {
            postDelayed(finishTapSequence, MULTI_TAP_WINDOW_MS)
        }
    }

    private fun doSingleClick(eventTime: Long) {
        if (logicalClickHandler?.invoke(cursorX, cursorY) == true) return
        clickAtCursor(eventTime)
        singleTapHandler?.invoke(cursorFx(), cursorFy())
    }

    private fun classifyInputSide(event: MotionEvent, logicalWidth: Int): Side {
        val name = runCatching {
            event.device?.name ?: InputDevice.getDevice(event.deviceId)?.name
        }.getOrNull().orEmpty()
        return when {
            name.contains("cyttsp6", true) -> Side.LEFT_VOLUME
            name.contains("cyttsp5", true) -> Side.RIGHT_CURSOR
            event.isFromSource(InputDevice.SOURCE_MOUSE) -> Side.RIGHT_CURSOR
            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE -> Side.RIGHT_CURSOR
            else -> if (event.getX(0) < logicalWidth) Side.LEFT_VOLUME else Side.RIGHT_CURSOR
        }
    }

    private fun isUnknownMirroredCoordinateEvent(event: MotionEvent, logicalWidth: Int): Boolean {
        val name = runCatching {
            event.device?.name ?: InputDevice.getDevice(event.deviceId)?.name
        }.getOrNull().orEmpty()
        if (name.contains("cyttsp5", true) || name.contains("cyttsp6", true)) return false
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return false
        return event.getX(0) >= logicalWidth
    }

    private fun adjustVolume(rawY: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val steps = ((leftVolumeStartY - rawY) / 34f).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (leftVolumeStart + steps).coerceIn(0, max), 0)
    }

    private fun moveCursor(dx: Float, dy: Float, logicalWidth: Int) {
        cursorX = (cursorX + dx * CURSOR_GAIN).coerceIn(1f, logicalWidth - 1f)
        cursorY = (cursorY + dy * CURSOR_GAIN).coerceIn(1f, height - 1f)
        updateCursor()
    }

    private fun updateCursor() {
        cursorView.cursorX = cursorX
        cursorView.cursorY = cursorY
        cursorView.invalidate()
        invalidate()
    }

    private fun updateEdgeScroll(logicalWidth: Int) {
        val edge = EDGE_SCROLL_BAND_PX
        val maxX = logicalWidth.toFloat()
        val maxY = height.toFloat()
        val left = ((edge - cursorX) / edge).coerceIn(0f, 1f)
        val right = ((cursorX - (maxX - edge)) / edge).coerceIn(0f, 1f)
        val up = ((edge - cursorY) / edge).coerceIn(0f, 1f)
        val down = ((cursorY - (maxY - edge)) / edge).coerceIn(0f, 1f)
        edgeScrollDx = ((right - left) * EDGE_SCROLL_MAX_STEP_PX).toInt()
        edgeScrollDy = ((down - up) * EDGE_SCROLL_MAX_STEP_PX).toInt()
        if (edgeScrollDx == 0 && edgeScrollDy == 0) { stopEdgeScroll(); return }
        if (!edgeScrollActive) { edgeScrollActive = true; removeCallbacks(edgeScrollRunnable); post(edgeScrollRunnable) }
    }

    private fun stopEdgeScroll() {
        if (edgeScrollActive) edgePanStopHandler?.invoke()
        edgeScrollActive = false; edgeScrollDx = 0; edgeScrollDy = 0
        removeCallbacks(edgeScrollRunnable)
    }

    private fun clickAtCursor(eventTime: Long) {
        sendPagePointer(MotionEvent.ACTION_DOWN, eventTime, eventTime)
        postDelayed({ sendPagePointer(MotionEvent.ACTION_UP, eventTime + 48L, eventTime) }, 48L)
    }

    private fun sendPagePointer(action: Int, eventTime: Long, downTime: Long) {
        val target = webView ?: return
        val ev = MotionEvent.obtain(downTime, eventTime, action, cursorX, cursorY, 0)
        target.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun logicalViewportWidth(totalWidth: Int): Int = (totalWidth / 2).coerceAtLeast(0)

    companion object {
        private const val CURSOR_GAIN = 0.45f
        private const val MAX_STEP_PX = 160f
        private const val MULTI_TAP_WINDOW_MS = 330L
        private const val CROSS_SOURCE_DUPLICATE_MS = 110L
        private const val EDGE_SCROLL_BAND_PX = 22f
        private const val EDGE_SCROLL_MAX_STEP_PX = 16f
        private const val EDGE_SCROLL_INTERVAL_MS = 22L
    }

    private class CursorView(context: Context) : View(context) {
        var cursorX = 320f
        var cursorY = 240f
        private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
        private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.BLACK
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        private val arrowPath = Path()
        init { visibility = VISIBLE; isClickable = false; isFocusable = false }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val p = arrowPath; val x = cursorX; val y = cursorY
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
}
