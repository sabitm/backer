package com.backer

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Two-row popup anchored to the ball. Full-screen transparent window so taps
 * outside the card dismiss it. The card itself is touchable.
 */
class ActionMenu(
    private val service: BackerAccessibilityService
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private var owner = OverlayLifecycleOwner()
    private var view: View? = null
    private var attached = false
    private var onAction: ((MenuAction) -> Unit)? = null

    private val autoDismiss = Runnable { hide() }

    enum class MenuAction {
        POWER,
        SCREENSHOT
    }

    val isShowing: Boolean
        get() = attached

    fun showAt(ballX: Int, ballY: Int, ballSizePx: Int, onAction: (MenuAction) -> Unit) {
        this.onAction = onAction
        if (attached) {
            hide()
        }
        val cardWidthPx = (CARD_WIDTH_DP * density).toInt()
        val gapPx = (CARD_GAP_DP * density).toInt()
        val bounds = screenBounds()
        // Prefer the right side. Fall back left when the card would overflow.
        var cardX = ballX + ballSizePx + gapPx
        if (cardX + cardWidthPx > bounds.first) {
            cardX = (ballX - cardWidthPx - gapPx).coerceAtLeast(0)
        }
        // Clamp the top edge. The card is short, so bottom overflow is rare.
        val cardY = (ballY - gapPx).coerceAtLeast(0)
        // A destroyed lifecycle owner cannot restart. Use a fresh one per show.
        owner = OverlayLifecycleOwner()
        val composeView = ComposeView(service).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { MenuFace(cardX, cardY, ::onRowClick, ::hide) }
            contentDescription = service.getString(R.string.action_menu_description)
        }
        owner.start()
        view = composeView
        windowManager.addView(composeView, params)
        attached = true
        handler.removeCallbacks(autoDismiss)
        handler.postDelayed(autoDismiss, MENU_TIMEOUT_MS)
    }

    fun hide() {
        handler.removeCallbacks(autoDismiss)
        onAction = null
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

    private fun onRowClick(action: MenuAction) {
        val callback = onAction
        hide()
        callback?.invoke(action)
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
        private const val MENU_TIMEOUT_MS = 5000L
        private const val CARD_WIDTH_DP = 200f
        private const val CARD_GAP_DP = 8f
    }
}

@Composable
private fun MenuFace(
    cardX: Int,
    cardY: Int,
    onRowClick: (ActionMenu.MenuAction) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        MenuCard(cardX, cardY, onRowClick)
    }
}

@Composable
private fun MenuCard(
    cardX: Int,
    cardY: Int,
    onRowClick: (ActionMenu.MenuAction) -> Unit
) {
    Surface(
        modifier = Modifier
            .width(200.dp)
            .offset { IntOffset(cardX, cardY) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1C1B1F).copy(alpha = 0.92f)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MenuRow(
                label = "Power menu",
                onClick = { onRowClick(ActionMenu.MenuAction.POWER) },
                icon = { PowerIcon() }
            )
            MenuRow(
                label = "Screenshot",
                onClick = { onRowClick(ActionMenu.MenuAction.SCREENSHOT) },
                icon = { ScreenshotIcon() }
            )
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        icon()
        Text(text = label, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun PowerIcon() {
    Canvas(modifier = Modifier.size(22.dp)) {
        val stroke = size.minDimension * 0.09f
        drawArc(
            color = Color.White.copy(alpha = 0.9f),
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = stroke)
        )
        drawLine(
            color = Color.White.copy(alpha = 0.9f),
            start = Offset(size.width / 2f, size.height * 0.12f),
            end = Offset(size.width / 2f, size.height * 0.52f),
            strokeWidth = stroke
        )
    }
}

@Composable
private fun ScreenshotIcon() {
    Canvas(modifier = Modifier.size(22.dp)) {
        val stroke = size.minDimension * 0.09f
        drawRect(
            color = Color.White.copy(alpha = 0.9f),
            topLeft = Offset(size.width * 0.1f, size.height * 0.19f),
            size = Size(size.width * 0.8f, size.height * 0.62f),
            style = Stroke(width = stroke)
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = size.minDimension * 0.12f,
            center = Offset(size.width / 2f, size.height / 2f)
        )
    }
}
