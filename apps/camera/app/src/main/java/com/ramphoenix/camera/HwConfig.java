package com.ramphoenix.camera;

/**
 * Hardware facts reverse-engineered from the stock SYU stack on this unit
 * (UNISOC UIS7870, Android 13 _nosec, portrait 1080x1920).
 *
 * Single source of truth for every board-specific constant so the unknowns are
 * visible and tunable in one place rather than scattered as magic numbers.
 *
 * CONFIRMED (captured live via adb/logcat 2026-10-20):
 *   - Backup cam  = Camera2 device "14"  (stock com.syu.doublecamera binds it; HAL camId=2)
 *   - 360 2nd input = Camera2 device "10" (stock com.syu.us binds it alongside 14)
 *   - Reverse flag  = /sys/lsecdcamctl/fyt_car_reverse  (0 at rest -> 1 on gear-R, world 0666)
 *   - Feed is plain Camera2 at 1920x1080 / 1280x720; no /dev/video node.
 *
 * NEEDS LIVE CONFIRMATION (left tunable, see docs/CAMERA-MILESTONE-1.md open items):
 *   - FRONT_CAM_ID: the board has 3 TP2850 analog decoders (main/main2/sub video ic).
 *     Front cam is one of the other Camera2 indices; confirm by opening candidates live.
 *   - Parking-sensor (radar) distances ride the canbus LSEC IPC via com.syu.us
 *     (setVisibility_CarRadar); the exact Main.DATA[] index is not yet pinned.
 */
public final class HwConfig {
    private HwConfig() {}

    // --- Camera2 device ids (strings, as CameraManager reports them) ---
    public static final String REAR_CAM_ID      = "14"; // CONFIRMED backup cam
    public static final String SURROUND_AUX_ID  = "10"; // CONFIRMED 360 second input
    public static final String FRONT_CAM_ID      = "12"; // TENTATIVE - confirm live before trusting

    /**
     * Native producer size per virtual camera, from the HAL's VirCamPara table
     * captured live (getSnsIdSize hwId=2): 7->1920x1080, 8/9->1280x800,
     * 10->720x504 (deinterlace), 11->1920x1080, 12/13->1280x800, 14->720x504 (deinterlace).
     * The analog TP2850 inputs (10, 14) are 720x504; the HAL will not scale them.
     */
    public static android.util.Size nativeSize(String id) {
        switch (id) {
            case "7": case "11": return new android.util.Size(1920, 1080);
            case "8": case "9": case "12": case "13": return new android.util.Size(1280, 800);
            case "10": case "14": return new android.util.Size(720, 504);
            default: return new android.util.Size(CAP_WIDTH, CAP_HEIGHT);
        }
    }

    // --- Preferred capture size (board supports these on the back cam) ---
    public static final int CAP_WIDTH  = 1280;
    public static final int CAP_HEIGHT = 720;

    // --- sysfs control plane (all world-writable 0666 on this board) ---
    public static final String SYS_DIR          = "/sys/lsecdcamctl/";
    public static final String NODE_REVERSE     = SYS_DIR + "fyt_car_reverse";
    public static final String NODE_MIRROR      = SYS_DIR + "backcar_mirror";
    public static final String NODE_ROTATION    = SYS_DIR + "video_rotation";
    public static final String NODE_POSITION    = SYS_DIR + "video_position";
    public static final String NODE_VERTICAL    = SYS_DIR + "video_vertical";
    public static final String NODE_SIGNAL      = SYS_DIR + "video_signal";

    // --- reverse poll cadence ---
    public static final long REVERSE_POLL_MS = 80;

    // --- truck geometry for guideline math (inches; tune to the actual RAM) ---
    public static final double WHEELBASE_IN  = 140.5;  // RAM crew cab ballpark
    public static final double TRACK_WIDTH_IN = 68.6;
    public static final double CAM_HEIGHT_IN = 44.0;
}
