# Logcat Viewer

Android app that analyzes logcat in real-time, grouping logs by app/component.

## Features

- Real-time logcat monitoring
- Groups logs by app/component
- Search across all apps or within specific app logs
- Dark theme, minimal UI
- Tap app to see detailed logs
- Copy log entries to clipboard

## Requirements

- Android 8.0+ (API 26)
- ADB access to grant READ_LOGS permission

## Installation

1. Install the APK
2. Grant READ_LOGS permission via ADB:
```bash
adb shell pm grant com.logcat.viewer android.permission.READ_LOGS
```
3. Launch the app

## How It Works

Uses the `READ_LOGS` permission to read system logcat. This permission cannot be granted from the UI - only via ADB. Once granted, the app can see all system logs including what media is playing, network activity, app crashes, etc.

## Building

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`
