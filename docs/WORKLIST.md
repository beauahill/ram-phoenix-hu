# Active worklist

Updated 2026-10-26. Current branch: `codex/refinement-2026-10-26`, based on the
tested reliability, TPMS and camera-alignment branches. Main is unchanged.

## Completed and previously verified

- [x] LAN SSH access through Crimson and explicit head-unit ADB targeting.
- [x] OBD worker/socket cancellation, complete ELM response boundaries and tests.
- [x] Reverse-session tokens and rejection of stale reverse launches.
- [x] Physical reverse activation confirmed by Beau on camera 0.3.2.
- [x] Four tire pressures on 2019 RAM 3500 SRW, verified against the cluster:
  61 / 63 / 72 / 75 psi in that snapshot. Launcher 0.4.2.
- [x] Camera top/left/bottom border corrected; live screenshot verified on 0.3.3.
- [x] Parking-data reset on IPC loss and all-eight-zone freshness requirement.
- [x] Climate UI-thread state updates and cancellation of queued fan commands.
- [x] Climate/Maintenance app components included in drawer discovery.
- [x] Map lifecycle start/stop forwarding.

## Current refinement pass

Beau selected: **Modern OEM — graphite surfaces, subtle depth, realistic truck
artwork, refined climate symbols.** Preserve the overall layout and usable touch
targets. Both launcher and climate are part of the same visual system.

- [x] Install and inspect the launcher 0.5.0 visual pass on the actual panel:
  quieter cards, less gloss, digital vehicle readouts, realistic SRW truck.
- [x] Inspect full climate and dashboard climate: fine line symbols, labeled
  airflow choices, warm/cool seat states, readable levels and no wrapping MAX A/C.
- [x] Verify expanded climate in FULL mode; fix clipped media controls and
  fallback-art sizing. Fix shared app-grid tiles exceeding their columns; verified
  in the drawer. Climate and Maintenance entries are visible.
- [ ] Continue SPLIT layout review: the saved external Chrome pane can display
  vendor system bars, and the pane selector overlaps map controls. Keep this
  distinct from the corrected shared app-grid cell sizing.
- [x] Verify TPMS/gauges recover after leaving climate and camera. Live samples
  resumed at 61 / 63 / 72 / 75 psi, coolant 95°F, transmission 84°F, battery 12.6 V.
- [x] Install/test camera 0.3.4 capture-result watchdog: six-second initial
  deadline, two-second stall deadline, reconnect overlay, attempt-token isolation.
- [x] Record first successful capture-result timing: 394 ms, then 388 ms on
  recovery with native capture size. Keep this separate from
  actual display-present latency; a repeating frozen analog source is not detected
  by capture-result liveness alone.
- [x] Fault-test no-frame startup using a temporary 1280×720 override: timeout at
  6.06 s, reconnect overlay, native-size restoration and successful recovery.
  Override cleared. Mid-stream timeout is covered by unit tests; a physical
  analog-source freeze is not detected by capture-result callbacks alone.
- [ ] Physical reverse cycle and factory camera-button response after these updates.

## Remaining reliability work

- [ ] Cold boot: identify and resolve vendor re-enabling of stock camera packages.
  Preserve ToolkitService and CAN/MCU functions. Requires a later parked cold boot.
  Static enable paths are now traced in [REFINEMENT-2026-10-26.md](REFINEMENT-2026-10-26.md).
- [ ] Confirm camera ownership after repeated reverse cycles, boot while in R and
  manual camera use followed by R/Park. Mark each only after the actual test.
- [ ] Diagnose factory view-button response: IPC commands are sent, but the
  2026-10-26 remote taps did not visibly change the image. Do not call this passed.
- [ ] Measure time from physical reverse signal to displayed usable image.
- [ ] Shared IPC/profile definitions across apps; retire contradictory old constants.
- [ ] Search cancellation/provider handling and freshness of remaining vehicle data.
- [ ] Check optional phone/apps/OBD cards against the updated visual system.

See [RELIABILITY-2026-10-26.md](RELIABILITY-2026-10-26.md),
[TPMS-2019-RAM3500.md](TPMS-2019-RAM3500.md), and
[CAMERA-ALIGNMENT-2026-10-26.md](CAMERA-ALIGNMENT-2026-10-26.md) for earlier evidence.
