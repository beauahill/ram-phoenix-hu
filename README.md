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

How we actually talk to it (LIVE 2026-10-21, see `docs/STATUS.md`):
- **Reverse** arrives over the SYU **ToolkitService IPC** (`com.syu.ms`,
  `MODULE_MAIN` update 12 `U_BACKCAR`). The sysfs node `fyt_car_reverse` is written
  by a stock app and is dead once the stock cameras are disabled.
- **Climate, vehicle data, radar, steering** ride the same IPC (`MODULE_CANBUS`
  update codes; write with `cmd(7, 0, {24, button})`). Protocol in `docs/CANBUS-IPC.md`.
- **Camera feed** is Camera2 device 14 = the 360 ECU's composite video (720x504).

We build everything above that:

- `apps/camera`  — **Milestone 1.** Replace the stock camera (worst part of the
  stock platform). Mechanism is mapped; see `docs/CAMERA-MILESTONE-1.md`.
- `apps/launcher` — **dashboard home** (replaces `com.android.launcher6`): CarPlay-style
  widgets — live map + now-playing on top, speed + vehicle sensors below, dock.
  See `docs/LAUNCHER.md`.
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
- **`docs/STATUS.md` — start here: what is proven, what is open, next truck session.**
- `docs/CAMERA-MILESTONE-1.md` — the authoritative camera findings (feed path,
  reverse trigger, button path, 360 system, sysfs control plane, punch list).
- `docs/findings.md` — dated recon log.
- `docs/REVIEW-2026-10-21.md` — desk review of the camera app + findings.
- `docs/LAUNCHER.md` — dashboard launcher: widgets, data sources, install steps.
- `docs/REVIEW-2026-10-21b-camera-launcher.md` — review of the camera fixes + dashboard launcher branch (built both apps; findings C1–C5, L1–L8).

## Wish list
Unscheduled ideas live in `docs/WISHLIST.md` (first entry: a maintenance tracker app for oil / fuel-filter / tire service logs).

- [Dashboard launcher design spec](docs/DASHBOARD-DESIGN.md) — agreed layout + climate cluster (2026-10-21)
