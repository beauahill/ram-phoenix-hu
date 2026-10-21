# Review 2026-10-21 (b) — branch `fix/camera-launch-and-dashboard-launcher`

Scope: commits `a73ecb6..07ef122` (4 commits, 67 files, +2935/−596) on top of
`main@6bddc3a`. Reviewer: Caddy. Method: full read of every Java file touched
plus a **real Gradle build of both apps** on the Crimson box (the commits only
claim "type-checks clean"; both `assembleDebug` runs pass, EXIT=0). Nothing was
run on the unit — it was offline during review — so every runtime claim below
is still truck-gated.

Verdict up front: **merge it.** Both apps compile, the camera corrections are
right, and the launcher is a real dashboard instead of an icon grid. The items
below are follow-ups, not blockers, ordered by how much they'd bite on the
truck.

---

## Camera (`apps/camera`)

### What landed, and it's correct
- **Surface sizing moved to the main thread** (`CameraController.open()` →
  `holder.setFixedSize()` → wait for `surfaceChanged` → open on the bg thread).
  That fixes a real `CalledFromWrongThreadException` the previous version
  would have thrown on first open. The "frame already matches → open now"
  short-circuit (line 98) is right and is what makes re-opens after a
  pause/resume go straight through, because `SurfaceView` keeps the fixed
  size across destroy/create.
- **Per-open `StateCallback` bound to a generation** (`newStateCb(gen)`)
  closes the stale-open race properly.
- **`ReverseService` now verifies the launch** instead of assuming
  `startActivity()` worked. Correct read of Android 10+ background-start
  behaviour (silent drop, no exception). The appops `SYSTEM_ALERT_WINDOW`
  route is the right one for an always-on head unit; the full-screen-intent
  fallback is honestly labelled as keyguard-only.
- Install/grant steps in `CAMERA-MILESTONE-1.md` are the correct sequence.

### C1 — `CameraController.open()` is not idempotent → self-inflicted black flash (HIGH)
`open()` unconditionally bumps `generation` and `doOpen()` calls `doClose()`
first (`CameraController.java:90, 217`). So any call to `open()` with the
**same id on the same surface** tears the device down and reopens it, ~200–400 ms
of black. And `MainActivity.applyMode()` — which calls `open()` — fires from
`surfaceCreated`, `onResume`, `handleIntent`, `onRequestPermissionsResult` and
`setMode()`. Concretely, when you shift into R while the app is already on
screen, **both** the activity's `ReverseMonitor` and the service's
`showCamera()` intent call `setMode(REAR)` → two `open()`s → two
close/reopen cycles on the live rear feed. Entering 360 mode from REAR also
reopens cam 14 even though it's already streaming to the same surface. This
is the exact symptom (flash entering reverse) the rewrite exists to kill.

Fix: at the top of `open()`, if `wantOpen && id.equals(cameraId) && holder ==
attachedHolder && device != null` (device open and session running), return.
Only bump the generation when something actually changes.

### C2 — Boot-in-reverse is swallowed (MEDIUM)
`ReverseMonitor.start()` seeds `lastState = Sysfs.isReverseEngaged()` and only
fires the listener on a *change* (`ReverseMonitor.java:40, 63`). So if the
unit boots (or the service is (re)started) while the truck is already in R —
the "backing out of the garage as it boots" case — the service never calls
`showCamera()`. The stock app handles this explicitly (JNI cmd 32
`GET_BOOT_REVERSE_STATUS`); `ReverseReceiver`'s javadoc claims we achieve the
same "by polling from boot", but we don't. Fix: in `ReverseService.onCreate`,
after `monitor.start()`, `if (monitor.isEngaged()) showCamera();`.

### C3 — Ongoing FGS notification on an `IMPORTANCE_HIGH` channel (LOW)
`buildOngoing()` reuses `CH_ID` (HIGH, needed for the full-screen fallback)
for the permanent "Watching for reverse gear" notification
(`ReverseService.java:134, 139`). On Android 13 that heads-ups on every boot
and service restart. Use a second `IMPORTANCE_MIN` channel for the ongoing
one.

### C4 — Two pollers while the activity is up (LOW)
Service and activity each run their own 80 ms `ReverseMonitor`. Harmless
(sysfs read), but the activity one is now redundant: the service already
delivers SHOW/HIDE intents. Dropping the in-activity monitor also removes the
double `setMode(REAR)` in C1.

### C5 — Housekeeping
- `versionCode`/`versionName` still 1 / 0.1 despite behaviour changes; bump so
  `adb install -r` upgrades are distinguishable in `dumpsys package`.
- `ReverseService.onStartCommand` returns `START_STICKY` but never re-posts
  `startForeground` on restart; fine today because `onCreate` does it, just
  noting it's load-bearing.

### Still truck-gated (unchanged from the punch list; nothing here resolves them)
1. Does `fyt_car_reverse` still flip with `com.syu.doublecamera` disabled?
2. Are camera ids 14 / 10 openable by a third-party app (`dumpsys media.camera`)?
3. What does device 10 actually show (the "overhead" assumption)?
4. Front cam id (tentative 12), parking-sensor LSEC index, steering-angle
   index (wheel-reactive guidelines — punch list).
5. `getenforce`, `wm density`.

---

## Launcher (`apps/launcher`)

### What landed
A genuine dashboard: status row + dock on top, then either FULL (map /
now-playing / instrument cluster stacked) or SPLIT (two 50/50 panes, each
with its own picker, incl. "launch an app into this half"). No AndroidX, one
dependency (osmdroid), 960 KB APK. The media card (`MediaSessionManager` +
album-art tint), the map (osmdroid, CARTO dark, GPS follow, nav hand-off) and
the view re-parenting between FULL slots and panes are all cleanly done. The
build is real: `assembleDebug` passes with osmdroid resolved from Maven
Central.

### L1 — The instrument cluster is 100 % placeholder today (be clear-eyed)
`VehicleCardView` (385 lines) + `TruckOutline` (generated) + `tools/gen_truck.py`
(116 lines) + the blueprint JPEG render speed, RPM, fuel, coolant, battery,
outside temp and four tire pressures — and **every one of them is wired to
`VehicleData.STUB`** (`LauncherActivity.java:64`). On the truck the whole
bottom third shows em-dashes, needles at rest and "GPS · NO CAN DATA"; only
the speed dial moves (GPS). That's documented honestly in `LAUNCHER.md`, but
it means the visual centrepiece of the FULL layout is dead until the
`Main.DATA[]` indexes for seven signals are captured live — which is its own
multi-session reverse-engineering job. Worth deciding now whether a second
speedo/tach on the head unit (the dash already has one) is what you actually
want in that slot, versus e.g. a bigger map, or the maintenance tracker
(see `WISHLIST.md`) once it exists.

### L2 — `docs/assets/ram-3500-blueprint.jpg` provenance (MEDIUM, licensing)
`TruckOutline.java`'s header says the outline is "traced from the dwgmodels
blueprint". dwgmodels.com sells those drawings under a per-user licence that
doesn't allow redistribution. The JPEG (103 KB) is now committed to a public
GitHub repo. The traced *outline* is fine; the source image should come out of
the repo (`git rm` + rewrite isn't worth it, just remove going forward and
note in `gen_truck.py` that the input is local-only). Also:
`TruckOutline.java` says it was generated by `scratchpad/cad/gen_truck.py` but
the committed script is `tools/gen_truck.py` (stale path).

### L3 — Cold-start cost in `Dock.build()` (MEDIUM)
`Dock.build()` runs in `onCreate` on the main thread and its
`musicIntent()`/`radioIntent()` fallbacks call `byLabel()`, which runs
`AppRepository.loadLaunchable()` — a full `queryIntentActivities` + `loadLabel`
over every app, potentially **twice** (`Dock.java:110-116, 133`). That's
exactly the cold-start hit the "no AndroidX" choice was meant to avoid, and it
fires on every HOME press that recreates the activity (`clearTaskOnLaunch`).
Fix: resolve the dock once off-thread and cache; or pin the SYU package names
(open item in `LAUNCHER.md` anyway).

### L4 — Dock targets are unverified on this unit (MEDIUM)
`com.syu.bt`, `com.syu.music` are guesses (`Dock.java:103, 110`); the recon
only confirmed `com.syu.radio`. `ACTION_DIAL` almost certainly doesn't resolve
on a no-telephony unit, so Phone falls to the `com.syu.bt` guess. Each wrong
one is a dimmed dock icon — not a crash — but it's a 10-second
`pm list packages syu` the next time the truck is up.

### L5 — Dock never refreshes after install/uninstall (LOW)
The v0.1 launcher listened for `PACKAGE_ADDED/REMOVED`; this one has no
receiver at all (grep: none). `AppDrawerActivity` reloads on resume so the
drawer is fine, but the dock's Camera tile stays dimmed until the launcher is
recreated if the camera APK is installed after the launcher.

### L6 — SPLIT "app in a half" is speculative until freeform is proven (LOW)
`AppLaunch.inBounds()` uses `ActivityOptions.setLaunchBounds`, which Android
only honours with freeform enabled (`enable_freeform_support=1` + reboot) and
Android 13 draws a caption bar on freeform windows. The fallback (full-screen
+ toast) is sane and the doc is honest about it. Treat it as an experiment
until it's seen on the panel.

### L7 — Map tiles need network + CARTO terms
CARTO's free basemap is for light/attributed use; fine for one truck. But the
map is blank without connectivity (wifi/hotspot) and there's no offline
fallback beyond the tile cache. `LAUNCHER.md` says so; just flagging that the
top widget of FULL mode is also network-dependent.

### L8 — Nits
- `LauncherActivity.onSelect()` calls `media.setLarge(true)` on every pane
  selection even when media isn't in a pane.
- `MediaWidget.progressTick` runs every second even with no session.
- `onBackPressed()` override is the deprecation warning in the build; fine for
  a HOME activity, could use `OnBackInvokedCallback` on 33+.

---

## Recommended order
1. Merge this branch to `main` as-is (it's strictly better than main).
2. Camera C1 + C2 + C3 (small, no truck needed, ~40 lines).
3. Launcher L2 (drop the JPEG) + L3 (dock resolution off-thread).
4. Next truck session: the five camera truck-gated items, then
   `pm list packages syu` for L4, then `dumpsys` the SPLIT freeform behaviour.

## Resolution log

**2026-10-21 04:50 MDT — C1, C2, C3, L2 fixed** (Beau: "knock out C1-C3 and L2").
Both apps rebuilt with Gradle on the Crimson box after the changes (camera 6.9 MB,
launcher 1.0 MB, `EXIT=0`); new strings verified in the compiled dex.

- **C1** `CameraController.open()` returns early when `wantOpen` and the
  same camera id is already bound to the same `SurfaceHolder`. Every surface-
  invalidating path (`onPause`, `surfaceDestroyed`) calls `close()` first,
  which clears `wantOpen`, so the guard can never be satisfied by a dead
  surface. Double `setMode(REAR)` on reverse and REAR→360 are now no-ops on
  cam 14. Error recovery stays with the controller's own reopen loop.
- **C2** `ReverseService.onCreate` calls `showCamera()` when the monitor's
  seed state is already engaged (boot / service restart while in R).
- **C3** Ongoing FGS notification moved to a new `IMPORTANCE_MIN` channel
  (`phoenix_reverse_watch`, no sound/vibration, `setOnlyAlertOnce`); the HIGH
  channel is now only used by the full-screen-intent fallback.
- **L2** `docs/assets/ram-3500-blueprint.jpg` removed from the index and
  git-ignored going forward (stays on disk locally so the tracer runs);
  `tools/gen_truck.py` documents the input as licensed/local-only, accepts
  `BLUEPRINT=` override and fails clearly if the file is absent; stale
  `scratchpad/cad/` path fixed in the generator and `TruckOutline.java`;
  `docs/LAUNCHER.md` updated. NOTE: the JPEG remains in branch history
  (commit `07ef122`); a history rewrite was judged not worth it (per L2).

Still open: C4, C5, L1, L3–L8, and the live-truck measurement items.
