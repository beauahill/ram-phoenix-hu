# Subtle glass and wallpaper — 2026-10-27

Launcher **0.5.19 / code 26**, Camera **0.3.6 / code 9**. Local branch
`codex/can-gauges-2026-10-27`; no GitHub push.

## Result

- Translucent graphite panels, restrained light borders and brighter secondary
  text across Home, the app drawer, climate, maintenance/forms and suspension.
  Existing automotive icons, permanent Power/Auto and control mappings remain.
- Gear → **Appearance & wallpaper** → **Choose image…** imports a private copy.
  Dimming (20–80%) and softness (0–20) preview immediately and persist locally.
  **Use default wallpaper** restores the procedural blue/graphite background.
- Prefer Android's system photo picker on Android 13+. This HU's vendor file
  manager claims OPEN_DOCUMENT but opens Gallery without returning a selection.
  For USB photos, copy to Pictures first so the system photo library can list them.
  Older firmware falls back to OPEN_DOCUMENT; that fallback is not verified here.
- Internal fullscreen pages open above Home without NEW_TASK. On this firmware,
  NEW_TASK with the launcher's shared affinity returned to its singleTask HOME
  root and swallowed the Appearance launch. External-task flags remain unchanged.
- Camera shares appearance through a read-only, signature-protected provider.
  Its lower SurfaceView overhang mask remains opaque and screen-aligned; crop,
  capture dimensions and factory view-button coordinate mapping are unchanged.
  Blur applies only to wallpaper, never controls or video. Other vendors' app
  content and Google Maps retain their own rendering.

## Import handling

No broad storage permission. URI input is copied with a 32 MiB limit, decoded at
at most 2048 pixels on the long edge, and saved atomically as WebP in app-private
storage. Images over 100 million source pixels are rejected. Bad/oversized input
preserves the prior wallpaper; a reset invalidates an in-flight import.

## Verified on the HU

- Actual Gear menu → Appearance → system photo picker → generated test image
  returned successfully and displayed **Custom wallpaper saved**.
- Changed dimming to 60% and softness to 12. Both survived force-stop/relaunch;
  saved image hash also survived after removal of the public source image.
- Inspected custom Appearance/Home and camera lower-panel screenshots. Camera's
  shared background loaded, but the manual Park launch showed Connecting camera;
  this screenshot does not establish live video or physical Reverse takeover.
- Restored default wallpaper, dimming 38%, softness 7. Removed the generated
  Downloads image and requested a rescan of its exact path. Shell MediaStore
  queries fail inside this vendor build; stale library-row removal is unverified.
- Android import probe passed bounded decode, invalid/oversize preservation,
  read-only provider and cancelled-import/reset ordering. Untrusted shell access
  to the appearance provider was denied; camera's signature permission is granted.
- Maintenance probe passed fully visible Save/Cancel with keyboard, retained
  invalid form and Done saving correct values. Its unique QA reminder was removed.
- Native split probe passed map gestures, task-preserving resize, software reverse
  guard/release and Home dismissal on 0.5.19.
- All **92 launcher + 9 camera unit tests** passed. Both APKs and the launcher
  instrumentation APK built successfully.

Screenshots, probe output and build logs are private artifacts under Crimson's
`/home/beauhill/ram-phoenix-research/` (`glass-*`). Pre-change APKs/preferences:
`/home/beauhill/ram-phoenix-backups/glass-wallpaper-ftwan60d`.

Physical Reverse/Park and the camera button were confirmed on camera 0.3.5 before
this visual pass; a physical retest on 0.3.6 remains pending. No HVAC commands or
suspension pressure commands were sent during this theme QA.

APK SHA-256:

```text
launcher d9de1c32b331e7da5fb7a652b5a54d7b998186c51101384222fe9c4b99272a76
camera   c0ab0f9ead18c65b76cd65cacd4329c74a9db07aa6307c6c08370c31d606c241
```
