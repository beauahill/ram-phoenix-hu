package com.ramphoenix.camera;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoViewportTest {
    @Test public void fullBufferOverrideKeepsOriginalPictureAndTouches() {
        VideoViewport.Layout p = VideoViewport.parse("0,0,0,0").measure(1080);
        assertEquals(756, p.height);
        assertEquals(0, p.surfaceLeft);
        assertEquals(0, p.surfaceTop);
        assertEquals(0f, p.bufferX(0), 0f);
        assertEquals(1f, p.bufferY(756), 0f);
    }

    @Test public void calibratedPictureExcludesAllFourEdges() {
        VideoViewport.Layout p = VideoViewport.parse(VideoViewport.DEFAULT_INSETS).measure(1080);
        assertEquals(747, p.height);
        // Allow one native pixel for integer view/layout rounding.
        assertEquals(16f, p.bufferX(0) * 720, 1f);
        assertEquals(710f, p.bufferX(1080) * 720, 1f);
        assertEquals(16f, p.bufferY(0) * 504, 1f);
        assertEquals(496f, p.bufferY(747) * 504, 1f);
    }

    @Test public void factoryButtonStillMapsToSameEncodedCoordinate() {
        VideoViewport.Layout p = VideoViewport.parse(VideoViewport.DEFAULT_INSETS).measure(1080);
        // First factory view button: encoded frame point (295,455).
        float x = p.surfaceLeft + 295f * p.surfaceWidth / 720;
        float y = p.surfaceTop + 455f * p.surfaceHeight / 504;
        assertEquals(420, Math.round(p.bufferX(x) * 1024));
        assertEquals(542, Math.round(p.bufferY(y) * 600));
    }

    @Test public void geometryUsesAvailableWindowWidthAndClampsDragOutsidePicture() {
        VideoViewport.Layout p = VideoViewport.parse(VideoViewport.DEFAULT_INSETS).measure(720);
        assertEquals(498, p.height);
        assertEquals(0f, p.bufferX(-1000), 0f);
        assertEquals(1f, p.bufferY(2000), 0f);
    }

    @Test public void malformedOrExcessiveInsetsUseSafeCalibration() {
        for (String s : new String[]{"", "1,2,3", "bad,0,0,0", "-1,0,0,0", "100,0,0,0", "0,2147483647,0,0"}) {
            VideoViewport v = VideoViewport.parse(s);
            assertEquals(16, v.left);
            assertEquals(16, v.top);
            assertEquals(10, v.right);
            assertEquals(8, v.bottom);
        }
    }
}
