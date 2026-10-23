# Backer

Floating ball for Android that sends system actions. One ball, no notification, no extra screens.

## Gestures

- Tap: Back
- Swipe left: Home
- Swipe right: Lock screen
- Swipe up: Recents
- Swipe down: Notifications
- Single-tap-hold and move: move the ball
- Double-tap: cursor mouse mode (tap sends click at cursor)
- Double-tap-hold: long-press when still, swipe when moved
- Double-tap-hold in normal mode: volume drive (up raises, down lowers)
- Triple-tap: action menu (power menu, screenshot)

## Requirements

- Android 10 or higher
- Accessibility permission for Backer (runs the ball without a notification)
- arm64 device, sideloaded APK

## Build

Uses Nix for the toolchain. Signing uses environment variables.

```
nix develop
export BACKER_KS_PATH="$HOME/.config/backer-release.jks"
export BACKER_KS_PASS="changeit"
export BACKER_KEY_ALIAS="backer"
export BACKER_KEY_PASS="changeit"
./gradlew :app:assembleRelease
```

APK output: `app/build/outputs/apk/release/app-release.apk`

Verify signature:

```
$HOME/.cache/backer/android-sdk/build-tools/36.0.0/apksigner verify app/build/outputs/apk/release/app-release.apk
```

## Notes

- Cursor clicks dim the cursor overlay first so the system trusts the gesture.
- Long-press and swipe in cursor mode fire on finger lift. Injection during touch gets cancelled by the system.
- Fresh memory baseline is about 26 MB TOTAL PSS (`adb shell dumpsys meminfo com.backer`).
