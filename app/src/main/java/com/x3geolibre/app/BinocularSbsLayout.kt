package com.x3geolibre.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioManager
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
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
 * Side-by-side binocular compositor (ported from TapGarden / TapLinkX3 / TapInsight).
 *
 * The first child is treated as a single logical viewport, measured to half the
 * physical width, then drawn twice (left eye + right eye) so RayNeo X3 Pro shows
 * one logical scene in both lenses. Touch / generic-motion / key events arriving
 * on the right temple pad drive a floating cursor over the logical child.
 *
 * TapGPT additions over TapGarden:
 *  - dispatchKeyEvent: the right-temple physical click arrives as KEYCODE_BUTTON_A /
 *    KEYCODE_DPAD_CENTER (a KEY, never a touch — X3 guide gotcha #1), translated
 *    into a synthetic click at the cursor. De-duped with the touch-up click so a
 *    single physical tap can't fire twice (guide gotcha #11).
 */
class BinocularSbsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val cursorView = CursorView(context)
    private var webView: WebView? = null
    var logicalClickHandler: ((Float, Float) -> Boolean)? = null
    var edgePanHandler: ((Int, Int) -> Unit)? = null
    var edgePanStopHandler: (() -> Unit)? = null
    var contentInteractionBlocked: (() -> Boolean)? = null
    private var cursorX = 320f
    private var cursorY = 240f
    private var activeSide = Side.NONE
    private var lastInputX = 0f
    private var lastInputY = 0f
    private var downInputX = 0f
    private var downInputY = 0f
    private var downCursorX = 0f
    private var downCursorY = 0f
    private var lastMoveTimeMs = 0L
    private var preMoveCursorX = 0f
    private var preMoveCursorY = 0f
    private var lastMoveDist = 0f
    private var leftVolumeStartY = 0f
    private var leftVolumeStart = 0
    private var draggingPage = false
    private var pageDragDownTime = 0L
    private var pageDragX = 0f
    private var pageDragY = 0f
    private var currentInputUsesMirroredCoordinates = false
    private var edgeScrollDx = 0
    private var edgeScrollDy = 0
    private var edgeScrollActive = false
    private var lastClickTime = 0L
    private val edgeScrollRunnable = object : Runnable {
        override fun run() {
            val target = webView
            if (!edgeScrollActive || target == null) return
            if (edgeScrollDx != 0 || edgeScrollDy != 0) {
                edgePanHandler?.invoke(edgeScrollDx, edgeScrollDy)
                postDelayed(this, EDGE_SCROLL_INTERVAL_MS)
            } else {
                stopEdgeScroll()
            }
        }
    }
    private enum class Side { NONE, LEFT_VOLUME, RIGHT_CURSOR }

    init {
        clipChildren = false
        clipToPadding = false
        addView(cursorView)
    }

    fun setWebViewTarget(view: WebView) {
        webView = view
        cursorView.bringToFront()
        post {
            val logicalWidth = logicalViewportWidth(width).coerceAtLeast(1)
            cursorX = logicalWidth * 0.5f
            cursorY = height * 0.5f
            updateCursor()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val logicalWidth = logicalViewportWidth(measuredWidth)
        val logicalHeight = measuredHeight.coerceAtLeast(0)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        // The right-temple physical click arrives as a KEY, not a touch (guide gotcha #1).
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            Log.d(TAG, "dispatchKeyEvent keyCode=$keyCode action=${event.action}")
            if (event.action == KeyEvent.ACTION_UP) {
                cursorView.visibility = View.VISIBLE
                performCursorClick(event.eventTime)
            }
            // Consume both DOWN and UP so the WebView can't also act on them.
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val logicalWidth = logicalViewportWidth(width)
        Log.d(TAG, "dispatchTouchEvent action=${ev.actionMasked} x=${ev.getX(0)} y=${ev.getY(0)} device=${runCatching { ev.device?.name }.getOrNull()} logicalW=$logicalWidth")
        if (logicalWidth <= 0) return super.dispatchTouchEvent(ev)
        return handleGlassesInput(ev, logicalWidth)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return super.dispatchGenericMotionEvent(event)
        val isMouseLike = event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        if (!isMouseLike) return super.dispatchGenericMotionEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                activeSide = Side.RIGHT_CURSOR
                cursorView.visibility = View.VISIBLE
                updateCursor()
                return true
            }
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                val dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                    .takeIf { it != 0f } ?: 0f
                val dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                    .takeIf { it != 0f } ?: 0f
                if (dx != 0f || dy != 0f) {
                    moveCursor(dx, dy, logicalWidth)
                    return true
                }
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                performCursorClick(event.eventTime)
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_CANCEL -> {
                activeSide = Side.NONE
                draggingPage = false
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun handleGlassesInput(event: MotionEvent, logicalWidth: Int): Boolean {
        val rawX = event.getX(0)
        val rawY = event.getY(0)
        val localX =
            if (currentInputUsesMirroredCoordinates && rawX >= logicalWidth) {
                rawX - logicalWidth
            } else {
                rawX
            }
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_BUTTON_PRESS -> {
                activeSide = classifyInputSide(event, logicalWidth)
                currentInputUsesMirroredCoordinates = isUnknownMirroredCoordinateEvent(event, logicalWidth)
                val startX =
                    if (currentInputUsesMirroredCoordinates && rawX >= logicalWidth) {
                        rawX - logicalWidth
                    } else {
                        rawX
                    }
                lastInputX = startX
                lastInputY = rawY
                downInputX = startX
                downInputY = rawY
                downCursorX = cursorX
                downCursorY = cursorY
                lastMoveTimeMs = 0L
                lastMoveDist = 0f
                if (activeSide == Side.LEFT_VOLUME) {
                    leftVolumeStartY = rawY
                    leftVolumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    return true
                }
                cursorView.visibility = View.VISIBLE
                updateCursor()
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                if (activeSide == Side.LEFT_VOLUME) {
                    adjustVolume(rawY)
                    return true
                }
                if (activeSide == Side.NONE) {
                    activeSide = classifyInputSide(event, logicalWidth)
                    if (activeSide == Side.LEFT_VOLUME) {
                        leftVolumeStartY = rawY
                        leftVolumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        adjustVolume(rawY)
                        return true
                    }
                }
                val dx = localX - lastInputX
                val dy = rawY - lastInputY
                if (abs(dx) < 0.35f && abs(dy) < 0.35f) return true
                preMoveCursorX = cursorX
                preMoveCursorY = cursorY
                moveCursor(dx, dy, logicalWidth)
                lastMoveDist = hypot(cursorX - preMoveCursorX, cursorY - preMoveCursorY)
                lastMoveTimeMs = event.eventTime
                lastInputX = localX
                lastInputY = rawY
                if (contentInteractionBlocked?.invoke() == true) {
                    stopEdgeScroll()
                } else {
                    updateEdgeScroll(logicalWidth)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_HOVER_EXIT,
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (activeSide == Side.LEFT_VOLUME) {
                    activeSide = Side.NONE
                    return true
                }
                // Capacitive trackpad liftoff jump: the finger's final sample is
                // slightly off. If the gesture ends right after a small move, undo
                // that last micro-move so the cursor stays put on release.
                if (!draggingPage && activeSide == Side.RIGHT_CURSOR &&
                    lastMoveTimeMs != 0L &&
                    event.eventTime - lastMoveTimeMs <= LIFT_JUMP_WINDOW_MS &&
                    lastMoveDist <= LIFT_JUMP_MAX_PX
                ) {
                    cursorX = preMoveCursorX
                    cursorY = preMoveCursorY
                    updateCursor()
                }
                val moved = abs(localX - downInputX) > touchSlop || abs(rawY - downInputY) > touchSlop
                val ended = action != MotionEvent.ACTION_CANCEL && action != MotionEvent.ACTION_HOVER_EXIT
                Log.d(
                    TAG,
                    "UP: moved=$moved touchSlop=$touchSlop dX=${abs(localX - downInputX)} dY=${abs(rawY - downInputY)} " +
                        "draggingPage=$draggingPage ended=$ended cursor=($cursorX,$cursorY)"
                )
                if (draggingPage) {
                    sendPagePointer(MotionEvent.ACTION_UP, event.eventTime, pageDragDownTime)
                } else if (!moved && ended) {
                    Log.d(TAG, "UP: firing performCursorClick at cursor=($cursorX,$cursorY)")
                    performCursorClick(event.eventTime)
                }
                currentInputUsesMirroredCoordinates = false
                draggingPage = false
                stopEdgeScroll()
                activeSide = Side.NONE
                return true
            }
        }
        return true
    }

    private fun classifyInputSide(event: MotionEvent, logicalWidth: Int): Side {
        val name = runCatching {
            event.device?.name ?: InputDevice.getDevice(event.deviceId)?.name
        }.getOrNull().orEmpty()

        return when {
            // Matches TapInsight/TapBrowser: cyttsp6_mt is the left-arm volume pad.
            name.contains("cyttsp6", ignoreCase = true) -> Side.LEFT_VOLUME
            // Matches TapInsight/TapBrowser: cyttsp5_mt is the right-arm temple pad.
            name.contains("cyttsp5", ignoreCase = true) -> Side.RIGHT_CURSOR
            event.isFromSource(InputDevice.SOURCE_MOUSE) -> Side.RIGHT_CURSOR
            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE -> Side.RIGHT_CURSOR
            else -> if (event.getX(0) < logicalWidth) Side.LEFT_VOLUME else Side.RIGHT_CURSOR
        }
    }

    private fun isUnknownMirroredCoordinateEvent(event: MotionEvent, logicalWidth: Int): Boolean {
        val name = runCatching {
            event.device?.name ?: InputDevice.getDevice(event.deviceId)?.name
        }.getOrNull().orEmpty()
        if (name.contains("cyttsp5", ignoreCase = true) ||
            name.contains("cyttsp6", ignoreCase = true)) {
            return false
        }
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            return false
        }
        return event.getX(0) >= logicalWidth
    }

    private fun adjustVolume(rawY: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val steps = ((leftVolumeStartY - rawY) / 34f).toInt()
        val next = (leftVolumeStart + steps).coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
    }

    private fun moveCursor(dx: Float, dy: Float, logicalWidth: Int) {
        cursorX = (cursorX + dx * CURSOR_SENSITIVITY).coerceIn(1f, logicalWidth - 1f)
        cursorY = (cursorY + dy * CURSOR_SENSITIVITY).coerceIn(1f, height - 1f)
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
        val leftStrength = ((edge - cursorX) / edge).coerceIn(0f, 1f)
        val rightStrength = ((cursorX - (maxX - edge)) / edge).coerceIn(0f, 1f)
        val upStrength = ((edge - cursorY) / edge).coerceIn(0f, 1f)
        val downStrength = ((cursorY - (maxY - edge)) / edge).coerceIn(0f, 1f)

        edgeScrollDx = ((rightStrength - leftStrength) * EDGE_SCROLL_MAX_STEP_PX).toInt()
        edgeScrollDy = ((downStrength - upStrength) * EDGE_SCROLL_MAX_STEP_PX).toInt()

        if (edgeScrollDx == 0 && edgeScrollDy == 0) {
            stopEdgeScroll()
            return
        }
        if (!edgeScrollActive) {
            edgeScrollActive = true
            removeCallbacks(edgeScrollRunnable)
            post(edgeScrollRunnable)
        }
    }

    private fun stopEdgeScroll() {
        if (edgeScrollActive) {
            edgePanStopHandler?.invoke()
        }
        edgeScrollActive = false
        edgeScrollDx = 0
        edgeScrollDy = 0
        removeCallbacks(edgeScrollRunnable)
    }

    private fun performCursorClick(eventTime: Long): Boolean {
        // One dedupe gate for every click source (touch-up, temple KEY, mouse button)
        // so a single physical tap that surfaces as both a touch and a key can't
        // fire twice (guide gotcha #11).
        val now = SystemClock.uptimeMillis()
        if (now - lastClickTime < CLICK_DEBOUNCE_MS) {
            Log.d(TAG, "performCursorClick: DEBOUNCED (${now - lastClickTime}ms < ${CLICK_DEBOUNCE_MS}ms)")
            return true
        }
        lastClickTime = now
        Log.d(TAG, "performCursorClick: invoking logicalClickHandler at ($cursorX,$cursorY)")
        if (logicalClickHandler?.invoke(cursorX, cursorY) == true) return true
        Log.d(TAG, "performCursorClick: logicalClickHandler returned false, falling back to raw MotionEvent click")
        clickAtCursor(eventTime)
        return true
    }

    private fun clickAtCursor(eventTime: Long) {
        val downTime = eventTime
        sendPagePointer(MotionEvent.ACTION_DOWN, eventTime, downTime)
        postDelayed({ sendPagePointer(MotionEvent.ACTION_UP, eventTime + 48L, downTime) }, 48L)
    }

    private fun sendPagePointer(action: Int, eventTime: Long, downTime: Long) {
        val target = webView ?: return
        val ev = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            if (draggingPage) pageDragX else cursorX,
            if (draggingPage) pageDragY else cursorY,
            0
        )
        target.dispatchTouchEvent(ev)
        ev.recycle()
    }

    companion object {
        private const val TAG = "TapGPT-Cursor"
        private const val CURSOR_SENSITIVITY = 0.86f
        private const val EDGE_SCROLL_BAND_PX = 44f
        private const val EDGE_SCROLL_MAX_STEP_PX = 22f
        private const val EDGE_SCROLL_INTERVAL_MS = 33L
        private const val LIFT_JUMP_WINDOW_MS = 140L
        private const val LIFT_JUMP_MAX_PX = 48f
        private const val CLICK_DEBOUNCE_MS = 450L
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        invalidate() // both halves must redraw when logical content changes
    }

    private fun logicalViewportWidth(totalWidth: Int): Int = (totalWidth / 2).coerceAtLeast(0)

    private class CursorView(context: Context) : View(context) {
        var cursorX = 320f
        var cursorY = 240f
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.BLACK
        }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = Color.WHITE
        }

        init {
            visibility = VISIBLE
            isClickable = false
            isFocusable = false
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawCircle(cursorX, cursorY, 7f, outer)
            canvas.drawCircle(cursorX, cursorY, 7f, inner)
            canvas.drawLine(cursorX - 12f, cursorY, cursorX + 12f, cursorY, outer)
            canvas.drawLine(cursorX, cursorY - 12f, cursorX, cursorY + 12f, outer)
            canvas.drawLine(cursorX - 12f, cursorY, cursorX + 12f, cursorY, inner)
            canvas.drawLine(cursorX, cursorY - 12f, cursorX, cursorY + 12f, inner)
        }
    }

}
