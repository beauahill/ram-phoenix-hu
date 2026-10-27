# Map controls and seat indicators — 2026-10-27

Installed launcher **0.5.21 / code 28**. Camera remains **0.3.6 / code 9**.

## Changes

- Map toolbar responds to actual card height. It uses a horizontal row when the
  five-button vertical column cannot fit, including after top-tray or climate
  expansion. Each button has a 44 dp target. Zoom, layers, 3D and traffic keep
  their existing behavior; the split-pane corner retains its clearance.
- Seat heating icons and active level bars use red `#FF625C`; ventilation uses
  blue `#4CA9FF`. Off stays neutral. Both dashboard and full climate share this
  renderer. Levels still come from CAN callbacks, never an optimistic tap count.
- Added test-only `climate-read` instrumentation for a read-only snapshot of the
  factory profile/climate callbacks. It sends no control commands.

## Diagnosis and live checks

Initially Phoenix commands logged but no seat level changed. Beau confirmed the
engine was running and the truck outdoors. A separate callback snapshot returned
active profile 2163062, MAIN50=1, all four seat levels 0, fan 0 and temperatures
1/1. The active factory profile was unchanged.

The factory PA climate Activity is not exported; direct shell launch was refused.
Its normal exported `com.syu.canbus.enter.air` action opened it successfully. A
factory driver-cooling press reported level 3. Phoenix then received that level
and its next press changed it to 2. **The earlier temporary nonresponse recovered
during this comparison; its cause is not established.** No command mapping, CAN
profile, MCU setting or vendor service was changed or restarted to force recovery.

On the installed 0.5.21 dashboard, verified actual callback/accessible state for
driver and passenger heat and ventilation through **3 → 2 → 1 → 0**. Screenshots
confirm blue cooling and red heating icons/bars. All four seat settings returned
to Off after QA. This verifies command/readback and rendering at that time; it
does not establish the cause or permanent resolution of the earlier nonresponse.

All five map controls were fully inside the card, with 88×88 px targets, in:

- FULL with dock open, climate compact and expanded.
- SPLIT with dock open and closed.
- FULL with dock closed and climate compact (original layout restored).

All **92 launcher unit tests passed**. Launcher and instrumentation APKs built;
the camera foreground reverse monitor remained running. No suspension command
was sent. Pre-install APK/preferences backup:
`/home/beauhill/ram-phoenix-backups/map-seat-ui-5q63acd8`.

Private Crimson evidence: `map-fit-live-test.json`, `seat-level-live-test.txt`,
`home-seat-pressed.png`, `home-seat-heat-red.png`, `climate-read-before.txt`,
`factory-seat.png` and `map-seat-ui-build.log` in `ram-phoenix-research`.

APK SHA-256:
`b8a8a56beb173f80d55330103ec37e9d72511003a67a44421cc177e5f54e79f6`.
