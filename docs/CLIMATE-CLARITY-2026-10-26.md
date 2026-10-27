# Climate clarity — 2026-10-26

Branch `codex/climate-clarity-2026-10-26`, based on `2976bce` with working oil
temperature, TPMS and the previous camera fixes. Launcher **0.5.2 / code 9**;
camera remains **0.3.4 / code 7**. Main is unchanged.

## User feedback and result

Beau found the replacement climate symbols harder to understand and requested
Power and Auto on the portion of the dashboard card that stays visible.

- Power and Auto now occupy the permanent top row, alongside Sync. They are
  outside `climate_expanded`, so the card's expand/collapse action cannot hide
  them. Their selected styling continues to follow the vehicle's reported state.
- Airflow buttons say **FACE**, **FACE + FEET**, **FEET**, and **FEET + DEFROST**.
  The expanded controls say **FRONT DEFROST**, **REAR DEFROST**, **RECIRC**,
  **MAX A/C**, **A/C**, and **HEATED WHEEL**.
- Each temperature zone has its own minus/value/plus row. Wider seat buttons
  below it show **HEAT** or **VENT**, plus **OFF**, the reported level, or a dash
  when unavailable. The side label identifies Driver or Passenger.
- Fan control uses a visible FAN label, minus/plus buttons, slider and reported
  level. Full climate uses the same plain-language labels and removes the
  abstract airflow, seat and system glyphs. Graphite surfaces and amber selected
  borders remain. Navigation retains familiar chevron/full-screen icons.
- Power/Auto and dashboard temperature steppers are 44 dp tall; dashboard seat
  buttons are 44 dp tall and use half of their respective zone's width.

Control IDs, CAN update codes and button commands are unchanged. In particular,
Power still sends `cmd(CANBUS, 6, {1, 16})`; Auto sends
`cmd(CANBUS, 6, {1, 2})`, matching the factory PA panel mapping. Labels are not
optimistic state: displayed levels and selections still depend on IPC updates.

## Verification

- `testDebugUnitTest assembleDebug` passed with **31 launcher tests, zero
  failures/errors/skips**. No new unit tests were added for these layout edits.
- Installed on the actual 1080×1920 head unit with its existing density and font
  scale. Inspected screenshots of the collapsed card, expanded card, full climate
  page and return to the dashboard. Labels fit and Power/Auto remain visible.
- UI hierarchy confirms Power/Auto are present, clickable and visible in both
  card states. The HU's accessibility window reports a lower boundary at y=1693
  even while screenshots show the complete dashboard through y=1888. Lower
  controls therefore received visual review; zero accessibility bounds alone
  were not treated as proof of actual visual clipping.
- The first compact label arrangement squeezed the seat controls; the final
  layout gives each zone separate temperature and seat rows. Map/media/vehicle
  cards continue to resize around the taller climate card; inspected both
  collapsed and expanded arrangements.
- Navigation-only QA expanded/collapsed the card and opened/closed full climate.
  **No HVAC controls were pressed during this QA.** Beau confirmed ignition/ACC
  only. The previous physical climate test remains evidence for the unchanged
  command path; Power/Auto actuation was not newly exercised in this pass.
- Restored the original `climate_open=false` preference. No audio controls,
  vehicle settings or diagnostic properties were changed for this pass.
- Live oil temperature and TPMS continued after installation; the settled
  snapshot showed **89°F** oil temperature and **61 / 63 / 72 / 75 psi**.

Screenshots and build log are retained outside Git in
`/home/beauhill/ram-phoenix-research/` on Crimson, including
`climate-clarity-collapsed.png`, `climate-clarity-expanded.png`,
`climate-clarity-full.png`, `climate-clarity-final.png`, and
`climate-clarity-build.log`. Matching screenshots are in the Windows workspace's
`artifacts/` folder. Location-bearing dashboard captures are not committed.

Rollback launcher 0.5.1:
`/home/beauhill/ram-phoenix-backups/climate-clarity-20261026.lUqCyM/launcher-0.5.1.apk`.
Use `adb -s 192.168.0.225:5555 install -r -d APK` if needed.
