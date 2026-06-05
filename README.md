# Logcat Viewer

Android app that analyzes logcat in real-time, grouping logs by app/component into browsable tiles.

## Features

- **Live monitoring** - Clears old logs on start, shows only active apps
- **Tile grid** - Apps displayed as tiles sorted by recent activity
- **Search** - Filter apps or search within specific app logs
- **Tap to browse** - Tap any tile to see detailed logs with search
- **Copy logs** - Tap any log entry to copy full details
- **Dark theme** - Terminal-style green on black UI

## Requirements

- Android 8.0+ (API 26)
- ADB access to grant READ_LOGS permission

## Installation

1. Install the APK:
```bash
adb install logcat-viewer.apk
```

2. Grant READ_LOGS permission via ADB:
```bash
adb shell pm grant com.logcat.viewer android.permission.READ_LOGS
```

3. Launch the app

## Why ADB Permission Grant?

The `READ_LOGS` permission is a **development permission** - Android won't let users grant it from Settings UI. It must be granted via ADB:

```bash
adb shell pm grant com.logcat.viewer android.permission.READ_LOGS
```

This is the same method apps like **MatLog**, **Tasker**, and other logcat readers use on non-rooted devices.

## SELinux Note

On some devices (especially car head units like 8227L/AC8227), SELinux runs in **Permissive** mode. In this case:
- The permission grant still works normally
- SELinux won't block log access even if there are policy edge cases
- Stock phones with SELinux **Enforcing** require the ADB grant

Check your device's SELinux mode:
```bash
adb shell getenforce
```

## How It Works

1. App clears old logcat on launch (`logcat -c`)
2. Starts reading live logs (`logcat -v threadtime`)
3. Parses and groups logs by app/component
4. Updates tile grid every 200ms with most active apps first
5. Tap tile to browse that app's logs with search

## Building

```bash
gradle assembleDebug
```

**Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`

**Signed release**:
```bash
gradle assembleRelease
```

## License

MIT
