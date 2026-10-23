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
        // Gesture-first won testing: Chrome fully, FB WebView after one scroll.
        // Duration differs by content. Web gets a longer press.
        val web = isWebViewPoint(x, y)
        Log.d(TAG, "click $clickId path=${if (web) "web" else "native"}")
        dispatchTap(x, y, clickId, if (web) WEB_CLICK_DURATION_MS else CLICK_DURATION_MS, onFinished)
    }

    private fun isWebViewPoint(x: Float, y: Float): Boolean {
        val winInfos = try {
            windows
        } catch (e: Exception) {
            Log.w(TAG, "webview check failed", e)
            return false
        }
        // Window infos and node roots come from pools. Recycle every one on all
        // paths so heavy tapping does not churn binder objects for days.
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (info in winInfos) {
                try {
                    info.root?.let { roots.add(it) }
                } catch (_: Exception) {
                }
            }
            try {
                rootInActiveWindow?.let { roots.add(it) }
            } catch (_: Exception) {
            }
            for (root in roots) {
                if (subtreeHasWebView(root, x.toInt(), y.toInt())) return true
            }
            return false
        } catch (e: Exception) {
            Log.w(TAG, "webview check failed", e)
            return false
        } finally {
            for (root in roots) {
                try {
                    root.recycle()
                } catch (_: Exception) {
                }
            }
            for (info in winInfos) {
                try {
                    info.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun subtreeHasWebView(root: AccessibilityNodeInfo, x: Int, y: Int): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val bounds = Rect()
        var found = false
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            node.getBoundsInScreen(bounds)
            val inside = bounds.contains(x, y)
            val cls = node.className?.toString() ?: ""
            if (inside && cls.contains("WebView", ignoreCase = true)) {
                found = true
            }
            // Only descend into branches that contain the point.
            if (inside && !found) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.addLast(it) }
                }
            }
            if (node !== root) node.recycle()
            if (found) {
                while (stack.isNotEmpty()) {
                    val rest = stack.removeLast()
                    if (rest !== root) rest.recycle()
                }
                return true
            }
        }
        return false
    }

    private fun dispatchTap(x: Float, y: Float, clickId: Long, durationMs: Long, onFinished: () -> Unit) {
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
            Log.d(TAG, "click $clickId dispatch at $cx,$cy duration=$durationMs")
            // Vertical 2px path reads as tap. Diagonal can read as scroll in WebView.
            val path = Path().apply {
                moveTo(cx, cy)
                lineTo(cx, cy + 2f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), callback, gestureHandler)
        } catch (e: Exception) {
            finish("failed", e)
            return
        }
        if (!accepted) {
            finish("rejected by system")
        }
    }

    fun scrollAt(x: Float, y: Float, dx: Float, dy: Float, onFinished: () -> Unit = {}) {
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
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onFinished()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onFinished()
                }
            }
            if (!dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), callback, gestureHandler)) {
                Log.w(TAG, "scroll rejected by system")
                onFinished()
            }
        } catch (e: Exception) {
            Log.w(TAG, "scroll failed", e)
            onFinished()
        }
    }

    // Cursor press fires on lift so the finger is up and the system cannot cancel
    // our touch stream. No move means fixed long-press. Move means one swipe.
    fun longPressAt(x: Float, y: Float, pressId: Long, onFinished: () -> Unit) {
        try {
            val cx = x.coerceAtLeast(0f)
            val cy = y.coerceAtLeast(0f)
            Log.d(TAG, "longpress $pressId at $cx,$cy")
            // Same 2px shape as taps. Only the duration differs.
            val path = Path().apply {
                moveTo(cx, cy)
                lineTo(cx, cy + 2f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, LONG_PRESS_DURATION_MS)
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "longpress $pressId completed")
                    onFinished()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "longpress $pressId cancelled by system")
                    onFinished()
                }
            }
            if (!dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), callback, gestureHandler)) {
                Log.w(TAG, "longpress $pressId rejected by system")
                onFinished()
            }
        } catch (e: Exception) {
            Log.w(TAG, "longpress $pressId failed", e)
            onFinished()
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
        private const val WEB_CLICK_DURATION_MS = 150L
        private const val SCROLL_DURATION_MS = 300L
        private const val LONG_PRESS_DURATION_MS = 800L
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
