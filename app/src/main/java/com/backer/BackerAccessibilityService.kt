package com.backer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

class BackerAccessibilityService : AccessibilityService() {

    private var overlay: BallOverlay? = null
    private val gestureHandler = Handler(Looper.getMainLooper())
    private val gestureCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            Log.d(TAG, "gesture completed")
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            Log.w(TAG, "gesture cancelled by system")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        val ball = BallOverlay(this)
        overlay = ball
        ball.attach()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        removeOverlay()
        if (instance?.get() === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeOverlay()
        if (instance?.get() === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        removeOverlay()
    }

    fun performSystemAction(action: Int): Boolean {
        return performGlobalAction(action)
    }

    fun clickAt(x: Float, y: Float, clickId: Long, onFinished: () -> Unit) {
        if (clickNodeAt(x, y, clickId)) {
            onFinished()
            return
        }
        dispatchTap(x, y, clickId, onFinished)
    }

    // Web content often ignores screen taps but answers node clicks.
    // Try the deepest clickable node at the point first.
    private fun clickNodeAt(x: Float, y: Float, clickId: Long): Boolean {
        try {
            val roots = listOfNotNull(rootInActiveWindow) + windows.mapNotNull { it.root }
            for (root in roots) {
                val node = findClickableAt(root, x.toInt(), y.toInt())
                if (node != null) {
                    val acted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "click $clickId node ${node.className} acted=$acted")
                    node.recycle()
                    if (acted) return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "click $clickId node failed", e)
        }
        return false
    }

    private fun findClickableAt(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val bounds = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestArea = Int.MAX_VALUE
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            node.getBoundsInScreen(bounds)
            if (bounds.contains(x, y)) {
                if (node.isClickable) {
                    val area = bounds.width() * bounds.height()
                    if (area < bestArea && area > 0) {
                        best?.recycle()
                        best = AccessibilityNodeInfo.obtain(node)
                        bestArea = area
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.addLast(it) }
                }
            }
            if (node !== root && node !== best) node.recycle()
        }
        if (root !== best) root.recycle()
        return best
    }

    private fun dispatchTap(x: Float, y: Float, clickId: Long, onFinished: () -> Unit) {
        var finished = false
        fun finish(result: String, error: Exception? = null) {
            if (finished) return
            finished = true
            if (result == "completed") {
                Log.d(TAG, "click $clickId completed")
            } else {
                Log.w(TAG, "click $clickId $result", error)
            }
            onFinished()
        }

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                finish("completed")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                finish("cancelled by system")
            }
        }
        val accepted = try {
            val cx = x.coerceAtLeast(0f)
            val cy = y.coerceAtLeast(0f)
            Log.d(TAG, "click $clickId dispatch at $cx,$cy")
            // Vertical 1px path reads as tap. Diagonal can read as scroll in WebView.
            val path = Path().apply {
                moveTo(cx, cy)
                lineTo(cx, cy + 1f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, CLICK_DURATION_MS)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), callback, gestureHandler)
        } catch (e: Exception) {
            finish("failed", e)
            return
        }
        if (!accepted) {
            finish("rejected by system")
        }
    }

    fun scrollAt(x: Float, y: Float, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        try {
            val cx = x.coerceAtLeast(0f)
            val cy = y.coerceAtLeast(0f)
            Log.d(TAG, "scroll at $cx,$cy by $dx,$dy")
            val path = Path().apply {
                moveTo(cx, cy)
                lineTo(cx + dx, cy + dy)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, SCROLL_DURATION_MS)
            if (!dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), gestureCallback, gestureHandler)) {
                Log.w(TAG, "scroll rejected by system")
            }
        } catch (e: Exception) {
            Log.w(TAG, "scroll failed", e)
        }
    }

    private fun removeOverlay() {
        overlay?.detach()
        overlay = null
    }

    companion object {
        const val PREFS_NAME = "backer_ball"
        const val KEY_BALL_FX = "ball_fx"
        const val KEY_BALL_FY = "ball_fy"
        const val KEY_BALL_X = "ball_x"
        const val KEY_BALL_Y = "ball_y"

        private const val CLICK_DURATION_MS = 100L
        private const val SCROLL_DURATION_MS = 300L
        private const val TAG = "Backer"

        private var instance: WeakReference<BackerAccessibilityService>? = null

        fun isRunning(): Boolean = instance?.get() != null

        fun isEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${BackerAccessibilityService::class.java.name}"
            val setting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return setting.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
