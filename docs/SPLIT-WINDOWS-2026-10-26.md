# SPLIT app windows — 2026-10-26

Branch `codex/split-windows-2026-10-26`, based on `064d879`.
Launcher **0.5.5 / code 12**; camera remains **0.3.4 / code 7**. Main is unchanged.

**Deployment update, 2026-10-27:** cumulative launcher **0.5.14 / code 21** is
installed. Native task/gesture/resize/software-reverse/Home checks passed on this
build. The helper-only restart test passed in 1.1 seconds using the app's own
approved key. A real cold boot and physical Reverse/Park with this backend remain
pending. The original branch/version above records the implementation's origin.

## User report and diagnosis

The initial problems were WirelessOne remaining over the dashboard, factory
information/climate overlays appearing after app launches, and the bottom pane
not growing when the top dock was hidden.

The 0.5.3 first pass added launcher-owned task roots, generation cancellation,
fixed inset handling, task dismissal and dock reflow. Calculator/WirelessOne
open/reflow/Home checks passed. **Those tests missed focus changes.** Beau then
confirmed that reverse takeover/dismissal and factory-bar suppression worked,
but touching the map hid the selected bottom app. He also rejected the side rails
and requested full-width panes with corner pickers.

Live task dumps identified YouTube's own native task 44, separate from the
launcher-owned wrapper. Before a map gesture it was `visible=true`; afterward it
was `visible=false` beneath launcher task 128. The app had not been removed.
Wrapper ownership alone cannot handle apps that create/reuse independent tasks.

A shell-UID proof launched YouTube with freeform bounds and
`ActivityOptions.setTaskAlwaysOnTop(true)`. Its native task remained visible
after map panning. Android's `IActivityTaskManager.resizeTask()` also supplies
the in-place resize capability unavailable through the launcher's public API.

## 0.5.5 implementation

- Split app windows use a small shell-UID companion, built from
  `apps/windowbridge/src` by the launcher's normal Gradle build. Native tasks
  stay above the launcher when touch focus returns to the map. The client does
  not close/relaunch an app on focus changes.
- Two explicit slots identify each selected component, generation and screen
  rectangle. The companion records the resulting native task ID. Removal and
  resizing re-check its package before touching it. Replacements are retired
  before new launches, including moves between slots. No `MULTIPLE_TASK` flag.
- Geometry changes resize the native task rather than recreating its activity.
  App-specific Android configuration handling can still affect its UI/state.
- The permanent side rails are removed. Both content slots use the full available
  width. A small corner chevron opens a labeled Map / Media / Vehicle / Climate /
  Apps menu; Apps exposes that pane's grid. An overlay hit target keeps the
  chevron reachable over a third-party task without reserving horizontal space.
- FULL, Home, pane replacement and full-screen launches retire the appropriate
  native windows. Full-screen launching waits for the close request to complete.
  The prior owned-root path remains for compatible full-screen drawer launches.
- A separate MAIN 12 subscription remains bound while the launcher lives.
  Reverse suppresses native app windows; the companion also watches for a visible
  Phoenix camera task. It can restore the selected split app after suppression
  clears. A 1.8-second client lease dismisses owned windows if the launcher dies.
  A mode-0600 atomic journal records owned IDs/packages so a restarted companion
  can retire its prior windows before accepting new selections.
  This changes Android windows only; no vehicle commands are added.

## Connection and permissions

The companion listens on **127.0.0.1:43837**, using protocol version 2. A random
256-bit credential is stored in the launcher's private, non-backed-up directory;
the shell copy is mode 0600 inside a verified shell-owned mode-0700 directory,
`/data/local/tmp/phoenix-windows`. Requests and responses use HMAC-SHA256 with a fresh
nonce. The credential is never sent to a port listener. The server rejects
reused nonces and requests older than two seconds. It accepts bounded requests
for the two slots, not arbitrary shell commands. Native camera/launcher components
are excluded as split targets. Bounds target this HU's 1080×1920 panel.

The local bootstrap connects to the HU's already-enabled loopback ADB port 5555.
It generates a fresh RSA key inside the app's private no-backup directory. It
does not copy Crimson's or the Windows PC's ADB identity. The wire implementation
was informed by the ADB protocol and Cameron Gutman's
[AdbLib source](https://github.com/cgutman/AdbLib); the implementation here uses
single synchronous streams rather than that library.

**One-time setup is still pending:** while parked, choose **Enable split app
windows → Set up**, then **Always allow / Allow** on Android's debugging prompt.
Only explicit setup can offer the public key and show that prompt. Automatic
reconnects try the existing signature only. After authorization, the launcher can
start the companion again when the head unit restarts. That real restart path
still requires an awake-device test.

During development the candidate's version-1 companion was started through
Crimson's approved ADB connection. This temporary start is not evidence that the
app's own ADB key is authorized. The final deployment must stop that old companion;
the installer below waits for its task lease before retiring its specific process.

`SYSTEM_ALERT_WINDOW` is used for the corner buttons/menu and was allowed on the
HU using appops. [tools/setup_launcher_ui.sh](../tools/setup_launcher_ui.sh)
applies that grant and the factory-bar setup with an explicit device serial.

## Factory bars

The stock SystemUI implementation supplied the actual controls:

- Its climate Hide button writes **System `show_navigationbar=0`**. The script
  applies that persistent setting; the previous value was 1.
- Its status receiver accepts **`com.fyt.action.hide_statusbar`** with boolean
  **`visibe=false`** (vendor spelling). Launch paths send package-scoped broadcasts
  immediately and shortly afterward.
- Zero fit-inset types/sides and stable immersive layout prevent a foreign app's
  bar requests from shifting the launcher by 60 pixels.

The launcher does not request extra settings permissions. SystemUI and
ToolkitService remain running. A foreign full-screen app can still reserve its
own blank status-bar area. Vendor cold-boot restoration of bars remains to test.

## Live follow-up, 2026-10-27

- Beau explicitly approved persistent local debugging trust. Verified the dialog's
  fingerprint against the launcher's public key and selected Always allow/Allow.
  No workstation private key was read or copied. Android's normal key-expiry policy
  remains enabled.
- Vendor SystemUI checks `service.adb.tcp.port`, although this HU enables TCP 5555
  through `persist.adb.tcp.port`. With the runtime property empty, its debugging
  dialog immediately denied the connection on a sticky USB-disconnected broadcast.
  Verified this condition in the installed `UsbDebuggingActivity` and event log.
  Aligning the runtime flag to the already active port allowed the genuine prompt.
  The installer handles this specific configuration; it never approves a key itself.
- The local transport now tolerates delayed close/ack packets for retired stream
  IDs and captures bounded shell output for ownership/mode checks. The existing
  fake-adbd test also injects a delayed retired-stream close before the next OPEN.
- Sync transfer can widen permissions on this firmware. Credential staging now
  occurs only inside the private directory, checks final 0600 modes, and rotates
  the early token on migration. The obsolete flat files were restricted to 0600;
  the new helper no longer uses their credential.
- Background startup retains the shell stream until the detached helper reports
  readiness, then verifies authenticated replies after transport closure. Earlier
  builds closed the shell before its child had reliably detached. A successful
  setup alone did not establish restart reliability; the first fault tests failed.
- Final helper fault test: verified exact shell UID/name, terminated PID13306,
  and observed the launcher start PID13489 in **1.1 seconds**. No Crimson bootstrap
  command was issued. Verified directory 0700 and token/JAR 0600. Evidence:
  `window-helper-restart-20261027.txt` in the private research folder.
- Final 0.5.14 native probe passed gestures, preserved task on resize, software
  reverse suppression/restoration and Home dismissal. The probe starts with a
  proper HOME intent and records post-Home task state instead of treating an
  empty historical task container as a running app.
- Actual picker testing on 0.5.10 selected YouTube task179, retained it through
  five map pans and dock expansion/collapse, and dismissed it on FULL. Final
  0.5.14 screenshot confirms the smaller corner chevron, unobscured zoom control
  and full-width native YouTube pane. No permanent side rail is reserved.

All **92 unit tests** and app/test APK builds passed. Physical camera priority,
broader app compatibility and a real cold boot still need their own checks.
Device screenshots/logs remain outside Git under `/home/beauhill/ram-phoenix-research`.

## Verification and pending checks

- `testDebugUnitTest assembleDebug assembleDebugAndroidTest`: **37 unit tests,
  zero failures/errors/skips**, plus successful APK and device-probe compilation.
- Three new isolated fake-adbd tests exercise RSA authentication, explicit
  public-key approval, rejection without an automatic approval prompt, multi-packet
  file transfer, interleaved transport acknowledgments and stream teardown.
- Three message-authentication tests reject changed credentials, payloads, nonces
  and malformed signatures.
- The initial 0.5.5 integrated test was postponed because the display was off.
  The later cumulative build's live results are recorded above; no off-screen
  task state was counted as a visual pass.
- 0.5.3 physical reverse takeover/dismissal and factory overlay removal are
  user-confirmed. Those results do not establish camera priority for the new
  always-on-top native backend.
- Next parked checks: select YouTube and pan/tap the map repeatedly; interact with
  the app; use each corner picker; hide/show the dock and confirm the app expands
  without restarting; select FULL/Home/drawer apps; Reverse → Park with the native
  split app visible. Repeat with WirelessOne, without operating Air Lift controls.
- One-time setup and companion-only recovery are now verified above. Repeat after
  a real HU cold boot. Chrome/Spotify split compatibility remains unverified.

The device-only `PaneWindowProbe` now checks native visibility after four map
gestures, preserved task ID on resize, the software reverse gate and Home
dismissal. It refuses to run with the display off. Its software reverse gate is
not a substitute for a physical reverse test. It passed on cumulative 0.5.14.
Run only with a parked truck and an available display:

```sh
python3 tools/install_launcher.py 192.168.0.225:5555 apps/launcher/app/build/outputs/apk/debug/app-debug.apk
# Finish one-time Enable split app windows in the launcher, then test:
adb -s 192.168.0.225:5555 install -r apps/launcher/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 192.168.0.225:5555 shell am instrument -w -e target com.google.android.youtube com.ramphoenix.launcher.test/com.ramphoenix.launcher.PaneWindowProbe
adb -s 192.168.0.225:5555 uninstall com.ramphoenix.launcher.test
```

Use a split layout with the map above before this probe; it deliberately dismisses
its test app afterward. `-e operation setup` instead performs explicit one-time
setup and waits for the human's Android approval.

Raw task/window dumps, build/test logs and location-bearing screenshots remain
outside Git in `/home/beauhill/ram-phoenix-research/` on Crimson. Important evidence:
`split-focus-opened-tasks.txt`, `split-focus-after-pan-tasks.txt`,
`split-native-final-build.log`, `split-native-packaged-build.log` and
`window-probe/NativeStart.java`. Earlier 0.5.3 evidence remains there separately.

## Rollback

Close app windows with Home. Stop the specifically named `phoenix-window-bridge`
shell process before downgrading; otherwise its lease should expire when the new
client stops, but explicit cleanup avoids a stale companion. Launcher 0.5.2:
`/home/beauhill/ram-phoenix-backups/split-windows-20261026.XfrzBD/launcher-0.5.2.apk`.
Install with `adb -s 192.168.0.225:5555 install -r -d APK`.

To restore the former factory bars as well:

```sh
adb -s 192.168.0.225:5555 shell settings put system show_navigationbar 1
adb -s 192.168.0.225:5555 shell am broadcast -a com.fyt.action.hide_statusbar -p com.android.systemui --ez visibe true
```
