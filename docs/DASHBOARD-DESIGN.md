# Dashboard launcher — design spec (2026-10-21)

Agreed direction after reviewing the stock launcher and the stock climate panel
photos. Supersedes the "icons + gauges" layouts. Mock: `docs/design/phoenix-dash-v2.png`
(rendered from `phoenix-dash-v2.html`, panel-native 1080×1920). Mock only — not a
panel capture.

## Layout, top → bottom (portrait 1080×1920)

| Zone | Height | Content |
|---|---|---|
| Status bar | 64 | clock, date, wifi, BT device, volume |
| **Dock** | 200 | 360 Cam · Phone · Maps · Radio · Music · Maintenance · Apps. **On top** by Beau's call — reaching to the bottom of a panel this tall is awkward. |
| **Map** | 640 | Always-on live map, Google Maps SDK for Android (Play Services confirmed present on the unit, full Play Store). Next-turn banner + ETA pill. Tap → full-screen nav. |
| Now playing | 200 | art, source (BT / radio / app), title, artist, transport |
| Truck card | 330 | traced RAM outline with per-tire TPMS (low tire → amber), outside temp, odometer, doors, **maintenance due** lines from the maintenance tracker (WISHLIST). Data-pending until canbus indexes captured; renders dimmed, never fake. |
| **Climate** | 348 | see below |

Dropped vs. stock launcher: tach/speedo (duplicate the cluster), the generic SUV
graphic, the gauges strip.

## Climate cluster — matches the stock panel minus REAR

Source: stock climate panel photo (2026-10-21). Beau: "the only thing we drop is the
rear controls, everything else is valid."

Row 1 — modes
- Power (⏻)
- AUTO
- DUAL (sync/unsync driver + passenger temps)
- A/C
- Front defrost
- Rear defrost (window grid; this is the rear *glass*, kept — not the rear-zone controls)
- Recirculation
- ~~REAR~~ (rear-zone climate) — **dropped**, not on this truck

Row 2 — zones
- Driver: heated seat (3-level), ventilated seat (3-level), temp − / value / +
- Passenger: temp − / value / +, ventilated seat (3-level), heated seat (3-level)
- Seat heat/cool have **no physical buttons** on the bezel, so these must be on-screen.

Row 3 — air
- Fan − / level bars / +
- Vent mode segmented: face · face+feet · feet · feet+defrost

Physical bezel buttons (power, volume, temp knobs, fan) still exist; the on-screen
cluster mirrors state and is the only path for seat heat/cool and vent mode.

## Data plumbing (all TODO, needs the truck on adb)

Every climate control rides the canbus IPC (`com.syu.canbus` → LSEC `Main.DATA[]`
shared array), same pipe as the reverse flag. Capture plan, one truck session:

1. Arm logcat + dump the IPC array.
2. Press each physical/stock control once, in order: power, AUTO, DUAL, A/C, front
   defrost, rear defrost, recirc, driver temp ±, passenger temp ±, fan ±, vent mode
   cycle, driver seat heat ×3, driver seat cool ×3, passenger seat heat ×3,
   passenger seat cool ×3.
3. Record the index + value for each → `docs/CANBUS-INDEXES.md`.
4. Find the *write* path (how the stock climate app sends a command back to the
   MCU) — read side is not enough for toggles.

Batched with the camera measurement items (does `fyt_car_reverse` flip with the
stock camera disabled; are camera IDs 14/10 visible to a third-party app; what is
actually on device 10) and the truck-card indexes (TPMS ×4, odometer, doors,
outside temp).

## Still needed from Beau

- Google Maps API key, in `local.properties` on the build box (gitignored), never in chat.
