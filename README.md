# ram-phoenix-hu

A replacement Android platform for the RAM "Phoenix" aftermarket head unit.

## Hardware
- **SoC:** UNISOC UIS7870 (ums9620)
- **OS:** Android 13 (`_nosec` / userdebug build)
- **Panel:** 1080x1920, **portrait** (vertical). All UI is portrait-first.
- **No `/dev/video*` nodes** — camera is NOT a standard V4L2 feed; it comes
  through SYU's proprietary vendor/MCU path.

## Architecture decision
Keep the vendor **SYU CANbus service (`com.syu.canbus`) as a dumb HAL.**
It speaks a proprietary binary protocol to the MCU for steering-wheel buttons,
climate, reverse-gear signal, and vehicle data. We do NOT reimplement that.
We listen to its broadcasts/intents and build everything above it:

- `apps/camera`  — **Milestone 1.** Replace the stock camera (worst part of the
  stock platform). Decode how the feed is acquired + what reverse-gear broadcast
  triggers it, then own the whole experience.
- `apps/launcher` — replacement home launcher (replaces `com.android.launcher6`).
- new car-UI layer on top of the SYU HAL.

## Stock SYU stack (reference)
- `com.syu.canbus`  — CAN<->MCU bridge (KEEP as HAL)
- `com.syu.carmark` — vehicle/brand profile ("RAM Phoenix")
- `com.syu.carui`   — car UI overlay (replace)
- `com.syu.carlink` — CarPlay/Android Auto bridge
- `com.syu.doublecamera` / `fourcamera2` / `rightcamera` / `com.syu.video` — stock camera stack (replace)
- `com.android.launcher6` — stock launcher (replace)
