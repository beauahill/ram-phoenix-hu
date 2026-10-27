# Climate icon correction — 2026-10-26

Branch `codex/climate-icons-2026-10-26`, based on split-window commit `22d1877`.
Launcher **0.5.6 / code 13** is built, **not installed**. The HU is offline
(`192.168.0.225:5555`, one reconnect attempt returned `No route to host`).

## Corrected direction

Beau explicitly rejected the text-only climate controls in 0.5.2. The request was
for modern, recognizable icons. Replacing the confusing 0.5.0 drawings with words
was the wrong interpretation; the 0.5.2 visual inspection and unit tests did not
establish that the design met his preference.

This revision restores the recognizable Material automotive symbol family that
was present at the pre-Codex checkout `c279705`: seated occupant with direction
arrows, windshield/window defrost, cabin recirculation, heated wheel and seat heat.
Seat ventilation uses the same family's seat-and-fan symbol. The simplified
`ic_oem_*` climate drawings and the FACE/FEET/HEAT/VENT text tiles are not used.

- Primary controls are icons on graphite surfaces with subtle depth, consistent
  sizing and restrained amber selection borders. Power is the standard power
  symbol; Auto is the existing thermostat/A symbol; Sync is opposing arrows.
- A/C uses the existing snowflake. Maximum cooling adds the existing plus symbol
  to the snowflake. It is a composite native drawable, not a text tile.
- Seat controls show their actual heat/ventilation symbol and three level bars.
  Warm amber indicates heat; blue indicates ventilation. A dash represents unknown
  state. No HEAT/VENT/OFF text is printed on the controls.
- Power and Auto remain outside `climate_expanded`, alongside Sync. They stay
  available when the extra controls are hidden.
- Temperature values and conventional +/- controls remain. The compact lower
  row returns to the original seat/temperature arrangement, with 44 dp minimum
  targets. The full climate page uses the same icons and reported seat levels.
- Content descriptions and long-press tooltips preserve the control names for
  accessibility and discovery, without turning every button into a word label.

Control IDs, CAN update codes, button commands, main-thread state updates and fan
command cancellation are preserved. `ClimateWidget` binds generic icon views for
system toggles and a native `ClimateSeatButton` for reported-level rendering.
The new seat view does not send commands or invent a level when tapped.

## Evidence and limits

- `testDebugUnitTest assembleDebug assembleDebugAndroidTest` passed, **37 tests,
  zero failures/errors/skips**. Those are functional regression checks, not proof
  of visual quality or a substitute for the user's judgment.
- Check both XML layouts for shared IDs, correct Java view types, icon resources,
  accessible descriptions and the permanent Power/Auto ancestry.
- Rendered and inspected the actual vector/layer-list assets in a desktop preview
  of collapsed/expanded controls. Preview values and selected states are examples,
  not live vehicle readings; fonts/layout are an illustration, not an Android
  runtime screenshot. Files remain outside Git under the Windows workspace's
  `artifacts/climate-icons-0.5.6-preview.{svg,png}`.
- On 2026-10-27 the corrected icon family was inspected on the actual HU in
  compact, expanded and full-page layouts in the cumulative build. Power/Auto
  remained visible in the compact header, and all full-page controls fit.
  No HVAC commands were pressed during this inspection. Actual screenshots are
  `launcher-0.5.10-main.png`, `climate-icons-expanded-live-20261027.png` and
  `climate-icons-full-live-20261027.png` in the private research folder, outside Git.
  Beau's judgment of this corrected appearance remains pending.
- This build includes the pending 0.5.5 native split-window work. Its one-time local
  ADB approval and parked camera/app/chevron tests remain on the worklist.

The earlier [text-control record](CLIMATE-CLARITY-2026-10-26.md) remains as history,
explicitly superseded by this correction. Camera, TPMS and oil-temperature work
are retained. Main has not been changed or pushed.
