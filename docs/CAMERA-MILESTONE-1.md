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

---

## Physical Camera Button + 360 System (captured live 2026-10-20)

Beau's truck has the **factory 360° surround-view system** and a **physical camera button**. Captured the full chain cold via logcat while he pressed the button.

### What the physical button fires

1. **Button press → `com.fyt.screenbutton`** draws an overlay window (it's the button/floating-UI app).
2. **`com.syu.us`** (pid 2565, the surround/ultrasonic app) handles the logic:
   - `refreshFloatCameraBtn` / `isExistPanorama_Float=false`
   - sets `U_CAMERA_MODE=true` on the radar view (`setVisibility_CarRadar`)
3. **TWO camera apps open simultaneously** (this is the 360 pipeline):
   - `com.syu.doublecamera` (pid 17359, tag `HJT`, `MyCamera3`) opens **Camera2 device 14** → HAL `camId=2`, `mMultiCameraMode`, `sprd_3dcalibration_cap_size w=720 h=504` (the ISP 3D-calibration / stitch path)
   - `com.syu.us` (pid 2565, tag `lseccamm`, `MyCamera2`) opens **Camera2 device 10**
   - Launch time ~360ms (`receivePreviewFrame ... Launch Camera Time:360(ms)`)

### Key architecture insight on the 360

The surround stitching happens **below the app layer**, in the Unisoc/Spreadtrum ISP (`Cam3OEMIf`, `sprd_3dcalibration`, `mMultiCameraMode`). The apps bind standard Camera2 surfaces (devices 14 + 10); the composited/calibrated frame comes out of the HAL. So for our own 360 UI we bind the same Camera2 devices — we do **not** have to stitch raw fisheye in-app (unless we want a fully custom bird's-eye, which is a later milestone).

### The control plane — `/sys/lsecdcamctl/` (all world-writable 0666)

Direct sysfs knobs, no IPC/root needed:
- **`fyt_car_reverse`** — reverse flag, `0`→`1` on gear-R (reads 0 at rest) ✅ our reverse trigger
- `backcar_mirror` — mirror the backup image
- `video_rotation`, `video_position`, `video_vertical`, `video_control_position` — feed geometry (the knobs to re-aim for a lifted truck / portrait panel)
- `video_signal`, `video_err`, `video_ntsc_pal` — signal/format state
- `main_video_ic`, `main2_video_ic`, `sub_video_ic` — the video decoder ICs (multi-cam inputs)
- `ori_display_resolution`

### Trigger map for our app (both paths)

| Trigger | Signal to subscribe | Cameras |
|---|---|---|
| Reverse gear | `/sys/lsecdcamctl/fyt_car_reverse` 0→1 (+ `U_BACKCAR` IPC) | dev 14 (back) |
| Physical button / 360 | `U_CAMERA_MODE` IPC via `com.syu.us`; button owned by `com.fyt.screenbutton` | dev 14 + dev 10 |

### Open question for the 360 rebuild

Only two non-SYU custom apps exist: `com.fyt.screenbutton` (button/overlay) and `com.syu.fytgesture` (gesture). There is **no separate AVM/bird's-eye app** — the 360 render lives inside `com.syu.us` + `com.syu.doublecamera`. If we want our own 360 view we either (a) reuse their Camera2 device binding and the ISP-stitched output, or (b) go full-custom bird's-eye from the 4 raw inputs (later milestone, much bigger). Milestone 1 stays: clean single-cam reverse + manual-button view, instant, no restart loop.

## Punch list (remaining / future)

- [ ] **Front camera device ID** — tentatively Camera2 device `12`; confirm live by opening candidates on the unit (3 analog decoders on board). Rear = dev 14 (confirmed), 360 = dev 14 + dev 10 (confirmed).
- [ ] **Parking-sensor data index** — strip is wired but reads a stub; real distances ride canbus IPC via `com.syu.us`. Capture the exact IPC slot live while approaching an object.
- [ ] **Dynamic steering-reactive centerline** (Beau request 2026-10-20) — guidelines that bend as the wheel turns. Stock `back.Module` uses *static* geometry (wheelbase/track/cam-angle); need to (a) static-analyze the decompiled stock camera for whether it subscribes to a live steering-angle signal, and (b) find the steering-angle index on the canbus IPC. Then subscribe (same pipe as the reverse flag) and redraw the trajectory curve live. Almost certainly on the bus given the truck has 360.
- [ ] **Live color/exposure tuning** — stock feed is washed out (interior reflection); tune via `/sys/lsecdcamctl/` signal knobs on the real cameras.

---

## Review fixes applied — 2026-10-21 (code-only, no truck)

Addressed the no-truck items from `docs/REVIEW-2026-10-21.md`. Verified: builds
clean, new symbols present in the APK dex.

Done:
- **Stream size (#1):** `CameraController.applyFixedSize()` queries
  `StreamConfigurationMap` and calls `holder.setFixedSize()` before opening, so
  the session no longer asks the analog HAL for a 1080x1920 portrait buffer.
- **Aspect ratio (#2):** rear/front feed + guideline overlay now live in a
  runtime-sized 16:9 `feed_box` (width*9/16), not a stretched portrait surface.
  Aux (360) surface sized the same way at runtime (fixes the dp/density issue #5).
- **Stale-open guard (#3):** per-open generation token; late callbacks from a
  superseded open close their device and bow out.
- **Sensor ticker leak (#4):** ticker is a held Runnable, cancelled in
  onPause/onDestroy.
- **Reverse-from-anywhere (2A):** new `ReverseService` foreground service polls
  the reverse flag independent of what is on screen and brings up the camera via
  full-screen-intent (sanctioned background-launch) + direct start. Started at
  boot by `ReverseReceiver`.
- **Return on disengage (2B):** reverse-initiated launches `moveTaskToBack()` on
  1→0; manual opens stay.
- **Manifest (#6):** `showWhenLocked` replaces deprecated `showOnLockScreen`;
  service + FOREGROUND_SERVICE/POST_NOTIFICATIONS declared.
- **volatile (#9):** `ReverseMonitor.lastState` is volatile.
- **Docs:** README + findings.md corrected (Camera2 + sysfs + LSEC IPC, not
  "proprietary MCU path / broadcasts"); `com.syu.us` added; TP2850 fact recorded.

Still TRUCK-GATED (must confirm on the unit before trusting — unchanged):
1. Does `fyt_car_reverse` still flip with stock camera apps disabled? (1A)
2. Are Camera2 ids 14/10 visible to a non-platform app? `dumpsys media.camera`. (1B)
3. What does device 10 show? (1C)
4. `getenforce`, `wm density`, `wm size`; real advertised stream sizes.
5. First on-screen frame; then pre-grant CAMERA, disable `com.syu.doublecamera`.
6. Front cam id (tentative 12); parking-sensor LSEC index; steering-angle index
   for wheel-reactive guidelines (punch-list item).
