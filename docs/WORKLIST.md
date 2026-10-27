# Active worklist

Updated 2026-10-26. Current branch: `codex/split-windows-2026-10-26`, based on the
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
- [x] SPLIT 0.5.3: Beau confirmed camera takeover/dismissal and removal of the
  factory overlays. Earlier open/reflow/Home probes missed a focus-change bug:
  interacting with the map hid the selected app's independent native task.
- [x] Implement SPLIT 0.5.5: native task management, in-place resize, full-width
  panes and corner chevrons. Native always-on-top proof passed; 37 unit tests pass.
  See [SPLIT-WINDOWS-2026-10-26.md](SPLIT-WINDOWS-2026-10-26.md).
- [ ] Install the final 0.5.5 revision. An earlier candidate is on the HU; the
  final installation was prevented by the HU going offline (`No route to host`).
- [ ] Run integrated 0.5.5 parked map/app/chevron/dock/FULL/Home tests. Display
  switched off; Beau opted to test later. Do not call this visually verified.
- [ ] Complete the app's one-time local ADB approval and test helper restart and
  real cold boot. Current session uses the helper started through Crimson.
- [ ] Broaden split app compatibility checks, including WirelessOne/Chrome/Spotify.
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
- [x] Beau confirmed the parked test on the refreshed apps: reverse, factory
  camera view buttons and climate temperature/fan changes all work.
- [x] Climate clarity follow-up, launcher 0.5.2: Beau found the new symbols
  confusing. Replace them with explicit text labels, wider seat controls and
  fan minus/plus. Keep Power, Auto and Sync visible in the collapsed card.
  Visually inspect collapsed, expanded and full-page layouts on the actual HU.
  Existing CAN command mappings are retained; no HVAC controls pressed during QA.
  See [CLIMATE-CLARITY-2026-10-26.md](CLIMATE-CLARITY-2026-10-26.md).

## Remaining reliability work

- [ ] Cold boot: identify and resolve vendor re-enabling of stock camera packages.
  Preserve ToolkitService and CAN/MCU functions. Requires a later parked cold boot.
  Static enable paths are now traced in [REFINEMENT-2026-10-26.md](REFINEMENT-2026-10-26.md).
- [ ] Confirm camera ownership after repeated reverse cycles, boot while in R and
  manual camera use followed by R/Park. Mark each only after the actual test.
- [x] Beau confirmed Reverse/Park takeover/dismissal with SPLIT on launcher 0.5.3.
- [ ] Repeat physical Reverse/Park with a native managed app on launcher 0.5.5;
  the always-on-top backend needs its own camera-priority check.
- [x] Physical factory view-button response confirmed by Beau on 2026-10-26.
  Earlier remote taps were inconclusive; the parked user test takes precedence.
- [ ] Measure time from physical reverse signal to displayed usable image.
- [ ] Shared IPC/profile definitions across apps; retire contradictory old constants.
- [ ] Search cancellation/provider handling and freshness of remaining vehicle data.
- [ ] Check optional phone/apps/OBD cards against the updated visual system.
- [x] Oil temperature: factory PA RAM CANBUS field 235, verified at 91°F against
  Beau's current cluster reading. Installed launcher 0.5.1; both vehicle-data
  cards use this source. See [OIL-DATA-2019-RAM3500.md](OIL-DATA-2019-RAM3500.md).
- [ ] Oil life remaining: Beau reports 96%; no verified data source yet.
  Save rejected identifiers and bounded read-only scan evidence in the oil-data
  record. Do not infer a percentage from an unidentified counter.

See [RELIABILITY-2026-10-26.md](RELIABILITY-2026-10-26.md),
[TPMS-2019-RAM3500.md](TPMS-2019-RAM3500.md), and
[CAMERA-ALIGNMENT-2026-10-26.md](CAMERA-ALIGNMENT-2026-10-26.md) for earlier evidence.
