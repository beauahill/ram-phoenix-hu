# ram-phoenix-hu

A replacement Android software stack for the RAM "Phoenix" aftermarket head unit
(UNISOC UIS7870, Android 13, portrait 1080×1920). The stock SYU software is slow and
ugly, and the backup camera is the worst part of it: 1–2 s of black flash and a
restart loop every time you shift into reverse. This repo replaces the launcher, the
camera, and the car UI while keeping the vendor's CAN/MCU bridge as a dumb hardware
abstraction layer.

> **Status (2026-10-25):** see `docs/LIVE-2026-10-25.md` for the latest live session; the summary below predates it.
>
> **TPMS resolved (2026-10-26):** the 2019 RAM 3500 SRW returns all four tire
> pressures over OBD at request/reply IDs **740/4C0**, with custom ISO-TP flow
> control. The earlier gateway-blocking conclusion was incorrect for these reads.
> See [the verified profile and live values](docs/TPMS-2019-RAM3500.md).
>
> **Status (2026-10-22):** Camera Milestone 1 is installed on the truck and the
> reverse trigger is **proven live** (two shifts, ~130 ms from signal to first frame on
> our side). Launcher v0.4 (dashboard, Google Maps, instrument cluster, climate card)
> builds but is not yet the default HOME. **All work is on `main`** (the feature branch
> was fast-forwarded in on 2026-10-22); do not push to the old branch.
> Both apps now render at 320 dpi via a per-app density override: the panel is 160 dpi
> and every layout was designed at 2x, so before this everything drew at half size.
>
> This README is the consolidated notebook. Everything below was either captured live
> on the unit over adb or read out of jadx decompiles of the stock APKs; anything
> still unverified is marked **VERIFY** or lives in the punch list.

---

## 1. Hardware and access

| | |
|---|---|
| SoC | UNISOC UIS7870 (`ums9620`) |
| OS | Android 13 (`TP1A.220624.014`), build `uis7870sc_2h10_nosec` (userdebug, non-secure) |
| SELinux | **Permissive** (avc denials are logged, not enforced) |
| Panel | **1080×1920 portrait**, 160 dpi (mdpi, 1 dp = 1 px). All UI is portrait-first. Our apps override to 320 dpi (`HwConfig.UI_DPI`, `Ui.DPI`) so their 2x layouts render at the intended size. |
| Root | `adb root` is **refused** ("production build" despite `_nosec`). `/data/data/com.syu.*` is 0700 and unreadable from shell. We do everything as an untrusted app + `pm`/`appops`/`settings` from adb shell. |
| Play Services | `com.google.android.gms` + Play Store are present (so Google Maps SDK works). |
| Cameras | 15 Camera2 devices (0–14) reported by cameraserver. **Device 14 = backup feed, device 10 = 360 aux.** Both openable by an untrusted app. Analog inputs are 3× TP2850 AHD/TVI decoders bridged to MIPI CSI-2, owned by cameraserver, so there is no `/dev/video*`. |
| Network | Unit is on the tailnet at `100.78.212.9` (hostname `uis7870sc-2h10-nosec`). |

### adb

- **adb is pinned to TCP 5555** on the unit (`persist.adb.tcp.port=5555`) and this
  **survives reboot** (verified 2026-10-21 16:11). Connect with
  `adb connect 100.78.212.9:5555`. No more wireless-debug pairing dance.
- Before the pin: wireless debugging rotated the port on every reboot / wifi cycle and
  dropped the trusted key on cold start, which meant re-pairing from the unit's screen
  every time. Don't go back to that.
- Tailscale on the unit does not reconnect itself after reboot unless the VPN is
  always-on. Set `settings put secure always_on_vpn_app com.tailscale.ipn`
  (applied 2026-10-21, **VERIFY** on next reboot). Until then, after a power cycle Beau
  has to tap Connect in the Tailscale app before the unit is reachable.
- The truck is only reachable when it has power (ACC on). "100 % packet loss" means
  the truck is off, not that something broke.

### Build box

Everything builds and pushes from **CrimsonMiniPC** (`100.107.169.36`,
`~/ram-phoenix-hu`), which has the Android SDK + JDK toolchain
(`JAVA_HOME=~/toolchain/jdk`, `ANDROID_HOME=~/toolchain/android-sdk`) and the GitHub
deploy key (`gh-ram-phoenix` ssh alias). Pulled stock APKs live in `~/ram-phoenix-hu/apks/`
(gitignored). jadx 1.5.0 decompiles of `doublecamera`, `canbus`, `air`, `carui`, `ms`
are on the gateway host at `~/.openclaw/workspace/rea-ramphoenix/jadx-out/`.

---

## 2. Architecture decision

**Keep the vendor CAN/MCU layer. Replace everything above it. Never decode CAN ourselves.**

```
   ┌──────────────────────────────────────────────────────┐
   │  OUR STACK (this repo)                                │
   │   apps/camera    com.ramphoenix.camera    ← M1, LIVE   │
   │   apps/launcher  com.ramphoenix.launcher  ← M2, builds │
   │   car UI / climate strip (inside the launcher)         │
   └──────────────────────▲───────────────────────────────┘
                          │ Binder IPC: com.syu.ms/app.ToolkitService
   ┌──────────────────────┴───────────────────────────────┐
   │  VENDOR HAL (keep as-is)                              │
   │   com.syu.ms      ToolkitService, GPIO/uevent, backcar │
   │   com.syu.canbus  CAN <-> MCU, vehicle profile         │
   │   com.syu.steer   steering-wheel buttons               │
   └──────────────────────▲───────────────────────────────┘
                          │ proprietary serial
                    ┌─────┴─────┐
                    │    MCU    │  CAN bus → truck
                    └───────────┘
```

Why: `com.syu.canbus` + `com.syu.ms` already decode the proprietary MCU protocol
(steering buttons, climate, reverse, vehicle data) and expose it over a Binder service
every stock app binds to. Re-doing that is months of work for zero visible payoff.

### How we actually talk to the HAL (this changed twice, here is the final answer)

1. ~~Android broadcasts~~ — the first guess. Wrong; the SYU apps don't broadcast.
2. ~~`/sys/lsecdcamctl/fyt_car_reverse` sysfs flag~~ — world-rw and it does read 0/1,
   but it is **written by the stock camera apps, not the kernel**. With the stock
   cameras disabled it never moves (7,154 samples at 20 Hz across two shifts into R,
   all 0). Dead as a trigger. Still kept as an OR fallback in `ReverseMonitor`.
3. **`com.syu.ms/app.ToolkitService` Binder IPC** — the real thing. See §4.

---

## 3. What is in the repo

| Path | What |
|---|---|
| `apps/camera/` | `com.ramphoenix.camera` v0.3 — Milestone 1. Rear / front / 360 modes, view selector, Camera2 controller with reopen-on-error (no restart loop), `ReverseService` + `ReverseMonitor` (IPC subscription, sysfs fallback, boot receiver), 8-zone `ParkingSensorView`, guidelines (off by default: the ECU draws its own), sysfs geometry knobs. The service drives a visible activity by direct call, never by intent (intents to the resumed activity re-opened cam 14 on every shift). |
| `apps/launcher/` | `com.ramphoenix.launcher` v0.4 — portrait dashboard HOME. Status row + **dock on top**, Google Maps SDK map (night style, heading-up follow), MediaSession now-playing, instrument cluster (`VehicleCardView`, traced RAM outline, currently on `VehicleData.STUB`), **climate card** (`ClimateWidget`: stock panel minus rear zone, reads CANBUS 10–66, sends `{24, id}`), FULL / SPLIT pane layouts (climate is also a pane option). Dock targets resolve off-thread and refresh on package changes. |
| `SyuIpc.java` (in both apps) | Hand-written Binder client for the SYU ToolkitService (no AIDL, no SYU jars). Two identical copies; keep them in sync. |
| `tools/gen_truck.py` | Traces the truck outline for the cluster from a locally held blueprint (the blueprint is licensed and **not** in the repo). Writes `TruckOutline.java`; never hand-edit the output. |
| `docs/` | Everything below in long form. See §9 for the index. |

---

## 4. The SYU ToolkitService IPC (the one API that matters)

Source: decompiles of `com.syu.doublecamera`, `com.syu.canbus`, `com.syu.air`; reverse
path confirmed live. Full detail with every code: `docs/CANBUS-IPC.md`.

- Bind: `Intent().setClassName("com.syu.ms", "app.ToolkitService")`. Manifest needs
  `<queries><package android:name="com.syu.ms"/></queries>` or `bindService` fails
  silently on Android 11+.
- `IRemoteToolkit` txn 1 `getRemoteModule(moduleCode) -> IBinder`
- `IRemoteModule` txn 1 `cmd(code, int[], float[], String[])` (oneway), txn 2 `get(code, …)`,
  txn 3 `register(IModuleCallback, updateCode, 1)`, txn 4 `unregister`
- `IModuleCallback` txn 1 `update(code, int[], float[], String[])`
- Module codes: **MAIN=0**, RADIO=1, BT=2, SOUND=4, **CANBUS=7**, DVR=9, STEER=10, OBD=12,
  GSENSOR=17, SENSOR=19, ADAS=20.

### Reverse (MODULE_MAIN)

- Register on MAIN, update code **12 = `U_BACKCAR`**, `ints[0]` 1 = engaged, 0 = out.
- Other MAIN codes the stock camera uses: 41 steer angle (`ints[1]`=SCALE_MAX,
  `ints[2]`=ANGLE_MAX, `ints[0]` **VERIFY**), 50 ACC on, 22 `U_BACKCAR_RADAR`,
  23 track-enable, 24 mirror, 81 360-camera.
- Physical camera button = `U_CAMERA_MODE`, handled by `com.syu.us`, drawn by
  `com.fyt.screenbutton`.

### Vehicle profile (MODULE_CANBUS)

- `DATA[1000]` = `U_CANBUS_ID`. The RAM-with-climate profile is
  `CAR_455_LZ_Dodge_RAM1500` = **6357447** (or `_H` = 6422983). **VERIFY** via
  `get(7, 1000)` from our app; not readable from shell (no root).

### Climate, read side (MODULE_CANBUS update codes, RAM 1500 profile)

10 power · 11 A/C · 12 recirc · 13 AUTO · 16 rear defrost · 18/19/20 vent up/body/foot ·
21 fan 0–8 · **27 driver temp** (confirmed live) · 28 passenger temp · 29/30 seat heat
D/P · 31/32 seat vent D/P · 37 temp unit (1 = °F) · 53 A/C MAX · 62 SYNC (= DUAL) ·
65 front defrost · 66 heated steering · 0–5 doors · 98–136 raw car-info bits (TPMS /
odometer probably in here, **map live**) · 137–144 EQ.

Temp encoding: °F = `(v*9+320)/10` when `persist.fyt.temperature=1`, else °C = `v/2`;
−1 NO, −2 LO, −3 HI.

### Climate, write side

`cmd(7, 0, {24, BUTTON})` on press, `cmd(7, 0, {24})` on release. Button ids:
1/2 driver temp ± · 3/4 passenger temp ± · 5/6 fan ± · 7 power · 8 SYNC/DUAL · 9 A/C MAX ·
10 A/C · 11 recirc · 12 AUTO · 13 front defrost · 14 rear defrost · 15–18 vent modes
(face, face+feet, feet, feet+defrost) · 20/21 passenger seat heat/vent · 22/23 driver seat
heat/vent · 24 heated wheel. **VERIFY** whether press alone toggles or release is required
(send `{24, 12}` from our app, watch code 13 flip). Seat/defrost work on accessory power;
temp/fan/mode need the HVAC module awake (engine running).

---

## 5. What has been proven on the truck

### Reverse trigger via IPC (2026-10-21 16:01, two shifts, full logcat)

| t (shift 1) | event | who |
|---|---|---|
| 16:01:52.593 | kernel uevent `GPIO_NAME=car-reverse GPIO_STATE=on` | logged by `com.syu.ms` |
| 16:01:53.855 | `HandlerMain.backcar(1)` → `backcar : 1` | `com.syu.ms` |
| 16:01:53.857 | `U_BACKCAR -> 1` | **our ReverseService, 2 ms later** |
| 16:01:53.863 | `START com.ramphoenix.camera.SHOW` | ActivityTaskManager |
| 16:01:53.956 | `Camera 14: Opened` by `com.ramphoenix.camera` | cameraserver |
| 16:01:59.200 / .203 | GPIO over → `U_BACKCAR -> 0` → HIDE, `moveTaskToBack` | kernel / us |

- Our end-to-end (IPC → activity → first 720×504 NV21 frame) is **~100–130 ms**.
- The **1.2–1.3 s between the GPIO uevent and `backcar : 1` is entirely inside
  `com.syu.ms`** (`jni_exe_cmd_get_boot_reverse_status`, TP2850 chip signal checks). The
  stock camera eats the same delay; we add none.
- `com.syu.ms` (pid 30301) owns the GPIO uevent observer, the chip signal poll and the
  ToolkitService. It is the single source of truth for reverse.
- Camera 14 is released on every HIDE (`dumpsys media.camera`, no leak). The ~7 s
  ghost re-open after HIDE was the SHOW/HIDE intents being delivered to the resumed
  activity (pause → newIntent → resume closes and re-opens the camera); fixed on
  2026-10-22 by driving a visible activity through direct calls. **VERIFY** live.

### Camera feed

- First light on device 14 was 2026-10-21 08:30 (`docs/live/2026-10-21-dev14-first-light.png`).
  Stream is **720×504 NV21**; `CameraController` picks the native per-cam size and
  calls `holder.setFixedSize()` on the main thread before opening.
- **Device 14 is not a raw camera. It is the composited CVBS output of the factory 360
  ECU**, OSD and all (view buttons, zoom, X, "Adjust Centerline", dashed centerline are
  burned into the pixels). Consequences:
  - Resolution ceiling is the ECU's analog output (~720×480). We fix lag, black flash
    and layout, not sharpness.
  - Front / rear / 360 view switching is a **command to the ECU**, not a different Camera2
    id. Stock `com.syu.us` forwards OSD-button touches to the ECU over canbus. That
    write path is the next RE target. "Front cam = device 12" is now doubtful.
  - Our static guidelines duplicate the ECU's own; hide ours or find the ECU command
    that disables its OSD.
- The 360 stitching happens in the Unisoc ISP (`sprd_3dcalibration`, `mMultiCameraMode`)
  below the app layer; stock opens dev 14 + dev 10 together. There is no separate
  AVM app, the 360 render lives in `com.syu.us` + `com.syu.doublecamera`.

### Camera picture quality (desk analysis 2026-10-23, Beau confirmed the two facts)

- **The on-screen buttons in the camera feed are the factory Ram UI** (rear, bird's-eye,
  bed camera, zoom, Adjust Centerline). The truck's own camera module draws them into its
  video; the factory radio showed the same picture.
- **The truck's camera lead plugs into the red FAKRA socket on the head unit's lower board**,
  not the yellow RCA "Camera video in" leads.
- By the time it reaches the decoder the signal is standard-definition composite: the HAL
  marks device 14 as 720x504 deinterlaced, and the capture shows composite cross-colour
  (rainbow fringes on "Adjust Centerline").
- **Truck: factory 12" Uconnect + 360 (Beau, 2026-10-23).** On these trucks the surround
  cameras feed the CVPM (central vision processing module), and the CVPM reaches the radio
  over a single FAKRA coax (owner reports, not FCA service docs). That coax is what is in
  our red FAKRA socket.
- **Most likely analog, so the factory radio got this same SD signal.** Reasoning: the
  decoder on our side sees an interlaced 720x504 composite channel with cross-colour; a
  vendor that had deserialized a digital link would feed the SoC's MIPI input directly
  rather than re-encode to composite. **VERIFY** Sunday: decoder nodes should report a
  CVBS/NTSC signal on that input.
- Consequence: the factory radio's better picture came from better decoding and display
  processing of the same signal. Matching it is a software and decoder-tuning job, and the
  factory picture is a realistic target.
- Either way, software recovers a lot. Measured on the first-light frame: the picture uses
  only luma 57-168 of 0-255, carries captured blanking on the edges, and the old build
  squashed it ~20 % vertically. Cropping, aspect, levels and light sharpening already look
  clearly better on a screenshot (desk preview, not in the app yet).

Plan for the next Crimson session (in order):
1. `adb shell 'for f in /sys/lsecdcamctl/*; do echo "$f: $(cat $f 2>&1)"; done'`
   (main/main2/sub video IC, NTSC/PAL, signal state; any brightness/contrast knobs).
2. Stock settings: check for camera brightness / contrast / saturation options.
3. Add a GPU processing stage to `apps/camera` (SurfaceTexture -> GLES shader: crop
   blanking, correct aspect, black-level/contrast, mild sharpen), parameters as
   `persist.phoenix.cam.*` properties so they can be tuned live with `setprop`, then tune
   against `adb exec-out screencap` frames.

### Climate

- Driver temp + on the stock strip produced exactly one canbus update: code **27**
  `U_AIR_TEMP_LEFT`. Table in §4 confirmed for that code. Send-side semantics still
  need our own test.

### Boot behaviour (reboot test 2026-10-21 16:10)

- adb 5555 pin survived. Unit booted in ~60–90 s but sat off the tailnet until Beau
  tapped Connect (hence the always-on VPN setting above).
- **Stock cameras come back at every boot.** `com.syu.ms` (uid 1000) re-enables
  `com.syu.us` and `com.syu.doublecamera` at startup (`a.i.a(pkg, flag)` → state 2/1;
  policy in `u0.i.d3()`; `g.m.i()` enables doublecamera unless
  `persist.syu.camera360 == 5`, it is 0). From shell only `pm disable-user` (state 3)
  is allowed and it gets flipped back each boot. There is a persistent disabled-list
  store (key 1022, `n0.d4.d3(jsonArray)`) that disables arbitrary packages; the IPC
  entry command for it is **not identified yet**. Until then: re-run the
  `disable-user` lines after every boot, or have our own boot receiver do it.
- Our camera app's `ReverseService` came up on its own at boot (BOOT_COMPLETED works).
- Default HOME is still `com.android.launcher6` by Beau's call; our launcher is
  installed but not set as home.

---

## 6. Build and install

Build (on Crimson):

```
cd ~/ram-phoenix-hu/apps/camera   && ./gradlew assembleDebug
cd ~/ram-phoenix-hu/apps/launcher && ./gradlew assembleDebug
```

Launcher needs `MAPS_API_KEY=…` in the repo-root `local.properties` (gitignored,
injected via `manifestPlaceholders`, never committed, never in chat). An empty key
still builds; the map card just renders blank.

Camera:

```
adb connect 100.78.212.9:5555
adb install -r apps/camera/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.ramphoenix.camera android.permission.CAMERA
adb shell pm grant com.ramphoenix.camera android.permission.POST_NOTIFICATIONS
adb shell appops set com.ramphoenix.camera SYSTEM_ALERT_WINDOW allow   # background launch from the service
adb shell am start -n com.ramphoenix.camera/.MainActivity               # first launch arms ReverseService + BOOT_COMPLETED
adb shell pm disable-user --user 0 com.syu.doublecamera                 # stock app fights for cam 14
adb shell pm disable-user --user 0 com.syu.us                           # its 360 overlay covers us on reverse
adb shell pm disable-user --user 0 com.syu.fourcamera2
```

Watch it: `adb logcat -s PhoenixCam ActivityTaskManager:W` while shifting into R.
Expect `U_BACKCAR -> 1` then `service reverse -> true`. A line containing
"Background activity start" means the appops grant is missing.

Launcher:

```
adb install -r apps/launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.ramphoenix.launcher android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.ramphoenix.launcher android.permission.ACCESS_COARSE_LOCATION
adb shell cmd notification allow_listener com.ramphoenix.launcher/.MediaListenerService
adb shell cmd package set-home-activity com.ramphoenix.launcher/.LauncherActivity   # only when Beau says so
# SPLIT "app in a half" needs freeform (unverified on this unit):
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
```

---

## 7. Milestones

1. **Camera** — SHIPPED v0.1 → reviewed → fixes applied → IPC trigger → **proven live**.
   Remaining work is polish + the ECU view-switch command (see punch list).
2. **Launcher** — v0.4 on `main`. Spec is `docs/DASHBOARD-DESIGN.md`
   (dock on top, map, now-playing, truck card with TPMS/odo/maintenance-due,
   climate cluster = stock panel minus REAR-zone controls). Delta plan: (1) Google Maps
   swap **done**; (2) climate card wired to `SyuIpc` CANBUS reads/writes **done, needs
   live verification**; (3) speed/RPM dials **dropped** (Beau, 2026-10-25): the truck card keeps the traced
   truck, tire psi and the two dual gauges; (4)
   maintenance-due slot in the truck card.
3. **Car UI** — folds into the launcher climate strip; no separate app planned.
4. **Maintenance tracker** (`apps/maintenance`, wish list) — oil, both Cummins fuel
   filters, tires, DEF, etc.; intervals in miles/months; odometer from canbus once the
   index is pinned; SQLite + JSON/CSV export; dashboard widget with next 2–3 due.

---

## 8. Punch list

Needs the truck on:

- [ ] Read `get(7, 1000)` from our app → expect 6357447 / 6422983 (log the canbus profile id).
- [ ] Climate send test: `cmd(7, 0, {24, 12})` (AUTO) from our app, watch code 13; settle press vs. release.
- [ ] Register on CANBUS 10–66 and press every stock control once to confirm the read table; then
      walk 98–136 for TPMS ×4, odometer, outside temp, doors → `docs/CANBUS-INDEXES.md`.
- [ ] Camera picture: GPU processing stage + live tuning (see "Camera picture quality").
- [ ] Find the **ECU view-switch command** (how `com.syu.us` tells the 360 ECU front/rear/overhead)
      and whether the ECU OSD can be turned off.
- [ ] Parking-sensor distances: real IPC slot (`U_BACKCAR_RADAR` 22 is the candidate).
- [ ] Steering angle: MAIN/41 `ints[0]` live, then wheel-reactive guidelines.
- [ ] Confirm `always_on_vpn_app` makes Tailscale come back after a reboot unattended.
- [ ] Launcher on the panel: Google map actually renders with the key (SHA-1 restriction
      risk on the debug keystore), freeform placement for SPLIT panes, SYU radio/music/
      phone package names for the dock, whether SYU radio/music publish a MediaSession.

Desk work:

- [ ] **Fast reverse path:** the GPIO uevent fires ~1.2 s before `backcar`. SELinux is
      permissive, so a `UEventObserver` on `GPIO_NAME=car-reverse` from our service may be
      allowed. Would cut reverse-to-picture from ~1.4 s to ~0.2 s. Untested.
- [x] Skip the camera re-open when a HIDE is pending (the 7 s ghost open) — 2026-10-22, verify live.
- [ ] Find the `com.syu.ms` IPC command that writes the persistent disabled-package list
      (store key 1022) so the stock cameras stay off across boots; or ship our own
      boot-time `disable-user` step.
- [x] Launcher deltas 2 (climate card, 2026-10-22) and 3 (dials dropped, 2026-10-25). Delta 4 (maintenance slot) open.
- [x] Camera C4 (activity no longer runs its own monitor), C5 (versions bumped); launcher L3
      (dock resolves off-thread), L5 (package-change refresh) — 2026-10-22.
- [ ] Launcher L1 (cluster is 100 % stub until `CanbusVehicleData`), L4 (dock targets unverified), L6–L8.
- [ ] Climate card on the panel: confirm the chip states track the stock panel and that a
      press from our card changes the truck (the whole card is dimmed until the IPC binds).

---

## 9. Docs index

| Doc | What it is |
|---|---|
| `docs/CAMERA-MILESTONE-1.md` | Authoritative camera write-up: stock pipeline decompile, why it lags, sysfs control plane, 360 system, review fixes, install. Note its sysfs-trigger section is superseded by `LIVE-2026-10-21.md` + `CANBUS-IPC.md`. |
| `docs/CANBUS-IPC.md` | ToolkitService Binder protocol, module codes, reverse code, full RAM 1500 climate read/write tables, live checklist. |
| `docs/LIVE-2026-10-21.md` | Live truck session: first light, the 360-ECU-CVBS finding, sysfs flag proven dead, IPC trigger proven with timestamps, climate press, lifecycle. |
| `docs/LIVE-2026-10-25.md` | **Read this first.** Parking sensors + steering pinned, climate write bug found and fixed, audio source (app id) protocol, radio/BT codes, what vehicle data the firmware does and does not decode, OSD touch forwarding, map card. |
| `docs/DASHBOARD-DESIGN.md` | Agreed launcher layout + climate cluster, 1080×1920 mock. |
| `docs/RUNBOOK-CRIMSON.md` | **Step-by-step for a session on Crimson:** recover stock camera, build, install, test, what evidence to send back. |
| `docs/LAUNCHER.md` | Launcher widgets, data sources, FULL/SPLIT, climate card, install, open items. |
| `docs/findings.md` | Dated recon log (early entries corrected in place). |
| `docs/REVIEW-2026-10-21.md` | Desk review of recon + camera M1 (what held up, what was stale). |
| `docs/REVIEW-2026-10-21b-camera-launcher.md` | Review of the camera fixes + dashboard launcher branch: C1–C5, L1–L8, resolution log. |
| `docs/WISHLIST.md` | Maintenance tracker app, steering-reactive guidelines. |

### Stock SYU package map (reference)

- `com.syu.ms` — ToolkitService, GPIO/uevent + chip signal, emits `backcar`, re-enables stock cams at boot (**keep**)
- `com.syu.canbus` — CAN↔MCU bridge, vehicle profile, climate codes (**keep**)
- `com.syu.steer` — steering-wheel buttons (**keep**)
- `com.syu.us` — 360 / radar / physical camera button owner (disable; it covers us on reverse)
- `com.syu.doublecamera`, `com.syu.fourcamera2`, `com.syu.rightcamera`, `com.syu.video` — stock camera stack (disable / replace)
- `com.syu.air` — stock climate UI (source of the climate tables)
- `com.syu.carui`, `com.syu.carmark`, `com.syu.carlink` — car UI overlay, brand profile, CarPlay/AA bridge
- `com.fyt.screenbutton` — physical-button / climate strip overlay window
- `com.syu.fytgesture` — assistive-touch popup (not the camera)
- `com.android.launcher6` — stock launcher (replace)

---

## 10. Lessons that cost a truck session each

- The sysfs reverse flag looked perfect on paper and was dead in practice. Measure the
  trigger with the stock apps actually disabled before building on it.
- "Type-checks clean" in a commit message is not a build. Run Gradle.
- `setFixedSize()` off the UI thread crashes on first open. Camera surface sizing is a
  main-thread job.
- A blocked background activity start does not throw, Android just drops it. Verify the
  activity resumed, don't assume.
- Anything disabled with `pm disable-user` is back after reboot on this unit. Re-check
  the stock cameras after every power cycle until the persistent path is found.
- Never put the Maps key, the licensed blueprint, or APKs in the repo. `.gitignore`
  already covers them; keep it that way.
