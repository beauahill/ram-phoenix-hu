# Phoenix Launcher — dashboard home (apps/launcher)

CarPlay-style home for the portrait 1080x1920 panel. Replaces the stock
`com.android.launcher6`. Large widgets, not an icon grid:

```
┌──────────────────────────────┐
│ 4:20 pm  Tue, Oct 21   52° ⚙ │  status row
├──────────────────────────────┤
│                              │
│   live map (osmdroid, dark)  │  upper half
│   [nav]            [+/-]     │
│   [🎤 🔍 Where to?        ]  │
├──────────────────────────────┤
│ [art]  Spotify               │
│        Falling               │  now playing (MediaSession)
│        Madeaux   ◀ ▶ ▶▶      │
├────────────┬─────────────────┤
│     0      │ VEHICLE         │
│    MPH     │  tire psi x4    │  lower half
│   • GPS    │ fuel cool bat °F│
├────────────┴─────────────────┤
│ [apps][cam][nav][☎][♪][📻][⚙]│  dock
└──────────────────────────────┘
```

Plain framework widgets, **no AndroidX** (small, fast cold start, and it
compiles without Google's Maven). One dependency: `org.osmdroid:osmdroid-android`
for key-free OpenStreetMap tiles (CARTO dark basemap).

## Widgets and where their data comes from

| Widget | Source today | Later |
|---|---|---|
| Map | osmdroid + GPS follow (`MapWidget`) | nav app hand-off already wired (tap nav / search) |
| Now playing | `MediaSessionManager` (`MediaWidget`), any app with a MediaSession: Spotify, SYU radio/music if they publish one | — |
| Speed | GPS ground speed (`SpeedWidget`) | canbus speed via `VehicleData.speedKph()` |
| Vehicle | `VehicleData.STUB` → dashes | `CanbusVehicleData` once LSEC IPC `Main.DATA[]` indexes for tire psi / fuel / coolant / battery / outside temp are captured |
| Dock | intent categories (`CATEGORY_APP_MAPS`, `CATEGORY_APP_MUSIC`, `ACTION_DIAL`), then package fallbacks, then label match ("radio") | pin the real SYU radio/music/phone package names |

`VehicleData` is the single seam for the canbus work: implement it, hand it to
`LauncherActivity`, and the truck view, stat row, status temp and speed all
light up.

## Install on the unit

```
adb install -r apps/launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.ramphoenix.launcher android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.ramphoenix.launcher android.permission.ACCESS_COARSE_LOCATION
adb shell cmd notification allow_listener com.ramphoenix.launcher/.MediaListenerService   # media widget
adb shell cmd package set-home-activity com.ramphoenix.launcher/.LauncherActivity        # make it HOME
```

Map tiles need network on the unit (wifi/hotspot/4G). Tiles cache in the app
cache dir, so a route driven once stays available offline.

## Open items

- [ ] Confirm `wm density` on the unit; the layout uses weights for the big
      splits but dp for text/art, tuned at density 2.0.
- [ ] Capture the SYU radio / music / phone package names (`pm list packages syu`)
      so the dock stops relying on category resolution.
- [ ] Do the SYU radio and music apps publish a MediaSession? If not, the
      now-playing card will only follow third-party apps (Spotify etc.).
- [ ] Canbus: pin the `Main.DATA[]` indexes for tire pressure, fuel, coolant,
      battery, outside temp, speed → `CanbusVehicleData`.
- [ ] Voice: the mic in the search bar currently just opens the nav app.
