package com.backer

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Overlay pointer state machine.
 *
 * A single tap fires Back after [TAP_SETTLE_MS] so a second tap can cancel it.
 * Holding one tap past [HOLD_MS] enters move mode. Drag moves the ball.
 * Release saves the position. A double tap toggles mouse mode.
 * A swipe fires at once when travel exceeds [swipeSlopPx]. Lift then ends it.
 *
 * In mouse mode the ball stays put. Drag moves the cursor at [CURSOR_SPEED].
 * A single tap clicks at the cursor. A swipe scrolls at the cursor. A second double tap exits.
 */
class BallOverlay(
    private val service: BackerAccessibilityService
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audio = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val density = service.resources.displayMetrics.density
    private val ballSizePx = (BALL_SIZE_DP * density).toInt()
    private val tapSlopPx = TAP_SLOP_DP * density
    private val swipeSlopPx = SWIPE_SLOP_DP * density
    private val scrollPx = SCROLL_DP * density
    private val mouseDriveSlopPx = MOUSE_DRIVE_SLOP_DP * density
    private val volumeRangePx = VOLUME_RANGE_DP * density
    // Drift under this at lift still counts as a stationary hold.
    private val longPressSlopPx = LONG_PRESS_SLOP_DP * density
    private val driveCapPx = DRIVE_CAP_DP * density
    private val cursor = MouseCursor(service)
    private val menu = ActionMenu(service)

    private val params = WindowManager.LayoutParams(
        ballSizePx,
        ballSizePx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private val owner = OverlayLifecycleOwner()
    private val handler = Handler(Looper.getMainLooper())
    private val pressed = androidx.compose.runtime.mutableStateOf(false)
    private var view: View? = null
    private var attached = false

    private var downRawX = 0f
    private var downRawY = 0f
    private var downTime = 0L
    private var tapCount = 0
    private var tapPending = false
    private var moving = false
    private var swiping = false
    private var swipeAxis = Axis.NONE
    private var swipeFired = false
    private var holdConsumed = false
    private var mouseMode = false
    private var mouseDriving = false
    private var volumeDriving = false
    private var volumeArmed = false
    private var volumeStart = 0
    private var volumeMax = 1
    private var volumeDownY = 0f
    private var volumeLast = -1
    private var cursorDragX = 0f
    private var cursorDragY = 0f
    private var lastDriveTime = 0L
    private var nextClickId = 0L
    private var activeClick: CursorClick? = null
    private var pressArmed = false
    private var pressLatched = false
    private var pressLatchX = 0f
    private var pressLatchY = 0f
    private var nextPressId = 0L
    private var clickDispatched = false
    private val queuedClicks = ArrayDeque<CursorClick>()
    private val dispatchClick = Runnable {
        val click = activeClick ?: return@Runnable
        if (!mouseMode || !attached) {
            cancelCursorClicks()
            return@Runnable
        }
        clickDispatched = true
        service.clickAt(click.x, click.y, click.id) {
            finishCursorClick(click.id)
        }
    }
    private val pendingTap = Runnable {
        tapPending = false
        if (tapCount == 2) {
            // Second tap settled with no third. Chained 200ms keeps single-tap delay flat.
            tapCount = 0
            if (mouseMode) {
                exitMouseMode()
            } else {
                enterMouseMode()
            }
            return@Runnable
        }
        tapCount = 0
        if (mouseMode) {
            clickThroughCursor()
        } else {
            service.performSystemAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }
    // Second tap held past HOLD_MS enters volume mode instead of mouse toggle.
    private val volumeHold = Runnable {
        if (volumeArmed && !mouseMode && !moving && !swiping) {
            handler.removeCallbacks(pendingTap)
            tapPending = false
            tapCount = 0
            volumeArmed = false
            volumeDriving = true
            volumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            volumeStart = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            volumeDownY = downRawY
            volumeLast = volumeStart
        }
    }
    // Second tap held past HOLD_MS in cursor mode latches a press. Nothing injects
    // while the finger is down. Lift fires the gesture so the system cannot cancel it.
    private val pressHold = Runnable {
        if (pressArmed && mouseMode && !mouseDriving && !pressLatched) {
            handler.removeCallbacks(pendingTap)
            tapPending = false
            tapCount = 0
            pressArmed = false
            latchPress()
        }
    }
    private var dragOriginX = 0
    private var dragOriginY = 0

    fun attach() {
        if (attached) return
        loadPosition()
        // Rotation changes pixel size. Reapply the saved fractions on layout changes
        // so the ball keeps its relative spot instead of sticking to old pixels.
        view?.viewTreeObserver?.removeOnGlobalLayoutListener(layoutListener)
        val composeView = ComposeView(service).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { BallFace(pressed.value) }
            setOnTouchListener { _, event -> handleTouch(event) }
            contentDescription = service.getString(R.string.ball_content_description)
        }
        owner.start()
        view = composeView
        composeView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        windowManager.addView(composeView, params)
        attached = true
    }

    fun detach() {
        handler.removeCallbacks(pendingTap)
        handler.removeCallbacks(volumeHold)
        handler.removeCallbacks(pressHold)
        view?.viewTreeObserver?.removeOnGlobalLayoutListener(layoutListener)
        menu.hide()
        unlatchPress()
        volumeDriving = false
        volumeArmed = false
        pressed.value = false
        exitMouseMode()
        val current = view ?: return
        current.setOnTouchListener(null)
        if (attached) {
            windowManager.removeView(current)
            attached = false
        }
        owner.stop()
        view = null
    }

    // WindowManager does not relayout a fixed overlay on rotation. Track the last
    // bounds and reapply fractions when they change.
    private var lastWidth = 0
    private var lastHeight = 0
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val bounds = screenBounds()
        if (bounds.first != lastWidth || bounds.second != lastHeight) {
            lastWidth = bounds.first
            lastHeight = bounds.second
            applyFractions(loadFractions(), bounds)
            if (moving) {
                // Rotation mid-move leaves dragOrigin on the old grid. Resync it
                // so the next move delta extends from the rotated position.
                dragOriginX = params.x
                dragOriginY = params.y
            }
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                cancelGesture()
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_UP -> onUp(event)
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }

    private fun onDown(event: MotionEvent) {
        pressed.value = true
        // New touches must not expose the cursor while a click is preparing or running.
        if (mouseMode && activeClick == null) {
            cursor.setDim(false)
        }
        // Rotation may leave params on the old grid until the layout listener
        // fires. Clamp now so the move origin starts inside the real screen.
        val bounds = screenBounds()
        if (bounds.first != lastWidth || bounds.second != lastHeight) {
            lastWidth = bounds.first
            lastHeight = bounds.second
            applyFractions(loadFractions(), bounds)
        } else {
            updatePosition(params.x, params.y)
        }
        downRawX = event.rawX
        downRawY = event.rawY
        downTime = event.eventTime
        moving = false
        swiping = false
        swipeAxis = Axis.NONE
        swipeFired = false
        holdConsumed = false
        mouseDriving = false
        volumeDriving = false
        volumeArmed = false
        handler.removeCallbacks(volumeHold)
        dragOriginX = params.x
        dragOriginY = params.y
        // Reserve the second touch before the first tap timer expires in either mode.
        if (tapCount >= 1) {
            handler.removeCallbacks(pendingTap)
            tapPending = false
            if (!mouseMode) {
                volumeArmed = true
                handler.postDelayed(volumeHold, HOLD_MS)
            } else {
                pressArmed = true
                handler.postDelayed(pressHold, HOLD_MS)
            }
        }
    }

    private fun onMove(event: MotionEvent) {
        val dx = event.rawX - downRawX
        val dy = event.rawY - downRawY
        val travel = hypot(dx, dy)
        val held = event.eventTime - downTime

        // Volume drive. Up raises media volume, down lowers it over VOLUME_RANGE_DP.
        if (volumeDriving) {
            val steps = ((volumeDownY - event.rawY) / volumeRangePx * volumeMax).toInt()
            val level = (volumeStart + steps).coerceIn(0, volumeMax)
            if (level != volumeLast) {
                volumeLast = level
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI)
            }
            return
        }
        // Mouse drive is continuous. Hold past HOLD_MS or push past the drive gate,
        // then every move shifts the cursor. No scroll branch. Release ends it.
        // A reserved second touch latches a press instead. Its moves shift the
        // cursor with no injection. Lift fires one gesture for the whole press.
        if (mouseMode && !mouseDriving && (held >= HOLD_MS || travel >= mouseDriveSlopPx)) {
            if (pressLatched) {
                driveCursorTo(event, dx, dy)
                return
            }
            if (pressArmed) {
                if (travel >= mouseDriveSlopPx) {
                    latchPress()
                    // Drop the entry sample like plain drive entry.
                    lastDriveTime = event.eventTime
                    downRawX = event.rawX
                    downRawY = event.rawY
                    return
                }
                // Still holding still. Wait for the press timer.
                return
            }
            handler.removeCallbacks(pendingTap)
            tapPending = false
            tapCount = 0
            mouseDriving = true
            cursorDragX = cursor.x
            cursorDragY = cursor.y
            lastDriveTime = event.eventTime
            // Drop the entry sample. Its delta holds the full gate travel and
            // would throw the cursor on swipe entry. Next sample starts clean.
            downRawX = event.rawX
            downRawY = event.rawY
            return
        }
        if (mouseMode && mouseDriving) {
            driveCursorTo(event, dx, dy)
            return
        }
        if (!mouseMode && !volumeArmed && !moving && !swiping && held >= HOLD_MS) {
            handler.removeCallbacks(pendingTap)
            tapPending = false
            tapCount = 0
            moving = true
        }
        if (moving) {
            updatePosition(dragOriginX + dx.toInt(), dragOriginY + dy.toInt())
            return
        }
        if (swiping) {
            return
        }
        if (travel >= swipeSlopPx) {
            handler.removeCallbacks(pendingTap)
            tapPending = false
            tapCount = 0
            swiping = true
            swipeFired = true
            swipeAxis = if (abs(dx) >= abs(dy)) Axis.HORIZONTAL else Axis.VERTICAL
            dispatchSwipe(dx, dy)
            return
        }
        // A press that stays put past the hold window is not a tap and not a move.
        if (!mouseMode && !volumeArmed && held >= HOLD_MS) {
            holdConsumed = true
        }
    }

    // Shared touchpad drive math for plain cursor moves and press-drag.
    private fun driveCursorTo(event: MotionEvent, dx: Float, dy: Float) {
        // Touchpad-style gain. Slow moves stay DRIVE_BASE_GAIN for control.
        // Fast flings scale up to DRIVE_MAX_GAIN to cross the screen in one stroke.
        val dt = (event.eventTime - lastDriveTime).coerceAtLeast(1L) / 1000f
        val speed = hypot(dx, dy) / dt
        val gain = when {
            speed <= DRIVE_SLOW_PX_S -> DRIVE_BASE_GAIN
            speed >= DRIVE_FAST_PX_S -> DRIVE_MAX_GAIN
            else -> DRIVE_BASE_GAIN + (speed - DRIVE_SLOW_PX_S) / (DRIVE_FAST_PX_S - DRIVE_SLOW_PX_S) * (DRIVE_MAX_GAIN - DRIVE_BASE_GAIN)
        }
        lastDriveTime = event.eventTime
        val stepX = (dx * gain).coerceIn(-driveCapPx, driveCapPx)
        val stepY = (dy * gain).coerceIn(-driveCapPx, driveCapPx)
        cursorDragX = cursor.clampX(cursorDragX + stepX)
        cursorDragY = cursor.clampY(cursorDragY + stepY)
        cursor.moveTo(cursorDragX, cursorDragY)
        downRawX = event.rawX
        downRawY = event.rawY
    }

    private fun latchPress() {
        handler.removeCallbacks(pressHold)
        handler.removeCallbacks(pendingTap)
        tapPending = false
        tapCount = 0
        pressArmed = false
        pressLatched = true
        pressLatchX = cursor.centerX
        pressLatchY = cursor.centerY
        cursorDragX = cursor.x
        cursorDragY = cursor.y
        lastDriveTime = android.os.SystemClock.uptimeMillis()
        cursor.setPressed(true)
    }

    private fun unlatchPress() {
        handler.removeCallbacks(pressHold)
        pressArmed = false
        pressLatched = false
        cursor.setPressed(false)
    }

    private fun onUp(event: MotionEvent) {
        val dx = event.rawX - downRawX
        val dy = event.rawY - downRawY
        val travel = hypot(dx, dy)
        val held = event.eventTime - downTime

        if (moving) {
            updatePosition(dragOriginX + dx.toInt(), dragOriginY + dy.toInt())
            savePosition()
            pressed.value = false
            tapCount = 0
            moving = false
            return
        }
        // A volume drive ends on release with no action.
        if (volumeDriving) {
            handler.removeCallbacks(volumeHold)
            pressed.value = false
            tapCount = 0
            volumeDriving = false
            volumeArmed = false
            return
        }
        // A latched press fires on release, when the finger is up. Drift under
        // the slop counts as stationary long-press. Real displacement fires one
        // swipe from latch point to release, same as plain scroll.
        if (mouseMode && pressLatched) {
            val dx = cursor.centerX - pressLatchX
            val dy = cursor.centerY - pressLatchY
            val drift = hypot(dx, dy)
            Log.d(TAG, "press finger up after ${event.eventTime - downTime}ms drift=${drift.toInt()}px")
            val fromX = pressLatchX
            val fromY = pressLatchY
            val toX = cursor.centerX
            val toY = cursor.centerY
            val stationary = drift <= longPressSlopPx
            pressed.value = false
            tapCount = 0
            unlatchPress()
            // Same occlusion rule as clicks. The cursor sits on the injection
            // point, so hide it first and restore when the gesture finishes.
            cursor.setDim(true)
            handler.postDelayed({
                val restore = {
                    if (mouseMode && attached) cursor.setDim(false)
                }
                if (stationary) {
                    service.longPressAt(fromX, fromY, nextPressId++, restore)
                } else {
                    service.scrollAt(fromX, fromY, toX - fromX, toY - fromY, restore)
                }
            }, CURSOR_HIDE_MS)
            return
        }
        // A mouse drive ends on release with no action. The cursor stays where it stopped.
        if (mouseMode && mouseDriving) {
            pressed.value = false
            tapCount = 0
            mouseDriving = false
            return
        }
        if (swiping) {
            pressed.value = false
            tapCount = 0
            swiping = false
            swipeFired = false
            return
        }
        if (!mouseMode && (holdConsumed || held >= HOLD_MS)) {
            pressed.value = false
            tapCount = 0
            holdConsumed = false
            return
        }
        if (travel <= tapSlopPx && held <= TAP_MS) {
            pressed.value = false
            handler.removeCallbacks(volumeHold)
            handler.removeCallbacks(pressHold)
            volumeArmed = false
            pressArmed = false
            tapCount += 1
            if (tapCount >= 3) {
                handler.removeCallbacks(pendingTap)
                tapPending = false
                tapCount = 0
                if (mouseMode) {
                    service.performSystemAction(AccessibilityService.GLOBAL_ACTION_BACK)
                } else {
                    showActionMenu()
                }
                return
            }
            if (tapCount >= 2) {
                // Wait a second window for a possible third tap. Mouse toggle
                // and single-tap Back both fire from pendingTap.
                tapPending = true
                handler.removeCallbacks(pendingTap)
                handler.postDelayed(pendingTap, TAP_SETTLE_MS)
                return
            }
            tapPending = true
            handler.removeCallbacks(pendingTap)
            handler.postDelayed(pendingTap, TAP_SETTLE_MS)
            return
        }
        handler.removeCallbacks(pendingTap)
        handler.removeCallbacks(volumeHold)
        volumeArmed = false
        pressed.value = false
        tapPending = false
        tapCount = 0
    }

    private fun dispatchSwipe(dx: Float, dy: Float) {
        pressed.value = false
        // Mouse mode has no swipe action. Swipes are consumed by cursor drive on move.
        if (mouseMode) return
        val action = when (swipeAxis) {
            Axis.HORIZONTAL -> if (dx < 0f) {
                AccessibilityService.GLOBAL_ACTION_HOME
            } else {
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            }
            Axis.VERTICAL -> if (dy < 0f) {
                AccessibilityService.GLOBAL_ACTION_RECENTS
            } else {
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            }
            Axis.NONE -> return
        }
        service.performSystemAction(action)
    }

    private fun clickThroughCursor() {
        if (!mouseMode || !attached) return
        val click = CursorClick(++nextClickId, cursor.centerX, cursor.centerY)
        Log.d(TAG, "click ${click.id} queued at ${click.x},${click.y}")
        queuedClicks.addLast(click)
        if (activeClick == null) {
            startNextCursorClick()
        }
    }

    private fun startNextCursorClick() {
        val click = queuedClicks.removeFirstOrNull() ?: return
        activeClick = click
        clickDispatched = false
        // Only one click owns the hidden cursor. Overlapping dispatches cancel each other.
        cursor.setDim(true)
        Log.d(TAG, "click ${click.id} preparing")
        handler.postDelayed(dispatchClick, CURSOR_HIDE_MS)
    }

    private fun finishCursorClick(clickId: Long) {
        // A callback from a previous mouse session must not change the new session.
        if (activeClick?.id != clickId) return
        activeClick = null
        clickDispatched = false
        if (queuedClicks.isNotEmpty()) {
            startNextCursorClick()
        } else if (mouseMode && attached) {
            cursor.setDim(false)
        }
    }

    private fun cancelCursorClicks() {
        handler.removeCallbacks(dispatchClick)
        activeClick?.let { click ->
            val state = if (clickDispatched) "dispatched" else "preparing"
            Log.d(TAG, "click ${click.id} $state tracking cleared on mouse exit")
        }
        for (click in queuedClicks) {
            Log.d(TAG, "click ${click.id} queued cancelled on mouse exit")
        }
        queuedClicks.clear()
        activeClick = null
        clickDispatched = false
    }

    private fun cancelGesture() {
        handler.removeCallbacks(pendingTap)
        handler.removeCallbacks(volumeHold)
        handler.removeCallbacks(pressHold)
        if (mouseMode && activeClick == null) {
            cursor.setDim(false)
        }
        unlatchPress()
        mouseDriving = false
        volumeDriving = false
        volumeArmed = false
        pressed.value = false
        tapPending = false
        tapCount = 0
        if (moving) {
            savePosition()
        }
        moving = false
        swiping = false
        swipeFired = false
        holdConsumed = false
        swipeAxis = Axis.NONE
    }

    private fun enterMouseMode() {
        menu.hide()
        mouseMode = true
        cursor.showAt(cursor.x, cursor.y)
        cursorDragX = cursor.x
        cursorDragY = cursor.y
    }

    private fun exitMouseMode() {
        mouseMode = false
        cancelCursorClicks()
        cursor.setDim(false)
        cursor.hide()
    }

    private fun showActionMenu() {
        // Normal-mode only. Cursor triple-tap sends Back instead of opening the menu.
        menu.showAt(params.x, params.y, ballSizePx, ::onMenuAction)
    }

    private fun onMenuAction(action: ActionMenu.MenuAction) {
        when (action) {
            ActionMenu.MenuAction.POWER -> {
                service.performSystemAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            }
            ActionMenu.MenuAction.SCREENSHOT -> {
                // Hide first so the menu does not appear in the shot.
                handler.postDelayed({
                    service.performSystemAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                }, SCREENSHOT_DELAY_MS)
            }
        }
    }

    private fun updatePosition(x: Int, y: Int) {
        val bounds = screenBounds()
        val maxX = (bounds.first - ballSizePx).coerceAtLeast(0)
        val maxY = (bounds.second - ballSizePx).coerceAtLeast(0)
        params.x = x.coerceIn(0, maxX)
        params.y = y.coerceIn(0, maxY)
        val current = view ?: return
        if (attached) {
            windowManager.updateViewLayout(current, params)
        }
    }

    private fun screenBounds(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics: WindowMetrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            return bounds.width() to bounds.height()
        }
        val display = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(display)
        return display.widthPixels to display.heightPixels
    }

    private fun loadPosition() {
        val bounds = screenBounds()
        lastWidth = bounds.first
        lastHeight = bounds.second
        applyFractions(loadFractions(), bounds)
    }

    private fun loadFractions(): Pair<Float, Float> {
        val prefs = service.getSharedPreferences(BackerAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(BackerAccessibilityService.KEY_BALL_FX)) {
            return prefs.getFloat(BackerAccessibilityService.KEY_BALL_FX, 0.85f) to
                prefs.getFloat(BackerAccessibilityService.KEY_BALL_FY, 0.5f)
        }
        val bounds = screenBounds()
        val maxX = (bounds.first - ballSizePx).coerceAtLeast(1)
        val maxY = (bounds.second - ballSizePx).coerceAtLeast(1)
        // One-time migration from absolute pixels. Then the old keys are deleted.
        if (prefs.contains(BackerAccessibilityService.KEY_BALL_X)) {
            val fx = prefs.getInt(BackerAccessibilityService.KEY_BALL_X, maxX) / maxX.toFloat()
            val fy = prefs.getInt(BackerAccessibilityService.KEY_BALL_Y, maxY / 2) / maxY.toFloat()
            prefs.edit()
                .putFloat(BackerAccessibilityService.KEY_BALL_FX, fx.coerceIn(0f, 1f))
                .putFloat(BackerAccessibilityService.KEY_BALL_FY, fy.coerceIn(0f, 1f))
                .remove(BackerAccessibilityService.KEY_BALL_X)
                .remove(BackerAccessibilityService.KEY_BALL_Y)
                .apply()
            return prefs.getFloat(BackerAccessibilityService.KEY_BALL_FX, 0.85f) to
                prefs.getFloat(BackerAccessibilityService.KEY_BALL_FY, 0.5f)
        }
        return 0.85f to 0.5f
    }

    private fun applyFractions(fractions: Pair<Float, Float>, bounds: Pair<Int, Int>) {
        val maxX = (bounds.first - ballSizePx).coerceAtLeast(0)
        val maxY = (bounds.second - ballSizePx).coerceAtLeast(0)
        params.x = (fractions.first.coerceIn(0f, 1f) * maxX).toInt()
        params.y = (fractions.second.coerceIn(0f, 1f) * maxY).toInt()
        val current = view ?: return
        if (attached) {
            windowManager.updateViewLayout(current, params)
        }
    }

    private fun savePosition() {
        val bounds = screenBounds()
        val maxX = (bounds.first - ballSizePx).coerceAtLeast(1)
        val maxY = (bounds.second - ballSizePx).coerceAtLeast(1)
        service.getSharedPreferences(BackerAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(BackerAccessibilityService.KEY_BALL_FX, (params.x / maxX.toFloat()).coerceIn(0f, 1f))
            .putFloat(BackerAccessibilityService.KEY_BALL_FY, (params.y / maxY.toFloat()).coerceIn(0f, 1f))
            .apply()
    }

    private data class CursorClick(val id: Long, val x: Float, val y: Float)

    private enum class Axis {
        NONE,
        HORIZONTAL,
        VERTICAL
    }

    companion object {
        private const val BALL_SIZE_DP = 56f
        private const val TAP_SLOP_DP = 16f
        private const val SWIPE_SLOP_DP = 16f
        private const val TAP_MS = 200L
        private const val TAP_SETTLE_MS = 200L
        private const val HOLD_MS = 250L
        private const val CURSOR_SPEED = 1.5f
        private const val DRIVE_BASE_GAIN = 1.5f
        private const val DRIVE_SLOW_PX_S = 800f
        private const val DRIVE_FAST_PX_S = 4000f
        private const val DRIVE_MAX_GAIN = 4f
        private const val DRIVE_CAP_DP = 96f
        private const val SCROLL_DP = 200f
        private const val CURSOR_HIDE_MS = 50L
        private const val TAG = "Backer"
        private const val MOUSE_DRIVE_SLOP_DP = 8f
        private const val LONG_PRESS_SLOP_DP = 24f
        private const val VOLUME_RANGE_DP = 200f
        private const val SCREENSHOT_DELAY_MS = 300L
    }
}

@Composable
private fun BallFace(flash: Boolean) {
    Canvas(modifier = Modifier.size(56.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f
        if (flash) {
            drawCircle(color = Color.White, radius = radius, center = center)
            val chevron = Path().apply {
                moveTo(size.width * 0.60f, size.height * 0.36f)
                lineTo(size.width * 0.42f, size.height * 0.50f)
                lineTo(size.width * 0.60f, size.height * 0.64f)
            }
            drawPath(
                path = chevron,
                color = Color(0xFF1C1B1F).copy(alpha = 0.85f),
                style = Stroke(width = size.minDimension * 0.09f)
            )
            return@Canvas
        }
        drawCircle(
            color = Color(0xFF1C1B1F).copy(alpha = 0.65f),
            radius = radius,
            center = center
        )
        drawCircle(
            color = Color(0xFF5F6368).copy(alpha = 0.65f),
            radius = radius * 0.76f,
            center = center
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.75f),
            radius = radius * 0.60f,
            center = center
        )
        val chevron = Path().apply {
            moveTo(size.width * 0.58f, size.height * 0.40f)
            lineTo(size.width * 0.44f, size.height * 0.50f)
            lineTo(size.width * 0.58f, size.height * 0.60f)
        }
        drawPath(
            path = chevron,
            color = Color(0xFF1C1B1F).copy(alpha = 0.85f),
            style = Stroke(width = size.minDimension * 0.07f)
        )
    }
}

/**
 * ComposeView in a window overlay has no Activity. It still requires a lifecycle owner
 * and a saved-state owner before setContent.
 */
internal class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = registry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
    }

    fun start() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

