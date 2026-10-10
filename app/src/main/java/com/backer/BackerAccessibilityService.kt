package com.backer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
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

    fun clickAt(x: Float, y: Float) {
        try {
            val cx = x.coerceAtLeast(0f)
            val cy = y.coerceAtLeast(0f)
            Log.d(TAG, "click at $cx,$cy")
            // A zero-length path has empty bounds and throws. Use a 4px path.
            val path = Path().apply {
                moveTo(cx, cy)
                lineTo(cx + 4f, cy + 4f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, CLICK_DURATION_MS)
            if (!dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), gestureCallback, gestureHandler)) {
                Log.w(TAG, "click rejected by system")
            }
        } catch (e: Exception) {
            Log.w(TAG, "click failed", e)
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
