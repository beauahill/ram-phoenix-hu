# Reliability pass — 2026-10-26

Branch: `codex/reliability-2026-10-26`. Includes the existing SPLIT/freeform launcher
commit `c279705` and the review document from `04ef82a`. Main is unchanged.

## Changes

- Launcher 0.4.1 (versionCode 5), camera 0.3.2 (versionCode 5).
- OBD cancellation owns and closes a socket before/during connect. A rapid stop/start
  waits for the old worker to exit; retired workers cannot publish readings or clear
  the replacement's state. Stopping immediately marks readings unavailable.
- ELM commands require a complete `>`-terminated response. A timeout retires the
  connection instead of sending another request into an incomplete transaction.
  Protocol-search extensions are bounded and use a monotonic clock.
- Camera launches carry a token for the current reverse shift. A delayed SHOW after
  leaving reverse is dismissed before opening the feed. Pending fallback notifications
  are cancelled when reverse ends. A manually opened camera remains manual.
- Parking readings reset on IPC loss/monitor stop and require all eight zone updates
  before becoming available again. The existing hold used for pulsed readings remains.
- Climate state is mutated/rendered on the UI thread. A new fan-slider target and
  activity pause cancel the previously queued fan steps.
- The drawer hides the launcher HOME component, allowing Climate and Maintenance
  activities in the same APK to appear in the drawer and shortcut pickers.
- The launcher forwards MapView start/stop in addition to the existing lifecycle calls.

## Verification

- `apps/launcher: ./gradlew testDebugUnitTest assembleDebug` — passed; eight tests,
  zero failures/errors. Tests exercise cancellation during a blocked connection,
  stop/start with delayed cleanup/socket close, rejection of stale publication/fallback sockets,
  prompt boundaries, partial replies, EOF, bounded search noise and interruption.
- `apps/camera: ./gradlew assembleDebug` — passed.
- `git diff --check` — passed.
- Installed both debug APKs on the head unit after backing up both original APKs.
- Launched camera with an expired reverse-session token while the launcher was
  foreground: returned to the launcher; the reverse monitor registered successfully.
- Physical reverse cycles, manual-camera ownership, drawer entries and repeated
  launcher pause/resume: live results are recorded below as testing progresses.

## Parked test sequence

1. From the launcher: R for five seconds, Park; repeat once. Check automatic camera
   appearance, return to the launcher and absence of a second popup/reopen.
2. Open camera manually, then R and Park. Camera should remain open after leaving R.
3. Verify Climate and Maintenance are visible in the app drawer and open correctly.
4. With the OBD adapter awake, move between launcher and climate/camera repeatedly.
   Verify no overlapping OBD connections and that readings recover after returning.
5. In a later cold-boot session, verify stock camera package ownership and boot-in-R.

## Follow-up work

- Camera first-frame/stalled-stream watchdog and measured time to visible picture.
- Persistent camera ownership after cold boot; stock package re-enabling remains
  documented as unresolved. This pass does not change vendor package policy.
- OBD availability with the actual adapter awake; transport changes cannot wake an
  adapter that is asleep or fix an external Bluetooth pairing/connection failure.
- Shared IPC/profile library, search cancellation/provider handling, and data freshness
  across the rest of the vehicle dashboard.

## Live results

Physical tests pending.

The reverse monitor seeded `U_BACKCAR = 0` and all eight parking zones on the unit.
The OBD adapter refused the current Bluetooth connection attempts, so live gauge
recovery has not been verified. No reverse shifts had reached the recorded log at
the last check; the two-cycle physical test is awaiting the driver's result.
