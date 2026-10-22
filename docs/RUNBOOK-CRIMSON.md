# Runbook — Crimson Mini PC → truck

For a Claude Code session running **on CrimsonMiniPC** (`claude remote-control` in
`~/ram-phoenix-hu`), or for Beau at a terminal there. Crimson has adb, the SDK
(`ANDROID_HOME=~/toolchain/android-sdk`, `JAVA_HOME=~/toolchain/jdk`) and the tailnet.
The truck is reachable only with ACC on; adb is pinned to `100.78.212.9:5555`.

Every step is safe to re-run. Nothing here needs root.

## 0. Recover the stock camera (do this first if the truck has no camera)

```
adb connect 100.78.212.9:5555
adb shell pm enable com.syu.doublecamera
adb shell pm enable com.syu.us
adb shell pm enable com.syu.fourcamera2
adb shell pm disable-user --user 0 com.ramphoenix.camera      # park ours until the next build
adb shell pm uninstall --user 0 com.ramphoenix.launcher       # HOME falls back to the stock launcher
adb reboot
```
After the reboot the stock reverse camera works again (com.syu.ms re-enables the stock
cameras at boot anyway; the `pm enable` lines just don't wait for it).

## 1. Build both apps from main

```
cd ~/ram-phoenix-hu && git fetch origin && git checkout main && git pull --ff-only
cd apps/camera   && ./gradlew assembleDebug 2>&1 | tail -3
cd ../launcher   && ./gradlew assembleDebug 2>&1 | tail -3
```
Launcher reads `MAPS_API_KEY` from the repo-root `local.properties` (gitignored) or the
env var; without it the map card is blank but everything else works.

Expected APKs:
- `apps/camera/app/build/outputs/apk/debug/app-debug.apk`   (v0.3, versionCode 3)
- `apps/launcher/app/build/outputs/apk/debug/app-debug.apk` (v0.4, versionCode 4)

Check what is on the unit before installing: `adb shell dumpsys package com.ramphoenix.camera | grep versionCode`.

## 2. Install the camera (only when ready to test; this takes the stock camera away)

```
adb install -r apps/camera/app/build/outputs/apk/debug/app-debug.apk
adb shell pm enable com.ramphoenix.camera
adb shell pm grant com.ramphoenix.camera android.permission.CAMERA
adb shell pm grant com.ramphoenix.camera android.permission.POST_NOTIFICATIONS
adb shell appops set com.ramphoenix.camera SYSTEM_ALERT_WINDOW allow
adb shell am start -n com.ramphoenix.camera/.MainActivity
adb shell pm disable-user --user 0 com.syu.doublecamera
adb shell pm disable-user --user 0 com.syu.us
adb shell pm disable-user --user 0 com.syu.fourcamera2
```
The last three come back after every reboot (com.syu.ms re-enables them). Re-run them
after a power cycle, or go back to step 0 to hand the camera back to stock.

## 3. Test the camera, capture the evidence

In one terminal, before shifting:
```
adb logcat -c
adb logcat -v time -s PhoenixCam ActivityTaskManager:W CameraService:I > /tmp/reverse.log
```
Then: shift R, wait 5 s, shift out. Twice. Then press the Rear / Front / 360 buttons once each.

What good looks like in the log:
- `U_BACKCAR -> 1` → `service reverse -> true` → camera comes up with **no black flash**
  and the picture is the ECU composite (720x504, its own buttons visible).
- On shifting out: `U_BACKCAR -> 0` and the screen returns to where it was; **no second
  `Camera 14: Opened` line** after the hide (that was the 7 s ghost open, fixed 2026-10-22).
- The UI (selector buttons, sensor strip) should be the mock-up size, not half of it.
  If it is still tiny, the density override did not apply: note it.

Send back: `/tmp/reverse.log`, a photo of the screen in reverse, `adb shell wm density`.

## 4. Install the launcher (does not touch the camera)

```
adb install -r apps/launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.ramphoenix.launcher android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.ramphoenix.launcher android.permission.ACCESS_COARSE_LOCATION
adb shell cmd notification allow_listener com.ramphoenix.launcher/.MediaListenerService
adb shell am start -n com.ramphoenix.launcher/.LauncherActivity     # try it WITHOUT making it HOME
```
Only set it as HOME when it looks right: `adb shell cmd package set-home-activity com.ramphoenix.launcher/.LauncherActivity`.
Undo: `adb shell pm uninstall --user 0 com.ramphoenix.launcher`.

## 5. Test the launcher, capture the evidence

```
adb logcat -c
adb logcat -v time -s PhoenixLauncher > /tmp/launcher.log
```
- Photo of FULL mode and of SPLIT mode (layout icon in the status row toggles).
- Climate card: expect `ipc connected to com.syu.ms/app.ToolkitService` in the log and
  the card to un-dim. Press one stock bezel control (fan +) and note which chip changes.
  Then press AUTO on our card: expect a `climate press 12` line and the AUTO chip to
  flip. If nothing flips, note whether `climate press` was logged at all.
- `adb shell pm list packages | grep syu` and `adb shell dumpsys media_session | head -60`
  while the radio plays (dock targets + whether SYU radio publishes a session).

Send back: both logs, the photos, the two command outputs.

## 6. Known gaps (do not debug these on the truck, they are desk items)
- Front / 360 buttons: view switching is an ECU command we have not found yet.
- Instrument cluster shows dashes except GPS speed: canbus indexes not pinned.
- SPLIT "app in a half" needs `settings put global enable_freeform_support 1` + reboot and is unverified.
