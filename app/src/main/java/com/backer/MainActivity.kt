package com.backer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {

    private var overlayGranted by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)
    private var batteryIgnored by mutableStateOf(false)

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshStatus()
    }

    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshStatus()
    }

    private val batteryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshStatus()
                }
            }
        )
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OnboardingScreen(
                        overlayGranted = overlayGranted,
                        accessibilityEnabled = accessibilityEnabled,
                        batteryIgnored = batteryIgnored,
                        onGrantOverlay = ::openOverlaySettings,
                        onEnableAccessibility = ::openAccessibilitySettings,
                        onIgnoreBattery = ::openBatterySettings
                    )
                }
            }
        }
    }

    private fun refreshStatus() {
        overlayGranted = Settings.canDrawOverlays(this)
        accessibilityEnabled = BackerAccessibilityService.isEnabled(this)
        val power = getSystemService(PowerManager::class.java)
        batteryIgnored = power?.isIgnoringBatteryOptimizations(packageName) == true
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayLauncher.launch(intent)
    }

    private fun openAccessibilitySettings() {
        accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openBatterySettings() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        batteryLauncher.launch(intent)
    }
}

@Composable
private fun OnboardingScreen(
    overlayGranted: Boolean,
    accessibilityEnabled: Boolean,
    batteryIgnored: Boolean,
    onGrantOverlay: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onIgnoreBattery: () -> Unit
) {
    val ballActive = overlayGranted && accessibilityEnabled
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(text = "Backer", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = if (ballActive) {
                "Ball active"
            } else {
                "Ball inactive"
            },
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Grant overlay permission, then enable the Backer accessibility service. Battery exclusion is optional.",
            style = MaterialTheme.typography.bodyMedium
        )
        StepRow(
            title = "1. Overlay permission",
            status = if (overlayGranted) "Granted" else "Not granted",
            actionLabel = "Grant overlay",
            enabled = !overlayGranted,
            onClick = onGrantOverlay
        )
        StepRow(
            title = "2. Accessibility service",
            status = if (accessibilityEnabled) "Enabled" else "Not enabled",
            actionLabel = "Open accessibility settings",
            enabled = true,
            onClick = onEnableAccessibility
        )
        StepRow(
            title = "3. Battery optimization",
            status = if (batteryIgnored) "Ignored" else "Not ignored (optional)",
            actionLabel = "Ignore battery optimizations",
            enabled = !batteryIgnored,
            onClick = onIgnoreBattery
        )
    }
}

@Composable
private fun StepRow(
    title: String,
    status: String,
    actionLabel: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = status, style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = onClick, enabled = enabled) {
            Text(text = actionLabel)
        }
    }
}
