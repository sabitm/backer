package com.backer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The system restores an enabled accessibility service after boot.
 * This receiver does not enable the service. It only re-checks that state.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        BackerAccessibilityService.isEnabled(context.applicationContext)
    }
}
