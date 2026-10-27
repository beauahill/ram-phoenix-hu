# OEM polish and reliability candidate

Installed: launcher **0.5.23 / code 30**, camera **0.3.7 / code 10**.
Based on the user-tested 0.5.21 / 0.3.6 baseline. The initial 0.5.22 launcher
exposed a connected-OBD crash described below. After the network recovered,
0.5.23 installed successfully and remained running through live diagnostic
updates. Beau subsequently confirmed physical Reverse → Park picture/dismissal,
the dedicated camera button and climate temperature/seat-control response.

## Appearance

- One set of graphite glass surfaces across launcher, climate, maintenance,
  suspension and camera parking information. Shared corner radii, subtle borders
  and quiet pressed/selected states; camera video and its opaque lower mask are
  preserved.
- Appearance now offers Automatic, Day and Night. Automatic uses local time:
  day from 07:00 through 18:59. This is a clock schedule, not an ambient-light or
  headlight sensor. Wallpaper dimming is saved separately for day and night.
- Appearance changes update existing drawable instances; they do not recreate
  activities, camera sessions or embedded app windows.
- Default dock icons use a consistent neutral treatment. User custom icons remain
  available. The Settings slot now also honors a custom selection.
- An idle media card collapses to a compact source/resume row in FULL layout.
  A paused media session retains its information. SPLIT pane dimensions stay fixed.
- Gauge values are larger. Routine CAN/OBD diagnostics move into the vehicle-card
  information button, where each gauge's current source and reading age are visible.
  Red seat heat, blue cooling, level bars and familiar climate symbols are retained.
- Tight media layouts reserve room for their controls and adapt their artwork.
  Source buttons retain 44 dp targets. The small Air Lift icon follows its pressure
  row even when expanded climate compresses the vehicle card.

## Reliability changes

### Reading age

An IPC connection does not prove that the factory CAN box is receiving new data.
Factory registration can replay cached scalar fields and suppress repeated equal
values. Initial cached gauge registration is therefore withheld until a valid
post-seed change confirms another PA packet 82 update. That proof is shared by
the four gauge words from the packet and expires after two minutes.

This is deliberately conservative: if all four values remain equal for two
minutes, even a healthy bus may lose freshness confirmation. The display then
uses a fresh OBD fallback or a dash. A page-read request never renews the lease.
An independent raw CAN heartbeat has not been established on this PA profile.

OBD scalar/custom readings expire after 30 seconds. Their age starts before the
gauge read sequence, so slow extra PID queries do not refresh earlier samples.
Existing independent TPMS and mileage age checks remain in use. A socket being
connected does not make an expired reading live. Engine gauge freshness does
not establish independent freshness for cached door/outside/speed fields.

### Monitor recovery

A small foreground launcher service maintains the signed camera reverse monitor
while the screen is interactive and ACC is on, including when a different app
owns the screen. It checks every 15 seconds and starts after launcher entry,
Android boot or package replacement. The camera also handles its own package
replacement. The watchdog starts a service, never the camera activity itself.

This is bounded recovery, not uninterrupted reverse coverage after a force-stop.
A real vendor sleep/wake and cold boot must still demonstrate recovery timing and
camera ownership. Nothing here restarts `com.syu.ms`, changes the CAN profile or
changes the calibrated camera crop/touch coordinates. No new stock-package
disable policy has been applied; the vendor's known stock-camera re-enable path
still needs a cold-boot observation before a targeted remedy.

### Automated checks

The GitHub Actions workflow builds both apps and runs their unit tests on pushes
to main/codex branches and pull requests. Official action versions are pinned to
commit hashes. Reports and synthetic layout PNGs are retained for 14 days; CI
does not publish or deploy debug APKs with a different signing key.

The native Android layout suite uses real dashboard XML/widgets at 1080×1920,
2× density and 1.25 font scale. Eight day/night state previews cover dock open and
closed, idle and playing media, expanded climate and SPLIT. Bounds assertions
check map controls, seat controls, climate height and media transport controls.
These are layout fixtures with synthetic map/vehicle/media data, not live truck
screenshots or a pixel-baseline comparison. Native app focus and vendor camera
hardware behavior still require the actual head unit.

## Verification

- Crimson builds: launcher and camera debug APKs built successfully.
- **104 launcher tests and 9 camera tests passed**, including the eight-state
  native layout matrix and cached/expired reading scenarios.
- Day/night idle, playing/expanded-climate and SPLIT previews inspected.
- Isolated Android 13 emulator: both apps installed, no fatal runtime exceptions;
  Day/Night switching and independent dimming verified (Day 32%, edited Night
  61%, then switching back retained each). These were emulator preferences only.
- With Appearance foreground on that emulator, force-stopping only Phoenix Camera
  removed its service; the launcher restored the reverse monitor in 4.1 seconds.
  The activity stayed in Appearance. This is one observed recovery, not a maximum
  latency guarantee or a physical camera picture test.
- After an Android reboot of that emulator, BOOT_COMPLETED started both foreground
  services automatically. Vendor services/camera hardware are absent there; this
  does not establish the HU's real boot or ACC sleep/wake behavior.
- No suspension pressure commands sent during this pass.
- Corrected launcher installation, live source diagnostics, day/night profiles,
  map toolbar and background monitor recovery checked on the HU, as detailed below.
- Beau confirmed the requested physical reverse/button/climate check on the
  installed 0.5.23 / 0.3.7 update: “All good.”
- Exact fresh-source cluster comparison, real cold boot and vendor sleep/wake
  remain separate pending checks.
- [GitHub-hosted checks passed for both corrected apps](https://github.com/beauahill/ram-phoenix-hu/actions/runs/40580727733)
  at source commit `5caee034fac4245158dbe83507ab587612211404`. The runner is
  pinned to Ubuntu 24.04 and resolves SDK tools explicitly rather than assuming
  `sdkmanager` is on PATH.

### First HU deployment and correction

The installed 0.5.21 / 0.3.6 APKs and launcher shared preferences were backed up
to private Crimson directory `/home/beauhill/ram-phoenix-backups/oem-polish-5li9nleb`.
The app-owned ADB key and authentication files in `no_backup` were excluded.
Candidate and installed signer SHA-256 matched for both apps.

The first update installed 0.5.22 / 0.3.7 and both monitor services started. Once
OBD connected, the launcher crashed with a StackOverflowError: the freshness
change made `oilPsi()` and `oilPsiClamped()` call each other. This had not executed
on the emulator without a live OBD sample. The 0.5.22 launcher is withdrawn.

Launcher 0.5.23 fixes that path by clamping the guarded raw field directly. New
tests exercise the actual public getters with fresh connected pressure, negative
pressure, absent pressure, missing freshness and disconnected values, without
opening Bluetooth or sending vehicle commands. All 104 launcher tests pass.

The initial rollback command was refused as a version downgrade; a retry with
`install -r -d` then lost the transport with `abb_exec: closed`. The HU stopped
answering LAN and Tailscale probes. After reconnect, a device read confirmed that
0.5.22 was still installed; no successful rollback was inferred. The corrected
0.5.23 subsequently installed successfully, with code 30 confirmed from Android.

Corrected launcher APK SHA-256:
`411a16b86c80102a9c90eb8762b4ba832e6fd1d4bfe214b55395cdbfeb10f155`.

### Corrected HU live checks

- The corrected launcher stayed in one process through OBD connection, multiple
  TPMS polls and mileage reads. The initial PID was 19032; instrumentation later
  restarted the target as expected. No post-fix recursive getter crash observed.
- Source details showed coolant 114°F / CAN, transmission 86°F / CAN, oil
  temperature 114°F / CAN and oil pressure 0 psi / OBD at that sample. CAN had a
  genuine post-registration change; source age was 10 seconds, OBD age 3 seconds.
  These are observed values, not a new cluster comparison.
- Actual Day and Night surfaces were inspected. The owner's dimming values
  remained 26% and 43%, and Automatic mode was restored. No wallpaper replacement.
- All five map buttons stayed within the card with 88×88 px targets in Home,
  tray-open and tray-plus-expanded-climate states. Layout toggles were restored.
- The native window probe passed actual YouTube task persistence through map
  gestures, in-place resize, software reverse guard and Home dismissal. This
  synthetic guard check does not prove physical reverse picture/takeover.
- With Android Settings foreground, only Phoenix Camera was force-stopped. Its
  service record returned after 14.6 seconds; foreground status and launcher as
  the caller were confirmed in a subsequent read. Settings remained foreground,
  with no camera activity opened by the recovery pulse.
- Phoenix was selected as default HOME; Android resolution and an ordinary Home
  key from Settings both returned to Phoenix. Retired `com.syu.doublecamera` was
  already disabled (enabled=3); no vendor package state or service was changed.
- Suspension presets, maintenance records/drafts and launcher/pane preferences
  matched the backup. The appearance preference only gained explicit `auto`;
  odometer freshness time advanced from a normal live read. No pressure command.
- FYT reports app bounds ending at y=1693 to accessibility even though the actual
  window/surface spans y=1920. Thus UIAutomator reports zero seat-control bounds
  at the bottom; this is not used as proof of a touch failure or a passing seat
  test. Beau's subsequent physical temperature/seat-control response check passed.

Private Crimson evidence: `hu-polish-source-details.*`, `hu-polish-profile-*`,
`hu-polish-expanded.*`, `hu-polish-native-pane-check.txt`,
`hu-polish-background-recovery.json`, `hu-polish-window-frames.txt` and
`hu-polish-preference-compare.json` under `ram-phoenix-research`.

## Parked acceptance checklist

1. Back up installed APKs and private preferences; verify candidate signer matches.
2. Install both candidates, inspect Appearance modes and separate dimming values.
3. Check map tools with tray/climate expanded; idle/active media; native split app
   persistence during map gestures and tray resizing.
4. Reverse → Park and physical camera button; verify actual picture and dismissal.
5. Compare current cluster values and source details; verify expired sources show
   fallback/dashes and later recover. Do not induce vehicle-bus faults.
6. With the truck parked and screen available, stop only Phoenix Camera and verify
   the monitor recovers while another app is foreground. No vendor service stops.
7. Observe actual ACC sleep/wake and a real cold boot. Record time to usable camera,
   native split recovery, selected HOME and any stock-camera ownership conflict.
