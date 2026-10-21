# Milestone 1 — Camera: how the stock system works (reverse-engineered)

Target: RAM Phoenix head unit — UNISOC UIS7870 (ums9620), Android 13 `_nosec` (userdebug),
portrait panel 1080×1920. Decompiled from `com.syu.doublecamera` (the stock camera app,
`/odm/app/...doublecamera.apk`, 33.6 MB) with jadx 1.5.0.

## TL;DR — the feed is NOT a raw /dev/video node

Shell sees **no `/dev/video*`** because the camera is owned by the Android **camera HAL
(cameraserver)** and accessed through the standard **Camera2 API**. The stock app's whole
pipeline is ordinary Android, which means we can rebuild it cleanly without touching the
MCU/CAN serial protocol.

## The pipeline, end to end

```
reverse gear
   │  (physical signal on the vehicle bus)
   ▼
MCU  ──serial──►  com.syu.canbus  (the HAL service; keep as-is)
   │
   │  LSEC IPC framework: com.lsec.core.ipc  →  Main.DATA[]  (shared int array)
   ▼
ShareHandler.isMcuReverse()  /  CanbusUtil.bIsMcuReverse   ← the reverse flag
   │        (also a native path: SyuJniNative cmd 32 = GET_BOOT_REVERSE_STATUS,
   │         ToolsJni.cmd_32_get_boot_reverse_status() — used for reverse-at-boot)
   ▼
com.syu.doublecamera opens the back camera via
   com.lsec.core.frame.camera2.MyCamera2   ← standard android.hardware.camera2
   │
   ▼
frames land in an ImageReader  (App_DoubleCamera.mImageReader / mImage2Reader,
   onImageAvailable(ImageReader))
   │
   ▼
rendered as an OpenGL texture through a GLSurfaceView
   (truck/JGLBackSurfaceView + truck/MyRender : GLSurfaceView.Renderer)
   │
   ▼
parking guidelines drawn on top
   (com.syu.back.Module = trajectory geometry from wheelbase/track/cam height+angle,
    com.syu.back.TrackView = the overlay View)
```

Camera frame sizes / IDs come from **system properties**, read via
`App_DoubleCamera.getSystemPropSizeCameraId6()` (note the "CameraId6" — the board numbers
its inputs; back-up cam is one of them). The app also re-exposes the feed as an **AMAP AR
camera service** (`com.autonavi.amapauto.gdarcameraservice.IGDCameraService`) so the AutoNavi
nav app can pull AR frames — not relevant to us, but confirms it's a normal Camera2 surface.

## Why the stock camera feels bad (the stuff we fix)

- **OpenGL guideline renderer is heavy and generic.** `MyRender` + `TrackView` recompute
  Benz/Audi-style curved trajectories every frame. On a portrait panel the preview is
  letterboxed and the overlay math is tuned for landscape sedans, not a lifted truck.
- **Restart-on-error churn:** `App_DoubleCamera` has `waitRestartBackErrorTime` /
  `runnable_startBackRecordDeley` / `restartAppMin()` — it tears down and relaunches the whole
  app on camera glitches, which is the lag/black-flash you see entering reverse.
- **Record coupling:** the back camera is entangled with a DVR record path
  (`startBackRecord`, `CarRecorderBack`), adding startup latency to a feed that should be instant.

## What this means for our build (all standard Android 13, `_nosec` lets us own HOME + perms)

1. **Camera feed** — our app uses `CameraManager.getCameraIdList()` → open the back-cam id →
   `SurfaceTexture`/`ImageReader` → render to a plain `SurfaceView`/`TextureView` (or a lean
   GL shader if we want distortion correction). No MCU work. Instant, no restart loop.
2. **Reverse trigger** — two options, both proven here:
   - Subscribe to the same canbus IPC the stock app uses (`com.lsec.core.ipc` → reverse flag), or
   - Read the reverse state the stock app reads (ShareHandler / JNI cmd 32).
   We keep `com.syu.canbus` running as the HAL and just *listen*; we never decode CAN ourselves.
3. **Guidelines** — reimplement `back.Module`'s geometry (we have the exact formulas) but tuned
   for the truck's real wheelbase/track and portrait aspect, drawn on a lightweight overlay.

## Open items (need the unit awake on adb to finish)

- [ ] Empirically enumerate Camera2 ids on the board: `dumpsys media.camera`,
      `getCameraIdList()`, and which id is the reverse cam (system prop `getSystemPropSizeCameraId6`).
- [ ] Identify the exact reverse flag: watch `com.syu.canbus` + logcat while shifting to reverse,
      confirm the `Main.DATA[]` index / broadcast action.
- [ ] Decompile `com.lsec.core.frame.camera2.MyCamera2` fully (it's in a shared lsec lib) to copy
      its exact open/format/rotation handling as a reference.
- [ ] Decompile `com.syu.rightcamera` + `com.syu.fourcamera2` (side / 360 variants) in case the
      reverse cam id differs per config.

## Artifacts

Pulled + integrity-checked APKs (on Crimson box `~/ram-phoenix-hu/apks/`):
doublecamera 33.6MB · fourcamera2 6.1MB · rightcamera 2.8MB · video 2.7MB · canbus 104.7MB.
Decompiled source (gateway host): `~/.openclaw/workspace/ram-phoenix/out/doublecamera/`.
