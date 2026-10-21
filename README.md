# ram-phoenix-hu

A replacement Android platform for the RAM "Phoenix" aftermarket head unit.

## Hardware
- **SoC:** UNISOC UIS7870 (ums9620)
- **OS:** Android 13 (`_nosec` / userdebug build)
- **Panel:** 1080x1920, **portrait** (vertical). All UI is portrait-first.
- **Cameras:** standard **Camera2** devices behind the Android cameraserver HAL.
  There is no `/dev/video*` node because the analog inputs (3x TP2850 AHD/TVI
  decoders bridged to MIPI CSI-2) are owned by cameraserver, not exposed raw.
  This is the key insight and it is why we can rebuild the camera in plain
  Android without touching the MCU. See `docs/CAMERA-MILESTONE-1.md`.

## Architecture decision
Keep the vendor **SYU CANbus service (`com.syu.canbus`) as a dumb HAL.**
It speaks a proprietary binary protocol to the MCU for steering-wheel buttons,
climate, reverse-gear signal, and vehicle data. We do NOT reimplement that.

How we actually talk to it (confirmed by decompile, not broadcasts):
- **Reverse / camera state** surfaces as a world-rw (0666) sysfs control plane
  at `/sys/lsecdcamctl/` (`fyt_car_reverse`, `backcar_mirror`, `video_rotation`,
  `video_position`, `video_signal`). Our app polls/sets those directly.
- **Richer vehicle data** (radar distances, steering angle, etc.) rides the
  **LSEC IPC shared array** (`Main.DATA[]` + `U_*` signal ids) that the SYU apps
  publish. That path is the fallback trigger and the source for parking sensors.

We build everything above that:

- `apps/camera`  — **Milestone 1.** Replace the stock camera (worst part of the
  stock platform). Mechanism is mapped; see `docs/CAMERA-MILESTONE-1.md`.
- `apps/launcher` — replacement home launcher (replaces `com.android.launcher6`).
- new car-UI layer on top of the SYU HAL.

## Stock SYU stack (reference)
- `com.syu.canbus`  — CAN<->MCU bridge (KEEP as HAL)
- `com.syu.us`      — surround/360 logic, radar UI, **physical camera button** owner
- `com.syu.carmark` — vehicle/brand profile ("RAM Phoenix")
- `com.syu.carui`   — car UI overlay (replace)
- `com.syu.carlink` — CarPlay/Android Auto bridge
- `com.fyt.screenbutton` — physical-button / overlay window handler
- `com.syu.doublecamera` / `fourcamera2` / `rightcamera` / `com.syu.video` — stock camera stack (replace)
- `com.android.launcher6` — stock launcher (replace)

## Docs
- `docs/CAMERA-MILESTONE-1.md` — the authoritative camera findings (feed path,
  reverse trigger, button path, 360 system, sysfs control plane, punch list).
- `docs/findings.md` — dated recon log.
- `docs/REVIEW-2026-10-21.md` — desk review of the camera app + findings.

## Wish list
Unscheduled ideas live in `docs/WISHLIST.md` (first entry: a maintenance tracker app for oil / fuel-filter / tire service logs).
