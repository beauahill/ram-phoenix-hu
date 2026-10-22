# Project status — single source of truth

Updated 2026-10-22. Everything verified on the truck is marked **LIVE**; everything
else is from decompiles or design and says so. Older docs are history; when they
disagree with this page, this page wins.

## Facts about the unit (LIVE 2026-10-21)
- UNISOC UIS7870, Android 13 userdebug `_nosec`, **SELinux permissive**, `adb root` refused.
- Panel 1080x1920 portrait at **160 dpi** (1 dp = 1 px). Both apps render at 320 dpi
  via a per-app density override so the 2x designs come out at the intended size.
- Play Services + Play Store present (Google Maps SDK usable with a key).
- Camera2 exposes 15 virtual devices; **14** and **10** open fine from a normal app.
  14 = the **360 ECU's composite CVBS**, 720x504, with the ECU's own OSD (view
  buttons, zoom, centerline) burned into the pixels. Source resolution is fixed by
  the ECU; nothing on the Android side sharpens it.
- **Reverse trigger = SYU ToolkitService IPC**, `MODULE_MAIN` update 12 (`U_BACKCAR`).
  GPIO uevent → ~1.2 s inside `com.syu.ms` → `backcar : 1` → our app in ~130 ms.
  The sysfs node `fyt_car_reverse` is written by a stock app and is dead with the
  stock cameras disabled.
- Climate: driver temp + produced canbus update code **27**, matching the decoded map.

## Apps

### Camera (`apps/camera`, v0.3)
Done: Camera2 preview of device 14 at its native 720x504, letterboxed; reverse via
IPC with sysfs OR-fallback; boot-in-reverse; foreground service with quiet
notification; background launch via appops grant; visible-activity updates go
through direct calls (no intent churn, no re-open on HIDE); guidelines off by
default (the ECU draws its own).

Open:
- [ ] View switching (rear / overhead / bed) is a **command to the ECU** sent by
      `com.syu.us` over canbus. Decompile `com.syu.us` for the touch → command path
      (likely `cmd(MODULE_CANBUS, …)` or MAIN 81 `U_BACKCAR_360_CAMERA`). Until then the
      Front/360 buttons are placeholders (device 12 is a guess; 10 is the second input).
- [ ] Can the ECU OSD be turned off? If not, our overlay stays off.
- [ ] Faster path: kernel `car-reverse` GPIO uevent fires 1.2 s before `backcar`.
      SELinux is permissive; a `UEventObserver` (hidden API) from our service is
      the experiment. Would cut reverse-to-picture to ~0.2 s.
- [ ] Parking-sensor distances (MAIN 22 `U_BACKCAR_RADAR`?) and steering angle
      (MAIN 41) for the sensor strip / dynamic guidelines.
- [ ] `com.syu.us` must stay disabled or it opens cam 10 and draws over us.

### Launcher (`apps/launcher`, v0.3)
Done: dashboard HOME, dock on top, FULL / SPLIT layouts, Google map (night style,
GPS follow), now-playing (MediaSession), instrument cluster (speed/rpm dials,
traced RAM 3500 top view with tire psi, dual gauges), **climate card** (stock panel
minus rear zone, over the IPC), async dock resolution with package-change refresh.

Open:
- [ ] Climate: verify the read map (codes 10–66) and press/release semantics live.
- [ ] Vehicle data for the cluster: rpm / fuel / coolant / battery / outside / tire
      psi indexes (canbus codes 98–136 raw bits are the likely home). `VehicleData`
      is the seam; `CanbusVehicleData` is the implementation to write.
- [ ] Canbus profile id: `get(MODULE_CANBUS, 1000)` → expect 6357447 / 6422983.
- [ ] Dock: real SYU radio / music / phone package names (`pm list packages syu`).
- [ ] Do SYU radio/music publish a MediaSession?
- [ ] SPLIT "app in a half": confirm freeform behaviour on the unit.
- [ ] Design decision (Beau): keep the dial cluster, or swap that slot for the
      truck/TPMS/maintenance card from `DASHBOARD-DESIGN.md`? Both exist in spirit;
      only one fits in FULL mode with the climate card.

## Next truck session — in order
1. Install both APKs (steps in `CAMERA-MILESTONE-1.md` and `LAUNCHER.md`); grants.
2. `logcat -s PhoenixCam PhoenixLauncher` — confirm density looks right, reverse
   shows the camera from the launcher with no black flash, releasing reverse
   returns to the launcher and cam 14 is not re-opened.
3. Climate: with the launcher's climate card up, press each stock/bezel control
   once and note which chip changes; then press AUTO on our card and watch code 13.
4. `pm list packages syu`, `dumpsys media_session` while the radio plays.
5. Camera: try the view buttons; capture `logcat` + any canbus `cmd` the stock
   `com.syu.us` sends when its OSD buttons are tapped (re-enable it just for that).

## Build
- Camera needs nothing beyond the SDK. Launcher needs `MAPS_API_KEY` (env var or
  repo-root `local.properties`, never committed); an empty key still builds.
- This session's environment cannot reach Google's SDK/Maven hosts; see the
  "build access" note in the latest status message. Both apps are type-checked
  here against the API 34 framework classes on every change.

## Docs map
- `STATUS.md` (this) — truth + punch list.
- `LIVE-2026-10-21.md` — raw measurements from the truck.
- `CANBUS-IPC.md` — the ToolkitService protocol, reverse code, climate map.
- `CAMERA-MILESTONE-1.md`, `LAUNCHER.md` — per-app design + install steps.
- `DASHBOARD-DESIGN.md`, `WISHLIST.md` — design intent / unscheduled ideas.
- `REVIEW-*.md`, `findings.md` — history.
