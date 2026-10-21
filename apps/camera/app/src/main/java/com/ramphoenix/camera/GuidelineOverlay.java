package com.ramphoenix.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Lightweight static parking-guideline overlay, re-tuned for a lifted truck on a
 * PORTRAIT panel. Draws three distance bands (red ~0.5m, yellow ~1m, green ~2m)
 * plus straight trajectory rails converging toward the vanishing point.
 *
 * Unlike the stock MyRender/TrackView, this is a single onDraw of cached Paths
 * (no per-frame GL trajectory recompute), so it costs ~nothing and never stutters.
 * Geometry is a simple perspective approximation driven by HwConfig truck dims;
 * exact on-ground calibration is a later refinement once we can see the feed.
 */
public class GuidelineOverlay extends View {

    private final Paint rail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean mirror = false;

    public GuidelineOverlay(Context c) { this(c, null); }
    public GuidelineOverlay(Context c, AttributeSet a) {
        super(c, a);
        rail.setStyle(Paint.Style.STROKE);
        rail.setStrokeWidth(6f);
        band.setStyle(Paint.Style.STROKE);
        band.setStrokeWidth(10f);
        band.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setMirror(boolean m) { this.mirror = m; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (mirror) { canvas.save(); canvas.scale(-1f, 1f, w / 2f, h / 2f); }

        // Rails: wider at the bottom (bumper) narrowing toward the top (distance).
        final float bottomY = h * 0.98f;
        final float topY    = h * 0.45f;
        final float bottomHalf = w * 0.34f;   // track half-width at bumper
        final float topHalf    = w * 0.12f;   // apparent half-width at range
        final float cx = w / 2f;

        Path left = new Path();
        left.moveTo(cx - bottomHalf, bottomY);
        left.lineTo(cx - topHalf, topY);
        Path right = new Path();
        right.moveTo(cx + bottomHalf, bottomY);
        right.lineTo(cx + topHalf, topY);

        rail.setColor(Color.WHITE);
        canvas.drawPath(left, rail);
        canvas.drawPath(right, rail);

        // Distance bands across the rails at red/yellow/green depths.
        drawBand(canvas, cx, bottomY, topY, bottomHalf, topHalf, 0.12f, Color.rgb(0xE5, 0x3E, 0x3E)); // ~0.5m
        drawBand(canvas, cx, bottomY, topY, bottomHalf, topHalf, 0.45f, Color.rgb(0xF2, 0xC4, 0x3D)); // ~1m
        drawBand(canvas, cx, bottomY, topY, bottomHalf, topHalf, 0.85f, Color.rgb(0x3D, 0xD6, 0x7A)); // ~2m

        if (mirror) canvas.restore();
    }

    private void drawBand(Canvas c, float cx, float by, float ty,
                          float bHalf, float tHalf, float t, int color) {
        float y = by + (ty - by) * t;
        float half = bHalf + (tHalf - bHalf) * t;
        band.setColor(color);
        c.drawLine(cx - half, y, cx + half, y, band);
    }
}
