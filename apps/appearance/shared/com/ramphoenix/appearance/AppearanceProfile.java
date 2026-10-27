package com.ramphoenix.appearance;

/** Time-based appearance only; never changes device brightness or vehicle settings. */
public final class AppearanceProfile {
    private AppearanceProfile() {}
    public static boolean night(String mode,int hour) {
        if("night".equals(mode))return true;
        if("day".equals(mode))return false;
        return hour<7 || hour>=19;
    }
    public static String mode(String value) {
        return "day".equals(value)||"night".equals(value)?value:"auto";
    }
}
