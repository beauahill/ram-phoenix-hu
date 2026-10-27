# OEM polish and reliability candidate

Candidate: launcher **0.5.22 / code 29**, camera **0.3.7 / code 10**.
Based on the user-tested 0.5.21 / 0.3.6 baseline. Installation and physical tests
remain pending while the head unit is offline. Do not describe this as installed.

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
- **102 launcher tests and 9 camera tests passed**, including the eight-state
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
- HU installation, reverse/physical-button regression, fresh-source comparison,
  background-monitor fault recovery, real cold boot and vendor sleep/wake pending.
- [GitHub-hosted checks passed for both apps](https://github.com/beauahill/ram-phoenix-hu/actions/runs/40571459451)
  at source commit `e6d25b07f5d5ba4b976ffd9a442551293ec7c828`. The runner is
  pinned to Ubuntu 24.04 and resolves SDK tools explicitly rather than assuming
  `sdkmanager` is on PATH.

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
