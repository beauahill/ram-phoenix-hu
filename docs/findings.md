# Head unit recon (verified live over adb)

- **Board:** UNISOC UIS7870 (ums9620), Android 13, `_nosec` userdebug build.
- **Panel:** 1080x1920 portrait.
- **Camera:** no `/dev/video*` nodes -> not V4L2. Feed bound through SYU vendor
  HAL/MCU path (likely a MIPI/analog input a SurfaceView binds to, possibly only
  live while reverse is engaged). **This is what Milestone 1 must decode.**
- **Access:** wireless debugging (Android 11+), connect port is dynamic.

## Milestone 1 (camera) — open questions to answer via decompile
1. How does the stock camera app acquire the video surface? (SurfaceView bound to
   what — a vendor Camera2 HAL id, a custom service, a framebuffer overlay?)
2. What broadcast/intent does reverse-gear fire? (comes from `com.syu.canbus`)
   Our app listens for the same signal.
3. Latency path: where does the stock lag come from (service handoff, decode,
   surface creation) — target instant switch.

## Stock camera stack packages
- com.syu.doublecamera
- com.syu.fourcamera2
- com.syu.rightcamera
- com.syu.video
