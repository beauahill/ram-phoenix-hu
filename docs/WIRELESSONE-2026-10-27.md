# WirelessOne air-spring integration

Updated 2026-10-27. Branch `codex/wirelessone-2026-10-27`, launcher **0.5.8 (15)**,
based on maintenance `f3ccbf2`. Main is unchanged. **Candidate only: not installed,
not paired or tested against Beau's manifold. No suspension commands were sent.**

## Requested behavior

The Vehicle Info card has an Air suspension entry with measured spring pressure,
separate from the four tire pressures. It opens four Phoenix load profiles:

| Profile | Target |
| --- | --- |
| Full Load & Trailer | 80 psi |
| Light Trailer | 50 psi |
| Light Load | 30 psi |
| Empty | 5 psi, or the air-spring kit minimum if higher |

Tap a profile, then Set pressure. These are local named targets, not replacements
for the manifold's three stored presets. The measured pressure and the controller's
reported target are displayed separately. A successful GATT write is not shown as
proof that the bags have reached the target.

## Evidence and limits

- [Air Lift WirelessOne user guide AD-946](https://digital.airliftcompany.com/m/53c7904429e8c513/original/AD-946.pdf)
  documents the controller's 5 psi minimum, 100 psi upper controller range, three
  memories and single-client Bluetooth connection. The air-spring kit can impose a
  lower maximum. Do not assume that every Air Lift spring supports 80 psi.
- [Air Lift's 25980 product page](https://www.airliftcompany.com/shop/25980)
  explicitly says compressor systems maintain at least 5 psi, even for spring kits
  whose unassisted minimum is zero.
- [Official Android app listing](https://play.google.com/store/apps/details?id=com.airlift.wirelessone):
  package `com.airlift.wirelessone`, second-generation WirelessOne.
- Saved HU activity evidence identifies
  `com.airlift.wirelessone/crc645d6edbbb0c80cc02.MainActivity`, a MAUI application.
  No saved installed APK was found. HU connection on this pass returned
  `No route to host`.
- An **offline reference**, version 2.3.0 (100305), was downloaded from
  `https://d.apkpure.net/b/APK/com.airlift.wirelessone?version=latest`.
  Base APK signature verification succeeded, with a Google source stamp and
  signing-certificate SHA-256
  `f312260b3a1fbf1daa0f9da3a471be9e111f15a4713fd5b19867277549f600cd`.
  This is not a substitute for comparing Beau's installed APK. Phoenix requires
  that same installed app version and signer before it allows target writes.
- Its manifest has a launcher entry, but no exported pressure-control activity,
  receiver or deep link. Managed code inspection found direct BLE pressure fields.
  This is an interoperability implementation, **not a published/supported Air Lift SDK**.
- Reference APKs, extracted assemblies and decompiled files remain private research
  artifacts under `/home/beauhill/ram-phoenix-research/wirelessone` and local
  `HU/artifacts/wirelessone`; none are included in Git or shipped in Phoenix.

Observed wire contract, independently implemented in Java:

| Service / characteristic | Observed meaning |
| --- | --- |
| FA00 / FA01 | Application mode; 0 is ordinary operation |
| FB00 / FB01 | Flags byte, then little-endian uint16 measured pressure in kPa |
| FB00 / FB04 | Diagnostic flags; this first implementation requires all clear |
| FC00 / FC10 | Little-endian uint16 target kPa, followed by active preset 0–3 |
| FC00 / FC56, if present | Air-tank mode; must be checked and disabled for spring profiles |

All UUIDs have suffix `-0000-1000-8000-00805f9b34fb` and a `0000` prefix.
Flags FB01 bit 0 and bit 3 indicate compressor and exhaust activity. Reference
conversion uses 6.89476 kPa/psi, rounded to whole kPa, with minimum 35 kPa.
Phoenix's target write is exactly FC10 `[kPa low, kPa high, 0]`; it never writes
stored preset values/names, tank mode, firmware, safety override or fault clearing.
Synthetic unit-test bytes are **not live truck captures**.

## Implemented candidate

- Native Air suspension entry in the Vehicle Info card; accessible pressure page
  with graphite surfaces, four profiles, measured/target separation and connection status.
- Owner selects an already paired WirelessOne manifold. Initial pairing stays in
  the manufacturer's app. No BLE scan, forced bonding or credential copying.
- Owner enters the installed spring kit's allowed target range in a keyboard-safe
  Setup form with fixed Save/Cancel. Unknown limits keep writes disabled. Out-of-range
  profiles are not silently clamped; Empty follows a higher configured minimum.
- BLE ownership follows the visible Vehicle Info card or pressure page; hiding the
  card or leaving both releases the session. Serialized reads and one explicit target write. Controller
  mode, diagnostics, target and measured pressure must all be fresh (6.5 seconds).
  Disconnection, reverse, leaving the app or opening WirelessOne cancels queued
  work and invalidates measurements. Reconnection performs reads only.
- No persisted/automatic pressure command. Dispatch expires after 750 ms; one
  write attempt, no retry. Reported target must match in a read started after write
  completion before Phoenix says the target was accepted. This still does not
  prove the measured spring pressure has settled.
- Opening WirelessOne through launcher app routes releases Phoenix's BLE session.
  Returning to Phoenix requires Connect, preventing the apps from competing.
- Android 12 fallback and Android 13 write APIs; existing OBD SPP and CAN remain
  separate. No CAN spring-pressure identifier was guessed.
- All nonzero diagnostic flags conservatively block controls. Mapping individual
  pending/history/failed bits can be refined after a real capture; Phoenix does not
  clear codes to enable itself.

## Verification and next parked session

`testDebugUnitTest assembleDebug assembleDebugAndroidTest` succeeded: **81 tests,
zero failures/errors/skips**, including 16 new synthetic protocol/session tests.
The capture helper's `--help` path was checked during the original offline build.
APK SHA-256: `7139df033119da921916f3ee01b35a25642ad9060d2d0a7b694c72bff3d6111b`.
Local candidate: `HU/artifacts/phoenix-launcher-0.5.8.apk`.
`HU/artifacts/wirelessone-0.5.8-preview.png` is a desktop design preview with sample
values and an example kit, not an Android/HU screenshot.

## Live read-only check, 2026-10-27

Captured the installed APK set without launching WirelessOne. Version 2.3.0,
code 100305 and its signing certificate match the inspected reference. The HU
supports BLE and already pairs with `WirelessONE-052680`. Beau reports a 100 psi
maximum on the manual controller; this is an owner-reported limit, not independent
identification of the installed air-spring kit.

The device-only `device-air` probe connected to that single bonded manifold,
discovered services, read fields serially, then disconnected. It implements no
characteristic or descriptor writes and makes no pressure/configuration changes.
Captured fields:

| Field | Raw bytes | Interpretation |
| --- | --- | --- |
| FA01 | `0001` | Ordinary mode (first byte 0) |
| FB01 | `002600` | Idle flags, 38 kPa ≈ 5.51 psi measured |
| FB04 | 20 zero bytes | No diagnostic flags |
| FC10 | `230000` | 35 kPa ≈ 5.08 psi target; manual preset 0 |
| FC56 | Absent | No optional tank-mode field |
| Device information | ASCII | Air Lift, model 72708, firmware 1.2.0 |

Evidence: `/home/beauhill/ram-phoenix-research/wirelessone-readonly-20261027.txt`;
APK capture under `wirelessone/installed-20261027/`. This establishes live read
compatibility with the observed fields. Actual inflation/deflation, target-write
acknowledgment, handoff behavior, keyboard behavior and final panel layout remain
unverified at the time of that initial read-only capture.

### Explicit 5 psi test

Beau subsequently authorized one parked 5 psi test. With launcher 0.5.15, the product
page showed a fresh5 psi controller target, measured about6 psi, normal mode and no
fault flags. Selected Empty, checked the enabled “Set5 psi” action, and pressed it
**once**, without retry. The GATT write completed and the required post-write
FC10 read returned `230000` (35 kPa/manual preset0). The UI reported “Target accepted”;
measured39 kPa remained about5.66 psi, rounded6. Higher profiles were not applied.

Because the reported target was already35 kPa, this verifies the single-write/
readback path at the existing minimum, not that a changed target is obeyed or a
pressure transition settles. Higher-pressure load tests, BLE loss/reverse and
official-app handoff still need separate checks. Evidence outside Git:
`wirelessone-before-5 psi-test-20261027.png`,
`wirelessone-after-5 psi-test-20261027.{png,xml}` and
`wirelessone-5 psi-test-20261027.log` in the private research folder.

1. Obtain the kit model/part number and its allowed pressures. Beau has been asked;
   do not substitute the truck model or an assumed LoadLifter kit.
2. Confirm whether the HU's WirelessOne app already pairs and controls the manifold.
3. When the HU is available, use the strictly read-only capture helper:
   `python3 tools/capture_wirelessone.py 192.168.0.225:5555 /absolute/new/evidence-dir`.
   Compare the installed app version and certificate with the reference before
   treating that reference as representative. It pulls APKs without opening the app.
4. Back up/install the cumulative candidate with the existing installer only while
   the display is on. Inspect card readability, full pressure page and keyboard-safe
   Setup. Pair using WirelessOne if needed; close phone/remote connections as required
   by Air Lift's single-client design. Do not rewrite its saved presets.
5. Read-only validation first: inspect `adb -s 192.168.0.225:5555 logcat -s PhoenixAir`
   for FA01, FB01, FB04, FC10 and optional FC56. Compare actual/target readings with
   the controller and inspect any diagnostic flags. Debug reads contain no pairing keys.
6. A later explicit parked pressure test must confirm target readback and measured
   response at the verified kit minimum and an agreed load profile. None has run.
7. Test handoff to WirelessOne, reverse, loss of BLE, foreground changes and reconnect.
   Confirm none reapplies a selected profile or leaves stale pressure displayed.

The manifold continues maintaining its existing setting after a client disconnect;
Phoenix cannot undo an already accepted change by closing Bluetooth. An interrupted
unconfirmed write is reported as uncertain and never retried automatically.

## Long-press preset editing — launcher 0.5.16

Holding any of the four load-profile tiles opens an editor for its name and
whole-number pressure. A small “Hold to edit” hint appears beside the profiles.
Names allow up to 40 characters; pressure must fit the configured kit limits
(currently 5–100 psi). Existing names/80/50/30/minimum defaults remain until edited.

Save/Cancel stay above the keyboard, and the keyboard Done action also saves.
Back/Cancel discard edits. Saved definitions persist on the head unit as a name/
pressure pair; no manufacturer preset slots are overwritten. Editing clears the
current selection. Saving does not send a pressure command: applying requires
selecting a tile and pressing Set, which now reads that tile's saved pressure.
If kit limits later change, an out-of-range custom preset stays blocked instead
of silently changing its target. Unsaved fields survive activity recreation.

Verified on the actual HU: long press opens the editor, Save/Cancel fit above the
real keyboard, invalid 0 psi retains the form and is not persisted, and a temporary
name/79 psi definition saved durably and rendered correctly. Restored the original
“Full Load & Trailer”/80 psi definition, verified Cancel discarded a temporary
edit, and verified persistence after a launcher process restart. All four tiles
expose long press; no target was selected automatically. **No suspension Set
action was pressed and no pressure command was sent during these editor checks.**
The camera foreground monitor remained running.

All **92 launcher unit tests** passed; app and instrumentation APKs built.
Backup: `/home/beauhill/ram-phoenix-backups/air-preset-editor-hmip65fm`.
Private evidence: `air-preset-editor-live-20261027.txt`,
`air-preset-editor-keyboard-20261027.png` and
`air-preset-editor-final-20261027.png` under the research folder.
APK SHA-256: `548708570ec571e84bf8f408c850a62fff6276a16e5afdb6fd761a47d6d5e7f6`.
