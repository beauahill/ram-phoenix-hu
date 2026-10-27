# OEM visual refresh and camera health — 2026-10-26

Branch `codex/refinement-2026-10-26`; launcher **0.5.0 / code 7**, camera
**0.3.4 / code 7**. Based on camera alignment commit `71b64cb`, including the
working TPMS and earlier reliability fixes. Main is unchanged.

## Visual direction

Beau selected modern OEM: graphite surfaces, subtle depth, realistic truck artwork
and refined climate symbols. The overall dashboard structure remains recognizable.

- Replace prominent gloss and colored glow with graphite surfaces and quiet borders.
  Keep amber for selected controls, with cool blue for seat ventilation.
- Use a rendered SRW truck in both the vehicle card and parking display. The PNG is
  decoded once at half resolution, preserves alpha and is drawn at its native aspect.
  It is illustrative artwork, not a dimensionally calibrated vehicle model.
- Present coolant, transmission, oil pressure and oil temperature as larger digital
  readouts with subdued scale bars; retain the same data sources and missing values.
  Tire mapping, truncation and the existing 30/85 psi display thresholds remain.
- Replace rounded human/seat pictograms with a consistent set of fine native line
  symbols. Full climate uses labeled airflow rows, subtle temperature strips and
  two rows of system toggles, keeping MAX A/C on one line.
- Seat controls show actual numeric levels in the compact card; the full page
  retains HEAT/VENT labels. Accessibility descriptions identify driver/passenger,
  heat/ventilation and level. Unknown temperatures do not show a fictional marker.
- Media switches to a compact presentation as the available height shrinks; at the
  smallest size the artist subtitle and progress strip give way to the controls.
  The play target remains at least 44 dp. Fallback-art padding follows the new size.
- App-grid backgrounds fit their columns instead of overlapping neighboring cells.

Artwork generation used the **built-in image_gen tool** via the imagegen skill.
Final assets:
`apps/launcher/app/src/main/res/drawable-nodpi/truck_oem_v1.png` and the identical
`apps/camera/app/src/main/res/drawable-nodpi/truck_oem_v1.png`.
The [exact prompt and provenance](assets/truck-oem-v1-prompt.txt) are saved alongside
this record. Control glyphs are native drawable code, not generated bitmaps.

## Camera health

After the native output surface is ready, a background watchdog allows six seconds
for the first successful Camera2 capture result and two seconds between results.
Missing results close and reopen the session with bounded retry backoff. Each retry
retires the old attempt's generation, so stale device/session/result callbacks
cannot change the replacement or schedule another retry.

A status panel covers the preview during connection/recovery; OSD touches are
consumed while no preview is ready. The reconnect message persists across retries.
The direct native-size capture path and calibrated video viewport are retained.

This measures **capture-result liveness**, not actual display-present timing or
pixel changes. A HAL that continues reporting successful captures of a frozen
analog input can evade this watchdog. Surface-size negotiation precedes its timer.
The parking display also avoids drawing a near-distance glow while its source is
unavailable; sensor mapping and proximity thresholds are unchanged.

## Verification and rollback

- Both `testDebugUnitTest assembleDebug` builds passed. **Launcher: 21 tests;
  camera: nine tests; zero failures/errors.** Camera tests cover native/cropped
  viewport geometry, touch mapping, startup/stall deadlines, stop and retry reset.
- Live normal startup: first successful capture result in **394 ms**. Native-size
  recovery: **388 ms**. These are not GPIO-to-picture or display-present timings.
- Injected a temporary `persist.phoenix.cam.size.14=1280x720`, previously known to
  configure without delivering this analog feed. The watchdog detected missing
  results at **6.06 seconds** and displayed “Camera unavailable · reconnecting…”.
  A shell cleanup trap restored the previous override, recreated the camera and
  verified native **720×504** capture and live picture recovery. Override is empty.
- Inspected the full climate page, collapsed/expanded dashboard climate, vehicle
  readouts, parking image and app drawer on the actual 1080×1920 panel. Climate
  and Maintenance both appear in the drawer. No ECU climate commands were changed.
- Live OBD reconnected after activity changes and repeatedly reported FL 61.03188,
  FR 63.816605, RL 72.99749, RR 75.78222 psi: displayed **61 / 63 / 72 / 75**.
- Existing APKs are saved at
  `/home/beauhill/ram-phoenix-backups/refinement-20261026.z1hiVr/` on Crimson.
  Debug downgrade rollback uses `adb -s 192.168.0.225:5555 install -r -d APK`.
- Before/after captures, fault-test image/log and build logs are in
  `/home/beauhill/ram-phoenix-research/` on Crimson. Matching screenshots are in the
  Windows workspace's `artifacts/` directory; location-bearing map captures are
  kept out of the Git repository.

Beau confirmed the parked physical test on the refreshed apps: reverse, factory
camera view buttons and climate temperature/fan changes all work. SPLIT still
needs attention for the saved external Chrome window's vendor
system bars and the pane selector sharing space with map tools. See the
[active worklist](WORKLIST.md) for the remaining tasks.

## Vendor camera ownership: static investigation

The decompiled stock `com.syu.ms` sources on Crimson expose these enable paths:

- `g/k.java: i()` calls `u0.d.m0("com.syu.doublecamera", z2)` according to
  the selected video-chip configuration. `g/m.java: i()` has another enable path.
- `u0/d.java: m0()` delegates to `u0.i.d3(package, enabled)`.
- `u0/i.java: d3()` checks whether the app exists, compares enabled state and calls
  `a.i.a(package, 1)` when enabling is requested.
- `a/i.java: a()` ultimately invokes
  `PackageManager.setApplicationEnabledSetting(package, 1, 0)`.

This explains how an ordinary disabled state can be overwritten by vendor chip
initialization. The exact path executed on the next cold boot still needs a log
capture; this is a static trace, not a completed persistent-ownership fix. Changing
video-chip configuration or the global TXZ UI property affects more than Phoenix's
camera ownership and is not part of this refresh. Preserve ToolkitService.
