package com.ramphoenix.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Top-down truck outline (cab + bed) with a tire at each corner and the
 * pressure reading beside it. Tire colour: grey = no data, green = in range,
 * amber = low/high. Pure Canvas, one onDraw, no allocation per frame.
 */
public class TruckSensorView extends View {

    private static final float PSI_LOW = 30f, PSI_HIGH = 85f;

    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tire = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private VehicleData data = VehicleData.STUB;
    private final float d;

    public TruckSensorView(Context c) { this(c, null); }
    public TruckSensorView(Context c, AttributeSet a) {
        super(c, a);
        d = c.getResources().getDisplayMetrics().density;
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(2f * d);
        outline.setColor(Color.argb(140, 255, 255, 255));
        inner.setStyle(Paint.Style.STROKE);
        inner.setStrokeWidth(1.5f * d);
        inner.setColor(Color.argb(75, 255, 255, 255));
        tire.setStyle(Paint.Style.FILL);
        value.setColor(c.getColor(R.color.text_primary));
        value.setTextSize(15f * d);
        value.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        label.setColor(c.getColor(R.color.text_tertiary));
        label.setTextSize(9f * d);
        label.setLetterSpacing(0.1f);
    }

    public void setData(VehicleData v) {
        this.data = (v == null) ? VehicleData.STUB : v;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        final float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // truck body: centered, long axis vertical
        final float bodyW = Math.min(w * 0.30f, h * 0.48f);
        final float bodyH = h * 0.92f;
        final float cx = w / 2f, top = (h - bodyH) / 2f, bottom = top + bodyH;
        r.set(cx - bodyW / 2f, top, cx + bodyW / 2f, bottom);
        canvas.drawRoundRect(r, 16f * d, 16f * d, outline);

        // cab + bed
        final float inset = bodyW * 0.14f;
        r.set(cx - bodyW / 2f + inset, top + bodyH * 0.10f, cx + bodyW / 2f - inset, top + bodyH * 0.40f);
        canvas.drawRoundRect(r, 9f * d, 9f * d, inner);
        r.set(cx - bodyW / 2f + inset, top + bodyH * 0.48f, cx + bodyW / 2f - inset, bottom - bodyH * 0.06f);
        canvas.drawRoundRect(r, 6f * d, 6f * d, inner);

        // tires + readings
        final float tw = bodyW * 0.20f, th = bodyH * 0.17f;
        final float fy = top + bodyH * 0.12f, ry = bottom - bodyH * 0.12f - th;
        final float lx = cx - bodyW / 2f - tw * 0.55f, rx = cx + bodyW / 2f - tw * 0.45f;
        drawTire(canvas, lx, fy, tw, th, data.tirePsi(VehicleData.FL), true);
        drawTire(canvas, rx, fy, tw, th, data.tirePsi(VehicleData.FR), false);
        drawTire(canvas, lx, ry, tw, th, data.tirePsi(VehicleData.RL), true);
        drawTire(canvas, rx, ry, tw, th, data.tirePsi(VehicleData.RR), false);
    }

    private void drawTire(Canvas c, float x, float y, float tw, float th, Float psi, boolean left) {
        tire.setColor(colorFor(psi));
        r.set(x, y, x + tw, y + th);
        c.drawRoundRect(r, 4f * d, 4f * d, tire);

        String v = Fmt.psi(psi);
        float tx = left ? 0f : getWidth();
        value.setTextAlign(left ? Paint.Align.LEFT : Paint.Align.RIGHT);
        label.setTextAlign(left ? Paint.Align.LEFT : Paint.Align.RIGHT);
        float ty = y + th * 0.45f;
        c.drawText(v, tx, ty, value);
        c.drawText("PSI", tx, ty + 12f * d, label);
    }

    private int colorFor(Float psi) {
        if (psi == null) return Color.argb(130, 255, 255, 255);
        if (psi < PSI_LOW || psi > PSI_HIGH) return getContext().getColor(R.color.warn_amber);
        return getContext().getColor(R.color.ok_green);
    }
}
