package com.ramphoenix.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Bottom proximity strip: a top-down vehicle silhouette with 8 ultrasonic zones
 * (front 4, rear 4). Each zone colors green->yellow->red by proximity and FLASHES
 * when an object is close (Beau's photo: "indicators that flash when something is
 * close"). Fed by a {@link ParkingSensorSource}; shows a quiet idle outline when
 * the source has no data yet.
 */
public class ParkingSensorView extends View {

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ParkingSensorSource source = ParkingSensorSource.STUB;

    public ParkingSensorView(Context c) { this(c, null); }
    public ParkingSensorView(Context c, AttributeSet a) {
        super(c, a);
        body.setStyle(Paint.Style.STROKE);
        body.setStrokeWidth(5f);
        body.setColor(Color.argb(180, 220, 220, 220));
        zone.setStyle(Paint.Style.FILL);
        text.setColor(Color.argb(160, 230, 230, 230));
        text.setTextSize(28f);
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setSource(ParkingSensorSource s) {
        this.source = (s == null) ? ParkingSensorSource.STUB : s;
    }

    /** Drive repaints at ~15fps while visible so flashing animates. */
    public void tick() { if (getVisibility() == VISIBLE) invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Vehicle silhouette centered, long axis vertical (portrait panel).
        final float carW = w * 0.30f;
        final float carH = h * 0.70f;
        final float cx = w / 2f;
        final float cy = h / 2f;
        RectF car = new RectF(cx - carW / 2f, cy - carH / 2f, cx + carW / 2f, cy + carH / 2f);
        canvas.drawRoundRect(car, 24f, 24f, body);

        final float[] p = source.proximity();
        final boolean on = flashOn();

        // front zones along the top edge, rear zones along the bottom edge
        float segW = carW / 4f;
        drawZone(canvas, car.left + segW * 0.5f, car.top, p[ParkingSensorSource.FL], on);
        drawZone(canvas, car.left + segW * 1.5f, car.top, p[ParkingSensorSource.FCL], on);
        drawZone(canvas, car.left + segW * 2.5f, car.top, p[ParkingSensorSource.FCR], on);
        drawZone(canvas, car.left + segW * 3.5f, car.top, p[ParkingSensorSource.FR], on);
        drawZone(canvas, car.left + segW * 0.5f, car.bottom, p[ParkingSensorSource.RL], on);
        drawZone(canvas, car.left + segW * 1.5f, car.bottom, p[ParkingSensorSource.RCL], on);
        drawZone(canvas, car.left + segW * 2.5f, car.bottom, p[ParkingSensorSource.RCR], on);
        drawZone(canvas, car.left + segW * 3.5f, car.bottom, p[ParkingSensorSource.RR], on);

        if (!source.available()) {
            canvas.drawText("parking sensors: no data", cx, h - 10f, text);
        }
    }

    private boolean flashOn() {
        // ~3Hz blink
        return ((SystemClock.uptimeMillis() / 160L) % 2L) == 0L;
    }

    private void drawZone(Canvas c, float x, float edgeY, float prox, boolean flashOn) {
        if (prox <= 0.02f) return; // clear -> draw nothing
        int color = colorFor(prox);
        // red zone flashes; yellow/green steady
        if (prox >= 0.75f && !flashOn) return;
        zone.setColor(color);
        float r = 10f + prox * 18f;
        // arc the pips just outside the car edge, toward the object
        float dir = (edgeY < getHeight() / 2f) ? -1f : 1f;
        c.drawCircle(x, edgeY + dir * (18f), r, zone);
    }

    private int colorFor(float prox) {
        if (prox >= 0.75f) return Color.rgb(0xE5, 0x3E, 0x3E); // red
        if (prox >= 0.40f) return Color.rgb(0xF2, 0xC4, 0x3D); // yellow
        return Color.rgb(0x3D, 0xD6, 0x7A);                    // green
    }
}
