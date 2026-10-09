package com.backer

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
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
 * A single tap is Back. A tap-and-hold consumes the gesture and does nothing.
 * Move mode starts only after a second down within [DOUBLE_TAP_MS] of the first up,
 * then a hold of [HOLD_MS]. A single drag never moves the ball.
 * Swipes lock to the dominant axis once travel exceeds [swipeSlopPx].
 */
class BallOverlay(
    private val service: BackerAccessibilityService
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private val ballSizePx = (BALL_SIZE_DP * density).toInt()
    private val tapSlopPx = TAP_SLOP_DP * density
    private val swipeSlopPx = SWIPE_SLOP_DP * density
    private val minFlingVelocity = ViewConfiguration.get(service).scaledMinimumFlingVelocity.toFloat()
    private var velocityTracker: VelocityTracker? = null

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
    private var view: View? = null
    private var attached = false

    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTime = 0L
    private var lastUpTime = 0L
    private var lastUpX = 0f
    private var lastUpY = 0f
    private var armedForMove = false
    private var moving = false
    private var swiping = false
    private var swipeAxis = Axis.NONE
    private var holdConsumed = false
    private var dragOriginX = 0
    private var dragOriginY = 0

    fun attach() {
        if (attached) return
        loadPosition()
        val composeView = ComposeView(service).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { BallFace() }
            setOnTouchListener { _, event -> handleTouch(event) }
            contentDescription = service.getString(R.string.ball_content_description)
        }
        owner.start()
        view = composeView
        windowManager.addView(composeView, params)
        attached = true
    }

    fun detach() {
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
        downX = event.x
        downY = event.y
        downRawX = event.rawX
        downRawY = event.rawY
        downTime = event.eventTime
        moving = false
        swiping = false
        swipeAxis = Axis.NONE
        holdConsumed = false
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
        val gap = event.eventTime - lastUpTime
        val nearLast = hypot(event.rawX - lastUpX, event.rawY - lastUpY) <= tapSlopPx
        armedForMove = gap in 1..DOUBLE_TAP_MS && nearLast
        dragOriginX = params.x
        dragOriginY = params.y
    }

    private fun onMove(event: MotionEvent) {
        velocityTracker?.addMovement(event)
        val dx = event.rawX - downRawX
        val dy = event.rawY - downRawY
        val travel = hypot(dx, dy)
        val held = event.eventTime - downTime

        if (armedForMove && !moving && !swiping && held >= HOLD_MS) {
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
            swiping = true
            armedForMove = false
            swipeAxis = if (abs(dx) >= abs(dy)) Axis.HORIZONTAL else Axis.VERTICAL
            return
        }
        // A press that stays put past the hold window is not a tap and not a move.
        if (!armedForMove && held >= HOLD_MS) {
            holdConsumed = true
        }
    }

    private fun onUp(event: MotionEvent) {
        velocityTracker?.addMovement(event)
        val dx = event.rawX - downRawX
        val dy = event.rawY - downRawY
        val travel = hypot(dx, dy)
        val held = event.eventTime - downTime

        if (moving) {
            updatePosition(dragOriginX + dx.toInt(), dragOriginY + dy.toInt())
            savePosition()
            clearTapMemory()
            moving = false
            armedForMove = false
            releaseVelocityTracker()
            return
        }
        if (swiping) {
            if (hasSwipeVelocity()) {
                dispatchSwipe(dx, dy)
            }
            clearTapMemory()
            swiping = false
            releaseVelocityTracker()
            return
        }
        if (holdConsumed || held >= HOLD_MS) {
            clearTapMemory()
            holdConsumed = false
            armedForMove = false
            releaseVelocityTracker()
            return
        }
        if (travel <= tapSlopPx && held <= TAP_MS) {
            lastUpTime = event.eventTime
            lastUpX = event.rawX
            lastUpY = event.rawY
            armedForMove = false
            service.performSystemAction(AccessibilityService.GLOBAL_ACTION_BACK)
            releaseVelocityTracker()
            return
        }
        clearTapMemory()
        releaseVelocityTracker()
    }

    private fun hasSwipeVelocity(): Boolean {
        val tracker = velocityTracker ?: return false
        tracker.computeCurrentVelocity(1000)
        val speed = hypot(tracker.xVelocity, tracker.yVelocity)
        return speed >= minFlingVelocity
    }

    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun dispatchSwipe(dx: Float, dy: Float) {
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
        if (moving) {
            savePosition()
        }
        moving = false
        swiping = false
        armedForMove = false
        holdConsumed = false
        swipeAxis = Axis.NONE
        clearTapMemory()
        releaseVelocityTracker()
    }

    private fun clearTapMemory() {
        lastUpTime = 0L
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
        private const val BALL_SIZE_DP = 56f
        private const val TAP_SLOP_DP = 12f
        private const val SWIPE_SLOP_DP = 48f
        private const val TAP_MS = 200L
        private const val DOUBLE_TAP_MS = 300L
        private const val HOLD_MS = 250L
    }
}

@Composable
private fun BallFace() {
    Canvas(modifier = Modifier.size(56.dp)) {
        val radius = size.minDimension / 2f
        drawCircle(
            color = Color(0xFF1C1B1F).copy(alpha = 0.85f),
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
            color = Color.White,
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

