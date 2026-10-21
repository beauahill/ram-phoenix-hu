package com.ramphoenix.launcher;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Picks a tint for the media card from album art: the average of its saturated pixels. */
final class ArtColor {
    private ArtColor() {}

    static int dominant(Bitmap src, int fallback) {
        if (src == null) return fallback;
        try {
            Bitmap s = Bitmap.createScaledBitmap(src, 12, 12, true);
            long r = 0, g = 0, b = 0; int n = 0;
            long r2 = 0, g2 = 0, b2 = 0; int n2 = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < s.getHeight(); y++) {
                for (int x = 0; x < s.getWidth(); x++) {
                    int p = s.getPixel(x, y);
                    r2 += Color.red(p); g2 += Color.green(p); b2 += Color.blue(p); n2++;
                    Color.colorToHSV(p, hsv);
                    if (hsv[1] > 0.25f && hsv[2] > 0.2f && hsv[2] < 0.95f) {
                        r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++;
                    }
                }
            }
            if (s != src) s.recycle();
            if (n >= 6) return Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
            if (n2 > 0) return Color.rgb((int) (r2 / n2), (int) (g2 / n2), (int) (b2 / n2));
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /** Darken/desaturate a colour toward the dashboard's base so text stays readable. */
    static int toneDown(int c, float keep) {
        int r = (int) (Color.red(c) * keep), g = (int) (Color.green(c) * keep), b = (int) (Color.blue(c) * keep);
        return Color.rgb(r, g, b);
    }
}
