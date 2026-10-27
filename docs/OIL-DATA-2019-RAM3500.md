# Oil data — 2019 RAM 3500 SRW

2026-10-26, branch `codex/oil-data-2019-3500`, based on visual-refinement commit
`d9f83ab`. Launcher **0.5.1 / code 8**. Camera remains **0.3.4 / code 7**.
Main is unchanged. Truck: 6.7L Cummins, 68RFE, SRW.

## Verified engine oil temperature

The active CAN-box profile is **2163062, PA_RAM_22_Low**, reported by CANBUS
subscription **1000**. Earlier LZ-profile assumptions in the repository do not
describe this setting. `getInts(CANBUS, 1000)` does not return that configuration
on this unit; subscribe to it. Callback 1037's value 1442166 is other metadata.

The factory accessory-gauge UI reads **CANBUS update 235** as engine oil
temperature. Evidence from the installed vendor APKs, decompiled on Crimson:

- `com.syu.carinfo.xp.ziyouguang.PAJeepAccessoryGaugesAct` and `PAJeepGauges`
  label update 235 as oil temperature. AAPT resolves its resource label to
  `Oil Temp`, distinct from 234 coolant and 236 transmission temperature.
- The PA decoder `k0.mc.U2`, packet case **82 decimal** (a CAN-box protocol
  packet type, not the truck's CAN arbitration ID), publishes update 235 from
  the unsigned big-endian pair at offsets `i2 + 8` and `i2 + 9`.
- The vendor display formula is `value = (raw & 0x7FFF) - 40`. Bit 15 set
  means Fahrenheit; otherwise the value is Celsius. Normalize to Celsius for
  `VehicleData`, then let the existing formatter produce the Fahrenheit display.
- The factory activity requests this gauge page using CANBUS command **4**,
  integer arguments **{82}**. The launcher makes the same read/page request
  after connecting to the confirmed profile. This is not an oil-life reset.

Live words observed during cooling with ignition RUN, engine stopped:

| Raw decimal | Raw hex | Factory temperature |
| --- | --- | --- |
| 32901 | 8085 | 93°F |
| 32899 | 8083 | 91°F |

Beau reported **91°F** on the cluster after the second reading. The launcher's
live screenshot also showed **91°**. The earlier 98°F report preceded these
observations; no fixed offset was added to reconcile readings taken at different
times. Verification so far is at these cool, stationary values; an ordinary
future drive can confirm agreement at normal operating temperatures.

`RamPaOil` gates this field on the confirmed profile and a connected IPC source,
rejects uninitialized zero, invalid/sentinel words and implausible temperatures,
and clears its state on disconnect. The normal vehicle card and optional OBD
card use the same composite oil-temperature getter, with the existing OBD value
as a fallback. The vendor service pushes cached values and changes; a connected
IPC does not prove that its upstream CAN feed is fresh. No independent CAN
heartbeat or fixed cache expiry has been established for this field.

## Oil life remains unresolved

Beau's cluster reference is **96%**. No oil-life value has been wired into the
launcher and no unidentified counter is labeled as oil life.

Read-only observations through the existing OBD worker and its single SPP socket:

| Route / request | Observed response | Interpretation |
| --- | --- | --- |
| ECM 7E0, 21D2 / 212D | `7F 21 11` | Service unsupported on this ECM |
| ECM 7E0, 220168 | `7E8 03 7F 22 31` | Identifier rejected |
| ECM 7E0, 22069F | `7F 22 31` | Identifier rejected |
| ECM 7E0, 223813 | `7F 22 31` | Identifier rejected |
| ECM 7E0, 8100–81FF | 256 reads, zero positive replies | No supported identifier found in this range |
| ECM 7E0, A000–A0FF | 256 reads, 148 positive replies | Raw results saved; none established as oil life |
| Candidate 720/4A0 and 730/4B0, 220000 / 223813 | Empty replies | These routes are unverified; absence proves no oil-life claim |
| 742/4C2, 220000 / 223813 | `7F 22 31` | Module answered; these identifiers rejected |

The A0 scan used headers and an open reply filter, so multi-frame replies were
retained rather than discarded by a `startsWith("62")` check. A matching byte or
plausible scale alone would not identify oil life. The tested body routes are
candidate addresses, not an established instrument-cluster map.

The original [OBDb RAM 3500 definitions](https://github.com/OBDb/Ram-3500) supplied
candidate ECM reads. Its 223813 oil-degradation definition is tagged for newer
vehicles; rejection here does not establish a universal security-gateway block.
The official [ScanGauge Chrysler/RAM table](https://www.scangauge.com/support/x-gauge-commands/dodge-chrysler-jeep/)
provides Cummins-specific 81xx candidates, but does not establish oil life for
this truck. Definitions for gasoline engines and newer platforms are not proof
of a supported field on this 2019 Cummins.

Factory PA source inspection has not found a labeled oil-life percentage. The
misleadingly named `Xp_374_OilDisplayActi` is a fuel-economy/trip page. Update
395 is another unit-tagged temperature, as shown by `PAJeepRaceCoolDown`, not
an oil-life percentage. No controls on that page were activated.

Next useful evidence is a verified 2019 Cummins/cluster identifier or a passive
capture containing an identifiable oil-life message. A normal future decrease
from 96% would provide a useful second comparison point; do not reset the
maintenance counter as a discovery technique.

## Diagnostic controls, validation and rollback

`persist.phoenix.obd.probe` is a single-shot bounded read facility handled by the
existing worker. Example: `7E0:21D2,212D,220168;740/4C0:22A0A5`. It validates the
entire specification before sending, permits only explicit 01/21/22 reads, allows
at most 12 requests, and clears the property before executing. It rejects resets,
writes, routines, adapter commands and malformed specifications. It does not open
a second adapter connection. The older DID scanner remains a separate diagnostic
control. `persist.phoenix.can.oilprobe=1` enables additional factory-gauge logging.

- `testDebugUnitTest assembleDebug` passed: **31 launcher tests, zero failures,
  errors or skips**. Five new tests cover read-only probe validation; five cover
  oil-temperature units, live words, invalid data, profile/connection gating and
  reconnect clearing. Existing OBD, TPMS and worker tests remain green.
- Installed the tested launcher with `adb -s 192.168.0.225:5555 install -r`.
  Confirmed live oil-temperature display and OBD/TPMS recovery after installation.
- Engine remained stopped during this investigation. No ECU settings, security
  access, diagnostic-session changes or service-counter resets were requested.
- Read logs, AAPT layout/resources, PA decoder, build output and screenshots are
  retained outside Git at `/home/beauhill/ram-phoenix-research/` on Crimson.
  Relevant files: `oil-a0-scan.log`, `oil-body-route-probes2.log`,
  `oil-probe-20261026.log`, `oil-final-live.log`, `oil-build.log`, and
  `oil-temp-final.png`. Location-bearing screenshots stay out of the repository.
- Rollback launcher 0.5.0 is saved at
  `/home/beauhill/ram-phoenix-backups/oil-data-20261026.A42ieI/launcher-0.5.0.apk`;
  use `adb -s 192.168.0.225:5555 install -r -d APK` if needed.

Beau also confirmed the parked test of the previous refresh: reverse, factory
camera view buttons and climate controls work. See [WORKLIST.md](WORKLIST.md) for
remaining boot, camera timing and SPLIT-layout work.
