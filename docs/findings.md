# Head unit recon log

> The authoritative camera write-up is `docs/CAMERA-MILESTONE-1.md`. This file is
> a dated recon log. Earlier entries here were superseded by the live decompile
> on 2026-10-20 and are corrected below.

## 2026-10-20 — baseline (verified live over adb)
- **Board:** UNISOC UIS7870 (ums9620), Android 13, `_nosec` userdebug build.
- **Panel:** 1080x1920 portrait.
- **Access:** wireless debugging (Android 11+), connect port is dynamic (changes
  on reboot / wifi cycle; re-pair when the trusted key is dropped).

## 2026-10-20 — camera mechanism (CORRECTS the earlier guess)
Earlier this file said the camera was "not V4L2, bound through a proprietary
vendor/MCU path." The decompile disproved that:

- The cameras are **standard Camera2 devices** behind cameraserver. No
  `/dev/video*` because the analog inputs (3x TP2850 decoders → MIPI CSI-2) are
  owned by cameraserver, not exposed raw.
- Backup cam = Camera2 device **"14"**; `com.syu.us` opens device **"10"**
  alongside it for the 360 path.
- Reverse trigger surfaces at **`/sys/lsecdcamctl/fyt_car_reverse`** (0→1 on
  gear-R, world-rw 0666). Vehicle/radar data rides the **LSEC IPC `Main.DATA[]`**
  array, not an Android broadcast.

### Open questions — status
1. ~~How does the stock camera acquire the surface?~~ **ANSWERED:** Camera2
   (`com.lsec.core.frame.camera2.MyCamera2`) → ImageReader → GLSurfaceView.
2. ~~What does reverse-gear fire?~~ **ANSWERED:** sysfs `fyt_car_reverse` +
   LSEC IPC `U_BACKCAR`; boot-reverse via JNI cmd 32.
3. Latency path: stock lag is the restart-on-error teardown
   (`restartAppMin`/`waitRestartBackErrorTime`) + DVR coupling + heavy GL
   guideline renderer. Our app re-opens the device instead of restarting.

### Still truck-gated (see milestone doc punch list)
- Does `fyt_car_reverse` keep flipping with the stock camera apps disabled?
- Are Camera2 ids 14/10 visible to a 3rd-party (non-platform) app?
- What does device 10 actually show (overhead vs. composite)?
- Front camera id (tentatively 12), real stream sizes, `getenforce`, `wm density`.

## Stock camera stack packages
- com.syu.doublecamera
- com.syu.fourcamera2
- com.syu.rightcamera
- com.syu.video
- com.syu.us (surround/360 + radar + button)
