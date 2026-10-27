# Parked HU checks — 2026-10-27

Installed launcher **0.5.15 / code 22**, camera **0.3.5 / code 8** after the physical
camera failure reported below. All **92 launcher + 9 camera unit tests** and APK
builds passed. No GitHub push. Current checksums and camera details are recorded
in [CAMERA-WAKE-2026-10-27.md](CAMERA-WAKE-2026-10-27.md).

## Verified

- Coolant and transmission select CAN independently of the OBD connection.
  Raw234/236 = 32874/32876 displayed 66°F/68°F, exactly Beau's cluster report.
  The ignition-only cluster showed a dash for pressure; initial CAN zero remained
  unknown. Running oil pressure has not been established.
- Maintenance's real editor/keyboard probe passed reachable Save/Cancel, retained
  invalid input and Done committing correct values. Its unique QA reminder was
  removed; existing preferences were backed up before schema migration.
- OBD recovered using its existing serialized channel-1 fallback. TPMS and voltage
  resumed. Standard 7E0:01A6 repeatedly reported 73,730.6629 miles; cluster comparison
  is pending. Later ignition-only reads returned NO DATA, so availability is not
  continuous. The approximate 71,000 estimate was never stored as a measurement.
- Installed WirelessOne 2.3.0/code100305 matches the reference signer. Read-only
  GATT check passed against the paired Air Lift model72708/firmware1.2.0: normal
  mode, no diagnostic flags, measured38kPa (~5.51psi), target35 kPa (~5.08psi), optional
  tank field absent. The native page displays pressure and 80/50/30/5 psi profiles.
  Setup contains the user-entered "Ram Kit" limits5–100psi; owner reports100psi max.
- Recognizable climate icons inspected on the actual panel in compact/expanded/
  full-page layouts. Power/Auto remain in the compact header. No HVAC commands
  pressed during visual QA; user acceptance of appearance is still pending.
- Native split regression passed on final 0.5.14: map gestures, in-place resize,
  software reverse guard, restoration and Home dismissal. Actual picker testing
  retained YouTube through five map pans and dock reflow, then FULL dismissed it.
  Final screenshot confirms full-width panes and a small corner chevron clear of
  the map's zoom button.
- Explicitly approved app-owned local debugging trust. Helper restart after a
  verified helper-only termination passed in1.1s without Crimson bootstrapping.
  Shell stream is retained until startup readiness; helper directory0700, token/
  JAR0600, early credential rotated. PC private keys are not embedded or copied.

## Still pending

- Physical0.5.14 test: Beau confirmed YouTube stays during map use, but camera
  never opened. Camera was vendor-force-stopped. Installed monitor recovery in
  launcher 0.5.15/camera 0.3.5; live foreground-service restoration verified. Initial
  manual video attempt produced zero frames. Usable picture, physical camera
  button and Reverse/Park with the recovery build remain pending.
- One explicitly approved Empty5 psi command completed and post-write readback
  remained35 kPa (target was already5 psi). No higher profile applied. Changed-target
  response/settling, app handoff and interruption checks remain pending.
- Exact cluster odometer agreement, remaining maintenance/history/export checks.
- Running oil-pressure CAN agreement, warm-temperature comparisons, independent
  upstream CAN freshness and oil-life remaining (owner previously reported96%).
- Real HU cold boot, vendor camera/bar restoration and broader app compatibility.

Evidence remains private outside Git in `/home/beauhill/ram-phoenix-research`.
Pre-install APK/preferences backups are under `/home/beauhill/ram-phoenix-backups`,
including `can-gauges-live-20261027.GNKoTq`, `launcher-0.5.10-live-uff_yyws` and
`launcher-0.5.11-live-xrziaqey`. Preserve the version2 maintenance data before any
downgrade; older builds can discard history fields when saving.
