# CAN-preferred engine gauges — 2019 RAM 3500 SRW

2026-10-27. Branch `codex/can-gauges-2026-10-27`, based on WirelessOne candidate
`700d76f`. Launcher **0.5.9 (16)**. Main is unchanged. **Built, not installed or
live-tested: the HU returned `No route to host`.** Earlier oil-temperature
verification remains valid; the three additional fields require a truck comparison.

## What was actually found

The earlier verified CAN reading was **engine oil temperature**, not engine oil
pressure. Oil pressure, coolant and transmission temperature previously came from
the known OBD routes. This pass traced the additional factory fields in the APKs
already pulled from this head unit:

| Reading | SYU CANBUS update | PA packet 82 offsets, relative to `i2` | Encoding |
| --- | --- | --- | --- |
| Coolant | 234 | 6–7 | Big-endian uint16; bit 15 selects °F, low 15 bits minus 40 |
| Oil temperature | 235 | 8–9 | Same temperature encoding; previously cluster-verified |
| Transmission temperature | 236 | 10–11 | Same temperature encoding |
| Engine oil pressure | 237 | 12–13 | Big-endian uint16; high 2 bits select unit, low 14 bits are tenths |

These are **IPC update codes**, not the truck's CAN arbitration IDs. Packet 82
is the CAN-box protocol's message type. The supported HU profile remains
**2163062 / PA_RAM_22_Low**, obtained by subscribing to CANBUS 1000.

Evidence on Crimson, outside Git:

- `/home/beauhill/ram-phoenix-research/pa-decoder/sources/k0/mc.java`, `U2`, case 82:
  publishes all four words through `st.z0(234..237, ...)`.
- Factory `com.syu.carinfo.xp.ziyouguang.PAJeepGauges` and
  `PAJeepAccessoryGaugesAct`: display the three temperatures and pressure units.
- AAPT layout/resource evidence in `pa-oil-gauges-layout.txt` and
  `canbus-resources.txt`: coolant, transmission and oil-pressure labels resolve to
  `str_coolant_temp`, `str_trans_temp` and `str_oil_pressure` respectively.
- `/home/beauhill/jadx-out/syu.ms/sources/k0/st.java`, `z0`: suppresses a scalar
  notification when its cached value is unchanged. Callbacks are not periodic
  packet-receipt acknowledgments.

Pressure units: tag 0 = psi, tag 1 = kPa, tag 2 = bar. Normalize to psi using
6.8947573 kPa/psi and 14.503774 psi/bar. The vendor UI treats tag 3 as a psi
fallback; its meaning is unconfirmed, so Phoenix rejects it. It also rejects
invalid words, all-ones payloads and readings above its 250 psi plausibility
ceiling. That ceiling is an input-validation bound, not a claimed engine limit.

## Candidate behavior

- Each gauge independently prefers a valid mapped CAN value on the supported
  profile, then falls back to its existing OBD value, then displays unavailable.
  CAN selection does not depend on an OBD socket being connected.
- The Vehicle Info card and optional vehicle/OBD card use the same value/source
  pairs. The former summarizes `GAUGES: CAN`, `GAUGES: CAN + OBD` or `GAUGES: OBD`;
  the optional card also shows each tile's actual source. Adapter connection alone
  is not used to infer a gauge's source.
- Units and existing Fahrenheit presentation are preserved. No pressure offset or
  calibration against an old cluster snapshot is invented.
- An initial untagged pressure word `0` is ambiguous with the vendor's default
  empty cache. It remains unavailable until a valid nonzero encoded pressure word
  has been seen in that connection. A unit-tagged zero is distinguishable and can
  be used immediately; a later untagged zero after initialization is also valid.
  Disconnects, profile changes and invalid pressure data clear that qualification.
- All cached gauge values clear on IPC disconnect. A profile change cannot reuse
  values from the previous decoder. Invalid one-field data does not discard other
  valid fields.
- The app requests factory gauge page 82 with CANBUS command 4, `{82}`, as the
  vendor activity already does. This is a read request, not an ECU write or reset.
- OBD polling remains available for comparison and fallback in this candidate.
  This change does not remove the adapter: TPMS, voltage, odometer investigations
  and configured custom readings still use OBD. Suppressing redundant polling can
  follow verified live CAN coverage; it has not been enabled based on cache alone.

The known factory-cache limitation remains: an IPC connection does not prove that
its upstream CAN feed is current. Stable values also produce no callbacks. No
arbitrary expiry was added that would misclassify steady temperatures as loss of
CAN. Independently detecting an upstream CAN-box failure remains further work.

## Build and tests

`testDebugUnitTest assembleDebug assembleDebugAndroidTest`: **92 tests, no
failures/errors/skips**. Eleven gauge-decoder tests retain the previous oil checks
and cover temperature units, pressure units, invalid/sentinel values, initial-zero
qualification, connection/profile gating and independent availability. Five
value/source tests cover CAN preference, per-field fallback, genuine zero,
nonfinite values and correct source captions. Additional-field examples are
synthetic; they are not recorded truck samples.

APK SHA-256: `4b596457ba2ef01d8bfccdbc4e90462897e015339b0dfd1f9b4fdc6f706ab31f`.
Local candidate: `HU/artifacts/phoenix-launcher-0.5.9.apk`.

## Live ignition-only check, 2026-10-27

Installed cumulative 0.5.9 after backing up the 0.5.4 APK and shared preferences
to `/home/beauhill/ram-phoenix-backups/can-gauges-live-20261027.GNKoTq`.
With OBD disconnected, CAN profile 2163062 returned coolant 234 = 32874,
oil temperature 235 = 32874, transmission 236 = 32876 and pressure 237 = 0.
Coolant displayed **66°F** and transmission **68°F**; Beau independently reported
those exact cluster values. The initial pressure zero remained unavailable,
matching the cluster's dash with the engine off. This verifies coolant and
transmission CAN selection without OBD; it does not establish running oil pressure.
The dashboard source caption and temperature values were inspected in a real HU
screenshot, `launcher-0.5.9-first-live.png`, outside Git in the research folder.

OBD later recovered through the existing channel-1 SPP fallback, returning TPMS,
voltage and standard 01A6 mileage. No additional diagnostic socket was opened.
An operating-temperature/running-pressure comparison and upstream freshness
checks remain pending. Do not start the engine inside the garage for this test.

## Next parked check and quick source rollback

1. Install the latest cumulative candidate while the display is on, preserving
   the earlier pending split, climate-icon, maintenance and WirelessOne work.
2. Capture `adb -s 192.168.0.225:5555 logcat -s PhoenixGauges PhoenixLauncher`.
   Record actual 234/236/237 words and compare simultaneous cluster values, both
   cold and at ordinary operating temperature. Use a parked engine-running
   comparison to establish nonzero oil pressure; ignition-only zero is insufficient.
3. Verify both cards select the same sources. Check CAN readings with the OBD
   connection absent; TPMS/voltage/OBD mileage can be unavailable during that check.
4. Check invalid/unavailable CAN data, reconnection, and application resume.
   Do not stop ToolkitService to simulate failure.
5. For an A/B comparison or quick source rollback, set app-owned property
   `persist.phoenix.can.gauges` to `0`. Engine gauges use OBD while CAN raw logs
   remain available. Restore `1` for CAN preference. No app downgrade, vehicle
   coding, service-counter reset or factory CAN-profile change is needed.

See [OIL-DATA-2019-RAM3500.md](OIL-DATA-2019-RAM3500.md) for the prior verified
oil-temperature samples and [WORKLIST.md](WORKLIST.md) for remaining device checks.
