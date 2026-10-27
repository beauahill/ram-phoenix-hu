package com.ramphoenix.camera;

/** Visible part of the analog buffer, excluding the decoder/composite edge. */
final class VideoViewport {
    static final int BUFFER_WIDTH = 720, BUFFER_HEIGHT = 504;
    // Measured on this unit on 2026-10-26. Includes a small allowance for
    // interpolation around the grey frame; no factory labels/buttons are cut.
    static final String DEFAULT_INSETS = "16,16,10,8";

    final int left, top, right, bottom;

    private VideoViewport(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    /** Insets are native-buffer pixels in left,top,right,bottom order. */
    static VideoViewport parse(String value) {
        try {
            String[] p = value.split(",", -1);
            if (p.length != 4) throw new IllegalArgumentException("four insets required");
            int l = Integer.parseInt(p[0].trim()), t = Integer.parseInt(p[1].trim());
            int r = Integer.parseInt(p[2].trim()), b = Integer.parseInt(p[3].trim());
            // Reject bad/debug values that would remove useful picture content.
            if (l < 0 || t < 0 || r < 0 || b < 0 || l > 32 || r > 32 || t > 32 || b > 32)
                throw new IllegalArgumentException("inset outside 0..32");
            return new VideoViewport(l, t, r, b);
        } catch (RuntimeException invalid) {
            return new VideoViewport(16, 16, 10, 8);
        }
    }

    Layout measure(int width) {
        if (width <= 0) throw new IllegalArgumentException("positive width required");
        int visibleW = BUFFER_WIDTH - left - right;
        int visibleH = BUFFER_HEIGHT - top - bottom;
        int height = Math.round(width * (float) visibleH / visibleW);
        int surfaceW = Math.round(width * (float) BUFFER_WIDTH / visibleW);
        int surfaceH = Math.round(height * (float) BUFFER_HEIGHT / visibleH);
        return new Layout(width, height, surfaceW, surfaceH,
                -Math.round(surfaceW * (float) left / BUFFER_WIDTH),
                -Math.round(surfaceH * (float) top / BUFFER_HEIGHT));
    }

    static final class Layout {
        final int width, height, surfaceWidth, surfaceHeight, surfaceLeft, surfaceTop;

        Layout(int width, int height, int surfaceWidth, int surfaceHeight,
               int surfaceLeft, int surfaceTop) {
            this.width = width;
            this.height = height;
            this.surfaceWidth = surfaceWidth;
            this.surfaceHeight = surfaceHeight;
            this.surfaceLeft = surfaceLeft;
            this.surfaceTop = surfaceTop;
        }

        float bufferX(float viewX) { return clamp((viewX - surfaceLeft) / surfaceWidth); }
        float bufferY(float viewY) { return clamp((viewY - surfaceTop) / surfaceHeight); }
        private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
    }
}
