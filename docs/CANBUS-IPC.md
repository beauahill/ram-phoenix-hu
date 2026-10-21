# SYU ToolkitService IPC — reverse trigger + climate control plane

Source: jadx decompiles of `com.syu.doublecamera`, `com.syu.canbus`, `com.syu.air` pulled
off the unit 2026-10-21. Everything here is static analysis; items marked **VERIFY** need
one live-truck check before being trusted.

## The service every stock app talks to

| | |
|---|---|
| Component | `com.syu.ms` / `app.ToolkitService` (bind with `Intent().setClassName("com.syu.ms","app.ToolkitService")`) |
| Root interface | `com.syu.ipc.IRemoteToolkit` — txn **1** `getRemoteModule(int moduleCode) -> IBinder` |
| Module interface | `com.syu.ipc.IRemoteModule` — txn **1** `cmd(code, int[], float[], String[])` (oneway), **2** `get(code, …) -> {int hasObj, int[], float[], String[]}`, **3** `register(IModuleCallback, updateCode, update=1)` (oneway), **4** `unregister(cb, updateCode)` |
| Callback | `com.syu.ipc.IModuleCallback` — txn **1** `update(code, int[], float[], String[])` (oneway) |

All parcels start with `writeInterfaceToken(descriptor)`. No AIDL needed; `SyuIpc.java` in
the camera app is a hand-written client. Manifest needs `<queries><package android:name="com.syu.ms"/></queries>`
(Android 11+ package visibility) or `bindService` fails silently.

Module codes (`FinalRemoteToolkit`): MAIN=0, RADIO=1, BT=2, DVD=3, SOUND=4, IPOD=5,
**CANBUS=7**, DVR=9, STEER=10, OBD=12, CAN_UP=14, GSENSOR=17, SENSOR=19, ADAS=20.

## Reverse (MODULE_MAIN)

`com.syu.doublecamera` → `DoubleCamera_Ipc.notify_Main`: registers on MAIN and acts on
update code **12 = `U_BACKCAR`**, `ints[0]==1` → reverse engaged, `0` → cancelled.
Live capture 2026-10-21 08:52: GPIO `car-reverse on` → ~0.8s → `backcar : 1` on this
path, both shifts. The sysfs node `fyt_car_reverse` never moved (stock-app-written, dead
with the stock cameras disabled). So **U_BACKCAR via IPC is the trigger.**

Other MAIN codes the stock camera uses: 41 `steer angle info` (`ints[1]`=SCALE_MAX,
`ints[2]`=ANGLE_MAX — this is how dynamic guidelines get the wheel angle; **VERIFY** what
`ints[0]` carries), 50 `ACC on`, 22 `U_BACKCAR_RADAR`, 23 `U_BACKCAR_TRACK_ENABLE`,
24 `U_BACKCAR_MIRROR`, 81 `U_BACKCAR_360_CAMERA`.

## Which vehicle profile the canbus app is running

`DataCanbus.DATA[1000]` (`U_CANBUS_ID`, readable with `get(MODULE_CANBUS, 1000)` —
**VERIFY** that `get` returns it). The only RAM-with-climate profile in this build:

* `CAR_455_LZ_Dodge_RAM1500` = **6357447** (0x610 1C7)
* `CAR_455_LZ_Dodge_RAM1500_H` = **6422983**

Both route to `Callback_0455_LZ_Dodge_RAM1500` (registers canbus updates 0–144) and the
climate overlay `Air_0455_LZ_Dodge_Ram1500`. The log line `CANBUS_ID:0` seen once in the
08:37 capture is from early boot; confirm the real value live.

## Climate — read side (MODULE_CANBUS update codes, RAM 1500 profile)

Register each code with `update=1`, keep a local `int[] DATA`. Names from
`Air_Activity_All_NewAdd_HP` case `CAR_455_LZ_Dodge_RAM1500` + generic `FinalCanbus`:

| code | meaning | notes |
|---|---|---|
| 10 | POWER | 0/1 |
| 11 | A/C | 0/1 |
| 12 | RECIRC (cycle) | 0/1 |
| 13 | AUTO | 0/1 |
| 16 | REAR DEFROST | 0/1 |
| 18 / 19 / 20 | vent UP / BODY / FOOT (left) | bit flags, combine for mode |
| 21 | FAN level | 0–8 (overlay draws 16px/step, clamps 8) |
| 27 | TEMP driver | raw; °F = `(v*9+320)/10` when `persist.fyt.temperature=1` else °C = `v/2`; -1 NO, -2 LO, -3 HI |
| 28 | TEMP passenger | same encoding |
| 29 / 30 | SEAT HEAT driver / passenger | level |
| 31 / 32 | SEAT VENT driver / passenger | level |
| 36 | cycle-auto | overlay hides a chip when != 1 |
| 37 | TEMP UNIT | 1 = °F |
| 53 | A/C MAX | 0/1 |
| 62 | SYNC (= DUAL on stock panel) | 0/1 |
| 65 | FRONT DEFROST | 0/1 |
| 66 | HEATED STEERING | 0/1 |
| 0–5 | doors: engine/FL/FR/RL/RR/tailgate | `DoorHelper` in `Callback_0455` |
| 137–144 | EQ vol/bal/fad/bass/mid/treb/surround/speed-vol | |
| 98–136 | `U_CARINF_D38_*` / `D3A_*` raw car-info bits | tire pressure / odo likely in here — **map live** |

Codes 27 and 21 were the two that showed up named in the 08:37 capture, matching this table.

## Climate — write side

`DataCanbus.PROXY.cmd(C_CONTRAL=0, ints)` on **MODULE_CANBUS**. For the RAM 1500 profile
(`sendCmd`, line ~1626):

* button press  → `cmd(7, 0, new int[]{24, BUTTON})`
* button release → `cmd(7, 0, new int[]{24})`

The stock UI sends press on ACTION_DOWN and release on ACTION_UP. **VERIFY** whether a
press alone toggles (likely) or the release is required.

BUTTON ids (`ConstAllAirDara` for CAR_455_LZ_Dodge_RAM1500):

| id | button | id | button |
|---|---|---|---|
| 1 | driver temp + | 13 | front defrost |
| 2 | driver temp − | 14 | rear defrost |
| 3 | passenger temp + | 15 | mode: face |
| 4 | passenger temp − | 16 | mode: face+feet |
| 5 | fan + | 17 | mode: feet |
| 6 | fan − | 18 | mode: feet+defrost |
| 7 | power | 20 | passenger seat heat |
| 8 | SYNC / DUAL | 21 | passenger seat vent |
| 9 | A/C MAX | 22 | driver seat heat |
| 10 | A/C | 23 | driver seat vent |
| 11 | recirc | 24 | heated steering wheel |
| 12 | AUTO | | |

Beau's 08:33 observation ("seat controls and rear defrost worked, the rest didn't")
fits: seat/defrost are body-control commands the MCU can send on accessory power; temp/fan/
mode need the HVAC module awake (engine running).

## What this means for the apps

* **Camera**: `ReverseMonitor` now subscribes to MAIN/12 through `SyuIpc` (sysfs kept as an
  OR fallback). `ReverseService` unchanged otherwise.
* **Launcher climate card**: read the table above, send `{24, id}`. No SYU jars, no root.
* **Dynamic guidelines**: MAIN/41 carries the steering scale; wire after a live look.
* **Still needs `com.syu.us` disabled**, or its 360 overlay keeps covering us on reverse.

## Live checklist (next truck session)

1. `logcat -s PhoenixCam` while shifting R: expect `U_BACKCAR -> 1` then `service reverse -> true`.
2. Read `get(7, 1000)` → expect 6357447 or 6422983.
3. Press one climate button on the stock screen with our logger registered on 10–66; confirm the code map.
4. Send `{24, 12}` (AUTO) from our app and watch code 13 flip.
