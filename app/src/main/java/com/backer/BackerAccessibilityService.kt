package com.backer

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

class BackerAccessibilityService : AccessibilityService() {

    private var overlay: BallOverlay? = null

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

    private fun removeOverlay() {
        overlay?.detach()
        overlay = null
    }

    companion object {
        const val PREFS_NAME = "backer_ball"
        const val KEY_BALL_X = "ball_x"
        const val KEY_BALL_Y = "ball_y"

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
