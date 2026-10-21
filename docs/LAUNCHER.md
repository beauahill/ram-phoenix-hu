# Phoenix Launcher — dashboard home (apps/launcher)

CarPlay-style home for the portrait 1080x1920 panel. Replaces the stock
`com.android.launcher6`. Status row and **dock on top**, then one of two
layouts (the layout icon in the status row toggles; the choice is remembered):

**FULL** — the whole screen is widgets:
```
┌──────────────────────────────┐
│ 4:20 pm  Tue, Oct 21  52° ▤ ⚙│  status row (▤ = layout toggle)
│ [apps][cam][nav][☎][♪][📻][⚙]│  dock
├──────────────────────────────┤
│   live map (osmdroid, dark)  │
│   [nav]            [+/-]     │
│   [🎤 🔍 Where to?        ]  │
├──────────────────────────────┤
│ [art]  Spotify · Falling     │  now playing
│        Madeaux   ◀ ▶ ▶▶      │
├──────────────────────────────┤
│ (SPEED)   [truck]    (RPM)   │  vehicle info card: dials, truck top view
│  psi                   psi   │  with tire psi, dual gauges fuel|temp and
│ (fuel|temp)      (batt|out°) │  batt|outside (VehicleCardView)
└──────────────────────────────┘
```

**SPLIT** — two 50/50 landscape panes (each 1080x~800 px), each one its own
screen. A picker pill on each pane's right edge chooses what it shows:
```
┌──────────────────────────────┐
│ status row + dock            │
├──────────────────────────┬───┤
│                          │ ▣ │  pane A: map / media / drive / app
│      (e.g. map)          │ ♪ │
│                          │ ◴ │
│                          │ ▦ │
├──────────────────────────┼───┤
│                          │ ▣ │  pane B
│      (e.g. now playing,  │ ♪ │
│       large)             │ ◴ │
│                          │ ▦ │
└──────────────────────────┴───┘
```
- Map, media and drive are the same widget views as FULL mode, re-parented
  into the pane (so only one pane can show each; picking a widget the other
  pane holds swaps them). The media card scales up its text and controls.
- **App**: shows an app grid; picking one launches it into that half's exact
  screen rectangle via `ActivityOptions.setLaunchBounds`. Android only honours
  that with freeform windows enabled (see install). Without it the app opens
  full-screen and a toast says so. The chosen app is remembered per pane and
  an Open button brings it back.

Plain framework widgets, **no AndroidX** (small, fast cold start, and it
compiles without Google's Maven). One dependency: `org.osmdroid:osmdroid-android`
for key-free OpenStreetMap tiles (CARTO dark basemap).

## Widgets and where their data comes from

| Widget | Source today | Later |
|---|---|---|
| Map | osmdroid + GPS follow (`MapWidget`) | nav app hand-off already wired (tap nav / search) |
| Now playing | `MediaSessionManager` (`MediaWidget`), any app with a MediaSession: Spotify, SYU radio/music if they publish one | — |
| Speed dial | GPS ground speed (`SpeedWidget`) | canbus speed via `VehicleData.speedKph()` |
| RPM dial, dual gauges (fuel/temp, batt/outside), tire psi | `VehicleData.STUB` → needles at rest, dashes | `CanbusVehicleData` once LSEC IPC `Main.DATA[]` indexes for rpm / tire psi / fuel / coolant / battery / outside temp are captured |
| Dock | intent categories (`CATEGORY_APP_MAPS`, `CATEGORY_APP_MUSIC`, `ACTION_DIAL`), then package fallbacks, then label match ("radio") | pin the real SYU radio/music/phone package names |

The truck in the card is traced from the RAM 3500 crew-cab dually blueprint in
`docs/assets/ram-3500-blueprint.jpg` (top view, 6941 x 2009 mm) by
`tools/gen_truck.py`, which writes `TruckOutline.java` (outer silhouette from
the drawing's pixels, inner features from measured coordinates). Re-run it to
re-trace; never hand-edit the generated file.

`VehicleData` is the single seam for the canbus work: implement it, hand it to
`LauncherActivity`, and the whole cluster (both dials, the four dual-gauge
needles, tire colours, status temp) lights up. Scales: speed 0–120 mph, rpm
0–7 x1000 with redline from 5.5, fuel 0–100 %, coolant 100–260 °F, battery
10–16 V, outside −20–120 °F, tire psi green 30–85 else amber.

## Install on the unit

```
adb install -r apps/launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.ramphoenix.launcher android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.ramphoenix.launcher android.permission.ACCESS_COARSE_LOCATION
adb shell cmd notification allow_listener com.ramphoenix.launcher/.MediaListenerService   # media widget
adb shell cmd package set-home-activity com.ramphoenix.launcher/.LauncherActivity        # make it HOME

# SPLIT mode "app in a half" needs freeform windows (userdebug unit, no root):
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1    # for apps that declare non-resizable
adb reboot
```

Map tiles need network on the unit (wifi/hotspot/4G). Tiles cache in the app
cache dir, so a route driven once stays available offline.

## Open items

- [ ] **Freeform on this unit**: confirm `setLaunchBounds` places the app in the
      pane, whether the window gets a caption bar (Android 13 draws one on
      freeform windows; may need hiding), and that it stays put when the
      launcher is tapped. Fallback is full-screen launch, which already works.

- [ ] Confirm `wm density` on the unit; the layout uses weights for the big
      splits but dp for text/art, tuned at density 2.0.
- [ ] Capture the SYU radio / music / phone package names (`pm list packages syu`)
      so the dock stops relying on category resolution.
- [ ] Do the SYU radio and music apps publish a MediaSession? If not, the
      now-playing card will only follow third-party apps (Spotify etc.).
- [ ] Canbus: pin the `Main.DATA[]` indexes for tire pressure, fuel, coolant,
      battery, outside temp, speed → `CanbusVehicleData`.
- [ ] Voice: the mic in the search bar currently just opens the nav app.
