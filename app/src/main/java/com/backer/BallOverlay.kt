package com.backer

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
 * Release saves the position. A double tap fires nothing.
 * A swipe fires at once when travel exceeds [swipeSlopPx]. Lift then ends it.
 */
class BallOverlay(
    private val service: BackerAccessibilityService
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private val ballSizePx = (BALL_SIZE_DP * density).toInt()
    private val tapSlopPx = TAP_SLOP_DP * density
    private val swipeSlopPx = SWIPE_SLOP_DP * density

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
    private val pendingTap = Runnable {
        tapPending = false
        tapCount = 0
        service.performSystemAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }
    private var dragOriginX = 0
    private var dragOriginY = 0

    fun attach() {
        if (attached) return
        loadPosition()
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
        windowManager.addView(composeView, params)
        attached = true
    }

    fun detach() {
        handler.removeCallbacks(pendingTap)
        pressed.value = false
        val current = view ?: return
        current.setOnTouchListener(null)
        if (attached) {
            windowManager.removeView(current)
            attached = false
        }
        owner.stop()
        view = null
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
        downRawX = event.rawX
        downRawY = event.rawY
        downTime = event.eventTime
        moving = false
        swiping = false
        swipeAxis = Axis.NONE
        swipeFired = false
        holdConsumed = false
        dragOriginX = params.x
        dragOriginY = params.y
    }

    private fun onMove(event: MotionEvent) {
        val dx = event.rawX - downRawX
        val dy = event.rawY - downRawY
        val travel = hypot(dx, dy)
        val held = event.eventTime - downTime

        if (!moving && !swiping && held >= HOLD_MS) {
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
        if (held >= HOLD_MS) {
            holdConsumed = true
        }
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
        if (swiping) {
            pressed.value = false
            tapCount = 0
            swiping = false
            swipeFired = false
            return
        }
        if (holdConsumed || held >= HOLD_MS) {
            pressed.value = false
            tapCount = 0
            holdConsumed = false
            return
        }
        if (travel <= tapSlopPx && held <= TAP_MS) {
            pressed.value = false
            tapCount += 1
            if (tapCount >= 2) {
                handler.removeCallbacks(pendingTap)
                tapPending = false
                tapCount = 0
                return
            }
            tapPending = true
            handler.removeCallbacks(pendingTap)
            handler.postDelayed(pendingTap, TAP_SETTLE_MS)
            return
        }
        handler.removeCallbacks(pendingTap)
        pressed.value = false
        tapPending = false
        tapCount = 0
    }

    private fun dispatchSwipe(dx: Float, dy: Float) {
        pressed.value = false
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

    private fun cancelGesture() {
        handler.removeCallbacks(pendingTap)
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
        val prefs = service.getSharedPreferences(BackerAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
        val bounds = screenBounds()
        val defaultX = (bounds.first - ballSizePx - (16 * density).toInt()).coerceAtLeast(0)
        val defaultY = ((bounds.second - ballSizePx) / 2).coerceAtLeast(0)
        if (!prefs.contains(BackerAccessibilityService.KEY_BALL_X)) {
            params.x = defaultX
            params.y = defaultY
            return
        }
        params.x = prefs.getInt(BackerAccessibilityService.KEY_BALL_X, defaultX)
        params.y = prefs.getInt(BackerAccessibilityService.KEY_BALL_Y, defaultY)
        val maxX = (bounds.first - ballSizePx).coerceAtLeast(0)
        val maxY = (bounds.second - ballSizePx).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    private fun savePosition() {
        service.getSharedPreferences(BackerAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(BackerAccessibilityService.KEY_BALL_X, params.x)
            .putInt(BackerAccessibilityService.KEY_BALL_Y, params.y)
            .apply()
    }

    private enum class Axis {
        NONE,
        HORIZONTAL,
        VERTICAL
    }

    companion object {
        private const val BALL_SIZE_DP = 52f
        private const val TAP_SLOP_DP = 16f
        private const val SWIPE_SLOP_DP = 16f
        private const val TAP_MS = 200L
        private const val TAP_SETTLE_MS = 200L
        private const val HOLD_MS = 250L
    }
}

@Composable
private fun BallFace(flash: Boolean) {
    Canvas(modifier = Modifier.size(52.dp)) {
        val radius = size.minDimension / 2f
        drawCircle(
            color = if (flash) {
                Color.White
            } else {
                Color(0xFF1C1B1F).copy(alpha = 0.45f)
            },
            radius = radius,
            center = Offset(size.width / 2f, size.height / 2f)
        )
        val chevron = Path().apply {
            moveTo(size.width * 0.62f, size.height * 0.30f)
            lineTo(size.width * 0.38f, size.height * 0.50f)
            lineTo(size.width * 0.62f, size.height * 0.70f)
        }
        drawPath(
            path = chevron,
            color = if (flash) Color(0xFF1C1B1F).copy(alpha = 0.70f) else Color.White.copy(alpha = 0.70f),
            style = Stroke(width = size.minDimension * 0.07f)
        )
    }
}

/**
 * ComposeView in a window overlay has no Activity. It still requires a lifecycle owner
 * and a saved-state owner before setContent.
 */
private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
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

