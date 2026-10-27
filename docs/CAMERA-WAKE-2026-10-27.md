# Camera stopped after vendor sleep — 2026-10-27

Beau confirmed that YouTube remained visible through map use on launcher 0.5.14,
but neither Reverse nor the physical camera button opened a camera view.

## Observed failure

`com.ramphoenix.camera`0.3.4 was installed/enabled but `stopped=true`, with no
process or ReverseService. Android exit history recorded a vendor-initiated force
stop on 2026-10-26 at 22:32:07 by PID 2331, identified as `com.syu.ms`. The camera's
existing start paths were BOOT_COMPLETED and opening MainActivity. A vendor sleep
cycle need not produce a new Android boot, and force-stopped apps do not revive
their own monitor. The native split task itself surviving is not a camera-priority
pass when no camera service was running.

Explicitly opening the existing camera restored its monitor and MAIN 12 subscription,
seeded0. The activity appeared fullscreen, but its preview watchdog timed out.
`dumpsys media.camera` showed Phoenix as the only device 14 client, native 720×504
and **zero frames produced**. No competing camera client or stale size override
was observed. The UI correctly showed “Camera unavailable · reconnecting…”.
This is a separate video-input problem; restoring the monitor does not prove a
usable image. A physical Reverse/Park retest was requested and remains pending.

## Installed recovery

Launcher **0.5.15/code 22**, camera **0.3.5/code 8**:

- Camera exposes only its reverse-monitor service behind the new signature-level
  `com.ramphoenix.camera.permission.REVERSE_MONITOR` permission. No video/ECU command
  API is exposed by this bridge. Existing boot and manual-start paths remain.
- Launcher starts that foreground monitor while visible, checks again every 15 s,
  and requests it immediately when its independent MAIN 12 subscription reports
  reverse. This does not open a camera activity in Park; the camera monitor owns
  its existing reverse-session show/hide behavior.
- Both installed signing identities were verified against the new APKs and each
  other before installation. The launcher receives the signature permission.
- Live service dump confirmed `recentCallingPackage=com.ramphoenix.launcher`,
  foreground=true and repeated start IDs without opening the camera UI. The
  untrusted shell caller was rejected: “Requires permission …REVERSE_MONITOR”.
- All 92 launcher and 9 camera unit tests passed; both APK builds succeeded.
  A vendor sleep cycle and force-stop fault test while safely in Park still need
  explicit device checks. No ToolkitService or factory camera HAL reset was used.

Backup: `/home/beauhill/ram-phoenix-backups/camera-wake-recovery-0bl02ku0`.
Private evidence: `camera-not-opening-tasks-20261027.txt`,
`camera-timeout-media-20261027.txt`, `camera-reopened-20261027.png` and
`camera-monitor-recovered-20261027.txt` under the research folder.

Launcher APK SHA-256:
`4069ee18a73e26e6317027ee7f4877468c8bf9b5990ea637c00e20335073f106`.
Camera APK SHA-256:
`0f8a7cf7182b1ffa0ab75645d93d1b2a3421ebfad366bb87fdad2cf303a8af4c`.

The physical button's exact show/hide route still needs a live trace. An earlier
note's CAN 1013 `U_CAMERA_MODE` is insufficient: inspected factory code uses it for
view/layout controls, so it was not guessed to be a manual launch trigger.
