# Wish list

Ideas that are wanted but not scheduled. Promote an item to a milestone by
giving it a doc under `docs/` and an `apps/` directory.

## Maintenance tracker app (`apps/maintenance`) — added 2026-10-21

Implementation now lives inside `apps/launcher`. The [0.5.7 refinement](MAINTENANCE-2026-10-26.md)
adds reminder/history forms, keyboard-safe save controls and automatic OBD mileage
plumbing. It is built but awaits HU installation, actual mileage validation and
parked keyboard testing. JSON backup export is implemented; import/SQLite remain
future work. The old assertion that the truck cannot supply an odometer is not
treated as a verified conclusion.
Log and remind on truck maintenance from the head unit:
- **Oil changes** (date, odometer, oil type/weight, filter part #)
- **Fuel filter changes** (both filters on the Cummins; date + odometer)
- **Tire maintenance / replacement** (rotations, pressure checks, new set:
  brand/size/date/odometer, per-corner)
- Open-ended "other" entries (DEF, air filter, brakes, coolant, trans fluid,
  differential, transfer case) with the same date + odometer + notes shape.
- Interval rules per item (miles and/or months) → "due in N mi / N days" on
  the dashboard and a nag when overdue.
- Odometer: read from the canbus `Main.DATA[]` once that index is pinned
  (shared seam with the launcher's `VehicleData`); manual entry until then.
- Data lives on the unit (SQLite) with export/import (JSON or CSV) so it
  survives a reflash and can be edited from a laptop.
- Natural home in the launcher: a dashboard widget showing the next 2–3 due
  items, tapping through to the full log.

## Steering-angle-reactive parking guidelines (camera)
Already on the camera punch list in `CAMERA-MILESTONE-1.md`; needs the
steering-angle LSEC index captured live.
