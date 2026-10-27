# Maintenance refinement — 2026-10-26

Updated 2026-10-27 as the work crossed midnight in America/Denver.

Branch `codex/maintenance-2026-10-26`, based on climate-icon commit `b9cc051`.
Launcher **0.5.7 / code 14** is built, **not installed**. The HU was offline at
both the initial and final connection checks (`No route to host`). Camera remains
0.3.4. Main is unchanged.

## Report and scope

Beau requested automatic CAN/OBD odometer capture and a major refinement of the
maintenance app. He could type into the existing forms but could not find a way
to accept the entries. He reports the cluster is **around 71,000 miles**. That
estimate is a diagnostic comparison target; it has not been entered as actual
vehicle mileage or a service record.

The old app only stored a manually entered odometer. Its comment claiming an
OBD fallback was not backed by an actual reader. The old forms did define an OK
button, but their unscrollable alert-dialog contents had no explicit IME handling.
The reported missing action could not be reproduced with the HU offline.

## Forms and presentation

- Rebuilt the maintenance home with a mileage/source card, due/soon/setup counts,
  reminder/history tabs, explicit Edit and Record service actions, and a persistent
  Add reminder action. Graphite surfaces, restrained state colors and familiar
  tool/edit/check icons match the revised launcher.
- Replaced data-entry dialogs with a non-exported full-screen editor. Fields live
  in a scroll view; Save and Cancel live in a separate footer. The editor requests
  `adjustResize`, avoids `FLAG_FULLSCREEN`, hides bars through the inset controller,
  and sets no-extract/no-fullscreen IME options. IME Done is handled when provided
  by the keyboard. This still needs an actual FYT keyboard check.
- Validation keeps the form open, identifies invalid fields and preserves input.
  Blank mileage differs from zero. Names, whole numbers, maximum values, future
  service dates and inconsistent dated service mileage are checked.
- Back/Cancel protects unsaved edits with a discard confirmation. Native view-state
  IDs preserve fields across recreation; private drafts saved on pause support
  returning after camera/app interruptions. Drafts are not service records and are
  cleared on explicit save/discard. Abrupt-power-loss behavior remains untested.
- Add/edit reminders separately from recording service. Service entries have date,
  mileage and notes, and can be reviewed/corrected/deleted in History. Backdated
  entries do not replace a later service baseline. Deleting a reminder keeps its
  historical entries; deleting a record recomputes the reminder's prior baseline.
- Export now uses Android's file picker to choose a JSON backup location, rather
  than writing a hidden app file and only showing its path. Import is not implemented.

The active native layouts are built in `MaintenanceActivity`,
`MaintenanceEditorActivity` and `MaintenanceUi`. The unused legacy maintenance
layout XML files were removed. Stable form IDs live in `maintenance_ids.xml`.

## Automatic odometer

The existing single-worker Bluetooth SPP reader now checks mileage at most every
30 seconds, with the physical ECM route **7E0 → 7E8**:

1. **01 A6**, standard odometer: four unsigned big-endian bytes, divided by 10
   for kilometers, then by 1.609344 for miles. An exact positive response can be
   accepted automatically. This is protocol support, not proof of support on this
   particular 2019 Cummins.
2. If A6 is unavailable, **22 03BC**, a RAM community candidate: four unsigned
   big-endian bytes, divided by 1000 for kilometers. This stays quarantined until
   the user compares it with the cluster in Mileage source and confirms it once.
   The source is then updated automatically on later successful reads.

The decoder requires the complete expected reply and byte count. Negative,
truncated, concatenated, out-of-range and all-ones replies are rejected. Zero-filled
automatic readings are ignored for this known used truck. Zero remains valid for
manually entered historical service mileage.

Accepted mileage cannot silently decrease or jump implausibly fast. Suspect values
become review candidates. Manual override remains manual even if OBD reconnects.
Automatic mode retains its last accepted reading when disconnected, explicitly
labeled with the last update date/time. Live labels require the current connected
reader's source and value to match; a candidate is not represented as live data.

`OdometerRepository` keeps telemetry in separate preferences from the service book,
so incoming readings cannot overwrite edited records. Recording a historical service
does not update the current odometer. The old manual value migrates as a labeled
fallback, not as a verified vehicle reading.

No verified CAN-box odometer mapping was found for profile 2163062. No CAN index
has been guessed. Mileage is not estimated from GPS/speed or distance since DTC
clear. The adapter performs read requests only; recording maintenance does not
reset a truck service counter or oil-life monitor.

Primary references:

- [SAE J1979 signal definitions](https://github.com/OBDb/SAEJ1979/blob/main/signalsets/v3/default.json)
  and the [OBDLink PID list](https://www.obdlink.com/obd-apps/obdlink-app/).
- [RAM 3500 definitions, pinned snapshot](https://github.com/OBDb/Ram-3500/blob/00a8cbb4e2a7a9063d10675691050fc9d4c9f432/signalsets/v3/default.json).
  The manufacturer definitions are community/testing data across other trucks,
  not confirmed captures from this 2019 6.7 Cummins. The listed 043B odometer is
  coarse (8.192-mile steps), so it is not used for normal odometer updates.
- Saved ECM A0 scan: 148 replies parsed. An arbitrary-scale numeric match from
  A040 was near 73,731 miles, but that does not identify it as an odometer. It is
  not integrated or accepted as a vehicle reading.

## Persistence and reminder calculations

- Existing `maintenance` preferences and the `json` key remain. Version-1 items,
  notes, last mileage/date and manual odometer migrate without invented dates.
  Existing last-service baselines become history entries with stable legacy IDs.
- Version 2 adds history and date-only ISO values. Calendar months use
  `LocalDate.plusMonths`, including month-end/leap-year behavior. Saved dates keep
  their calendar day when the system timezone changes. Epoch fields remain for
  backward compatibility/export.
- Exact due mileage/date is Due now, rather than merely Due soon. Unknown required
  mileage is not displayed as Current. The dashboard avoids promising a future
  service interval when required data is missing.
- Loading does not re-save the book. The previous loader re-wrote preferences
  whenever the dashboard constructed a store. The launcher now caches its service
  book while visible and refreshes it on resume.
- Mutations reload current persisted data under a shared lock before committing,
  preserving unrelated changes from another editor. Save success requires a
  successful durable preference commit. Unreadable data is preserved, exportable
  and protected from automatic replacement with starter defaults.
- Starter intervals are retained as editable existing reminders, not newly verified
  manufacturer recommendations. No service history is fabricated for them.

## Verification and remaining work

`testDebugUnitTest assembleDebug assembleDebugAndroidTest` passed: **65 unit tests,
zero failures/errors/skips**. New coverage includes legacy migration/round trips,
failed commits, stale editors, telemetry isolation, due-date boundaries, timezone
changes, history correction/deletion, malformed input, 32-bit mileage decoding,
source confirmation, stale candidates, manual precedence and suspect jumps.

The device-only `MaintenanceProbe` compiles. It requires an awake display and no
existing new-reminder draft. It opens the real editor/keyboard, checks that Save
and Cancel are entirely above the IME, verifies invalid input stays open, saves a
uniquely named temporary reminder through the Done action, verifies persistence,
and removes only that QA reminder/draft before reporting completion. It has **not
run on the HU** and is not a substitute for normal user testing.

The desktop design preview uses the app's actual icon assets and palette, but
illustrative values/layout and a simulated keyboard. It is not an Android screenshot:
`artifacts/maintenance-0.5.7-preview.{svg,png}` outside Git. Build logs and APKs are
in the Crimson worktree/research folder, with a final APK copied into Windows
`artifacts/phoenix-launcher-0.5.7.apk`.

Next parked checks:

1. Back up current preferences/APK, install the latest build, and inspect reminder,
   record, history and mileage-source pages at the HU's actual density/font scale.
2. Open the real keyboard in every form; confirm Save/Cancel remain reachable.
   Save a reminder and a backdated service, correct it, reopen and confirm values.
   Interrupt a draft with camera/app navigation and confirm restoration.
3. Capture **01A6** and **2203BC** replies. Compare the exact decoded mileage with
   the cluster; do not treat the approximate 71,000 as confirmation. If neither
   responds, further read-only ECU/IPC investigation is still needed.
4. Confirm live → cached → reconnect labels and manual override. Export a real
   backup and inspect it. Repeat existing split/camera/climate checks in this build.

```sh
python3 tools/install_launcher.py 192.168.0.225:5555 apps/launcher/app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.0.225:5555 install -r apps/launcher/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 192.168.0.225:5555 shell am instrument -w -e operation maintenance com.ramphoenix.launcher.test/com.ramphoenix.launcher.PaneWindowProbe
adb -s 192.168.0.225:5555 uninstall com.ramphoenix.launcher.test
```

For a bounded diagnostic capture through the already-owned SPP connection:
`persist.phoenix.obd.probe=7E0:01A6,2203BC,22043B`; the existing probe validates
read-only commands and clears the property after one run. Normal automatic
polling does not use 043B. No broad scan or vehicle write is required by this UI.

This cumulative build includes the pending 0.5.5 split companion and 0.5.6 climate
icons. Their parked checks and one-time local ADB setup remain pending. Before
downgrading, preserve the version-2 backup: older maintenance code can discard
new history fields when it rewrites the JSON.
