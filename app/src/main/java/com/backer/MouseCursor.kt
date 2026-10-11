package com.backer

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Non-touchable cursor overlay. Touch still hits the ball.
 * First show in a session starts at screen center. Later shows reuse the last position.
 */
class MouseCursor(
    private val service: BackerAccessibilityService
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private val sizePx = (CURSOR_SIZE_DP * density).toInt()

    private val params = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private var owner = OverlayLifecycleOwner()
    private var view: View? = null
    private var attached = false
    private var placed = false

    val isShowing: Boolean
        get() = attached

    val x: Float
        get() = params.x.toFloat()

    val y: Float
        get() = params.y.toFloat()

    val centerX: Float
        get() = params.x + sizePx / 2f

    val centerY: Float
        get() = params.y + sizePx / 2f

    fun showAt(x: Float, y: Float) {
        if (!placed) {
            val bounds = screenBounds()
            params.x = ((bounds.first - sizePx) / 2).coerceAtLeast(0)
            params.y = ((bounds.second - sizePx) / 2).coerceAtLeast(0)
            placed = true
        } else {
            applyPosition(x, y)
        }
        if (attached) {
            val current = view ?: return
            windowManager.updateViewLayout(current, params)
            return
        }
        // A destroyed lifecycle owner cannot restart. Use a fresh one per show.
        owner = OverlayLifecycleOwner()
        val composeView = ComposeView(service).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { CursorFace() }
            contentDescription = service.getString(R.string.cursor_content_description)
        }
        owner.start()
        view = composeView
        windowManager.addView(composeView, params)
        attached = true
    }

    fun moveTo(x: Float, y: Float) {
        applyPosition(x, y)
        placed = true
        pushLayout()
    }

    // Clamp a raw drag value to the screen so the stored position never winds up
    // past the edge. Without this, reversing at the edge needs dead travel first.
    fun clampX(v: Float): Float {
        val bounds = screenBounds()
        return v.coerceIn(0f, (bounds.first - sizePx).coerceAtLeast(0).toFloat())
    }

    fun clampY(v: Float): Float {
        val bounds = screenBounds()
        return v.coerceIn(0f, (bounds.second - sizePx).coerceAtLeast(0).toFloat())
    }

    // Dim to alpha 0 instead of removing the view. No rebuild, no flicker.
    // A fully transparent window adds no obscuring opacity, so taps pass through.
    fun setDim(hidden: Boolean) {
        params.alpha = if (hidden) 0f else 1f
        pushLayout()
    }

    private fun pushLayout() {
        val current = view ?: return
        if (attached) {
            windowManager.updateViewLayout(current, params)
        }
    }

    fun hide() {
        val current = view ?: return
        if (attached) {
            try {
                windowManager.removeView(current)
            } catch (_: IllegalArgumentException) {
            }
            attached = false
        }
        owner.stop()
        view = null
    }

    private fun applyPosition(x: Float, y: Float) {
        val bounds = screenBounds()
        val maxX = (bounds.first - sizePx).coerceAtLeast(0)
        val maxY = (bounds.second - sizePx).coerceAtLeast(0)
        params.x = x.toInt().coerceIn(0, maxX)
        params.y = y.toInt().coerceIn(0, maxY)
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

    companion object {
        private const val CURSOR_SIZE_DP = 24f
    }
}

@Composable
private fun CursorFace() {
    Canvas(modifier = Modifier.size(24.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val ringRadius = size.minDimension / 2f - 1.dp.toPx()
        drawCircle(
            color = Color(0xFF1C1B1F),
            radius = ringRadius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = ringRadius - 1.dp.toPx(),
            center = center,
            style = Stroke(width = 1.5.dp.toPx())
        )
        drawCircle(
            color = Color(0xFF1C1B1F),
            radius = 3.dp.toPx(),
            center = center
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = 2.dp.toPx(),
            center = center
        )
    }
}
