# Camera edge alignment — 2026-10-26

Camera 0.3.3 (versionCode 6), branch `codex/camera-alignment-2026-10-26`.
Includes the reliability and working 2019 RAM 3500 TPMS changes.

## What was visible

Beau confirmed reverse activation worked, then highlighted a grey horizontal band
above the factory labels and a strip down the left edge. This made the picture
look displaced down/right over another view. A thin bottom band was visible too.

Live inspection found one active camera client (Phoenix, device 14), one preview
surface and a full-screen 1080×1920 window. The preview's original bounds were
0,0–1080,756 with an uncropped 720×504 source buffer. The unwanted edges are in
that incoming composite; there was no second active camera app underneath it.

## Correction

- Keep capture at the proven native 720×504 size; changing the HAL stream size is
  unnecessary and has previously produced sessions with no delivered frames.
- Display native-buffer rectangle `(16,16)–(710,496)`: 694×480 pixels, excluding
  the measured outer frame and a small interpolation allowance. At 1080 pixels
  wide the visible picture is 747 pixels tall. The feed remains at the top.
- Draw opaque letterboxing below the visible feed. This unit's SurfaceView draws
  beyond its parent's clip; the explicit mask covers the lower overhang while
  retaining the direct Camera2 preview path. The parking graphic draws above it.
- Undo the display crop before forwarding factory OSD touches into the existing
  1024×600 coordinate space. Cropping must not move a button's hit target.
- Recompute layout from the actual root width when it changes. Apply the same
  picture geometry to the optional legacy auxiliary panel. The legacy selector
  remains hidden by default; this change does not establish independent feeds.

Android's [SurfaceView implementation](https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/core/java/android/view/SurfaceView.java)
has separate surface clipping from ordinary view drawing. No hidden surface APIs
or global decoder/sysfs settings are changed by this correction.

## Calibration override

`persist.phoenix.cam.insets` contains **left,top,right,bottom** in native 720×504
buffer pixels. Default: `16,16,10,8`. Each inset must be within 0–32; malformed
values fall back to the measured calibration. Insets affect display and touch
mapping together, and are read when the camera activity is created.

From Crimson, the following selects the original full-buffer display for comparison
(recreate the activity while parked afterward):

```sh
adb -s 192.168.0.225:5555 shell setprop persist.phoenix.cam.insets 0,0,0,0
```

Restore the default by setting `16,16,10,8` and recreating the activity. These are
display settings, not vehicle or camera-module calibration writes.

## Verification

- `apps/camera: ./gradlew testDebugUnitTest assembleDebug` passed: five tests,
  zero failures/errors. Covers full-frame comparison, all four cropped edges,
  factory-button coordinate preservation, alternate window width, drag clamping
  and rejection of invalid calibration.
- Camera 0.3.2 APK and the original screenshot were backed up on Crimson at
  `/home/beauhill/ram-phoenix-backups/camera-alignment-20261026.qz86GO/`.
- Camera 0.3.3 installed on the head unit. Final live image and button results are
  recorded below. A further physical reverse test of this version remains for
  Beau; the reported successful shift was with 0.3.2.
- Final screen capture confirms the top/left strips and bottom line are gone;
  factory labels and the whole view-button row remain visible. The parking
  graphic is unchanged. Evidence on Crimson:
  `/home/beauhill/ram-phoenix-research/camera-alignment-before.png` and
  `camera-alignment-after.png` in the same directory.
- Remote taps in the second/third factory view buttons generated CANBUS 1029
  down/up commands at `(555,542)` / `(687,542)`; a first-button tap generated
  `(420,542)`. No IPC send failures were logged, but the module did not visibly
  switch views in these captures. This is **not** a confirmed physical-button
  pass; check actual touch response while parked. The coordinate transform is
  covered by the unit tests, and no transport command/protocol was changed.
