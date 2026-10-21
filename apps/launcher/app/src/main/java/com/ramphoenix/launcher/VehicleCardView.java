package com.ramphoenix.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * The vehicle info card, drawn as one instrument cluster:
 *
 *   ( SPEED dial )    [ truck, top view ]    ( RPM dial )
 *       psi                                       psi
 *   (fuel | temp)       status line         (batt | outside)
 *
 * Everything is laid out from the view's own size, so the same view works in
 * the FULL-mode slot and in a taller SPLIT pane. Pure Canvas, no per-frame
 * allocation. Values come from {@link VehicleData} (rpm, fuel, coolant,
 * battery, outside temp, tire psi) and {@link #setSpeed} (canbus or GPS).
 * Unknown values draw the needle at rest and an em dash.
 */
public class VehicleCardView extends View {

    // ---- scales ----
    private static final float SPEED_MAX_MPH = 120f, SPEED_MAX_KPH = 200f;
    private static final float RPM_MAX = 7f, RPM_REDLINE = 5.5f;          // x1000
    private static final float FUEL_MIN = 0f, FUEL_MAX = 100f;
    private static final float COOL_MIN_F = 100f, COOL_MAX_F = 260f;
    private static final float BATT_MIN = 10f, BATT_MAX = 16f;
    private static final float OUT_MIN_F = -20f, OUT_MAX_F = 120f;
    private static final float PSI_LOW = 30f, PSI_HIGH = 85f;

    // ---- paints ----
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progress = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint redline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBig = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint detail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bedFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wheelFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wheelStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lamp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix truckMatrix = new Matrix();

    // truck geometry in a 100 x 220 unit box, nose up
    private final Path body = new Path(), hoodCrease = new Path(), windshield = new Path(),
            roof = new Path(), rearWindow = new Path(), bed = new Path(), bedFloor = new Path(),
            tailgate = new Path(), mirrors = new Path(), headlights = new Path(), taillights = new Path();
    private final Path scratch = new Path();
    private final RectF[] wheels = {   // FL, FR, RL, RR in unit coords
            new RectF(-1, 38, 11, 68), new RectF(89, 38, 101, 68),
            new RectF(-1, 166, 11, 196), new RectF(89, 166, 101, 196)};

    private final int colorTeal, colorAmber, colorGreen, colorRed, colorPrimary, colorSecondary, colorTertiary, colorStroke;

    private VehicleData data = VehicleData.STUB;
    private Float speed;             // in display units
    private String speedSource = "";
    private boolean mph = true;

    public VehicleCardView(Context c) { this(c, null); }
    public VehicleCardView(Context c, AttributeSet a) {
        super(c, a);
        colorTeal = c.getColor(R.color.teal);
        colorAmber = c.getColor(R.color.accent);
        colorGreen = c.getColor(R.color.ok_green);
        colorRed = c.getColor(R.color.bad_red);
        colorPrimary = c.getColor(R.color.text_primary);
        colorSecondary = c.getColor(R.color.text_secondary);
        colorTertiary = c.getColor(R.color.text_tertiary);
        colorStroke = c.getColor(R.color.glass_stroke);

        track.setStyle(Paint.Style.STROKE); track.setStrokeCap(Paint.Cap.ROUND); track.setColor(Color.argb(40, 255, 255, 255));
        progress.setStyle(Paint.Style.STROKE); progress.setStrokeCap(Paint.Cap.ROUND); progress.setColor(colorTeal);
        redline.setStyle(Paint.Style.STROKE); redline.setStrokeCap(Paint.Cap.BUTT); redline.setColor(Color.argb(110, 229, 62, 62));
        tick.setStyle(Paint.Style.STROKE); tick.setStrokeCap(Paint.Cap.ROUND); tick.setColor(Color.argb(150, 255, 255, 255));
        needle.setStyle(Paint.Style.STROKE); needle.setStrokeCap(Paint.Cap.ROUND); needle.setColor(colorAmber);
        hub.setColor(Color.rgb(0x16, 0x19, 0x22));
        hubRing.setStyle(Paint.Style.STROKE); hubRing.setColor(colorAmber);
        textBig.setColor(colorPrimary); textBig.setTextAlign(Paint.Align.CENTER);
        textBig.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textSmall.setColor(colorSecondary); textSmall.setTextAlign(Paint.Align.CENTER);
        textLabel.setColor(colorTertiary); textLabel.setTextAlign(Paint.Align.CENTER); textLabel.setLetterSpacing(0.12f);
        textLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bodyFill.setColor(Color.argb(28, 255, 255, 255));
        bodyStroke.setStyle(Paint.Style.STROKE); bodyStroke.setColor(Color.argb(170, 255, 255, 255)); bodyStroke.setStrokeJoin(Paint.Join.ROUND);
        glass.setColor(Color.argb(70, 120, 185, 235));
        detail.setStyle(Paint.Style.STROKE); detail.setColor(Color.argb(80, 255, 255, 255));
        bedFill.setColor(Color.argb(70, 0, 0, 0));
        wheelFill.setColor(Color.rgb(0x2a, 0x2f, 0x3a));
        wheelStroke.setStyle(Paint.Style.STROKE);
        lamp.setColor(Color.argb(200, 255, 236, 180));
        buildTruck();
    }

    // ---- inputs ----

    public void setData(VehicleData v) { data = (v == null) ? VehicleData.STUB : v; invalidate(); }
    /** Speed in display units (mph or km/h per {@link #setUseMph}); null = unknown. */
    public void setSpeed(Float v) { speed = v; invalidate(); }
    public void setSpeedSource(String s) { speedSource = s == null ? "" : s; invalidate(); }
    public void setUseMph(boolean useMph) { mph = useMph; invalidate(); }

    // ---- drawing ----

    @Override protected void onDraw(Canvas c) {
        final float W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;

        // layout
        final float R = Math.min(H * 0.30f, W * 0.165f);          // big dial radius
        final float dialY = H * 0.36f;
        final float speedX = W * 0.19f, rpmX = W * 0.81f;
        final float r2 = Math.min(H * 0.15f, W * 0.08f);          // dual gauge radius
        final float dualY = H * 0.78f;

        // big dials
        float sMax = mph ? SPEED_MAX_MPH : SPEED_MAX_KPH;
        drawDial(c, speedX, dialY, R, speed, 0f, sMax, mph ? 6 : 10, 4, -1f, -1f,
                speed == null ? Fmt.NONE : String.valueOf(Math.round(speed)), mph ? "MPH" : "KM/H", "SPEED", 1f);
        Float rpm = data.rpm();
        drawDial(c, rpmX, dialY, R, rpm, 0f, RPM_MAX, 7, 2, RPM_REDLINE, RPM_MAX,
                rpm == null ? Fmt.NONE : String.format(Locale.US, "%.1f", rpm), "x1000", "RPM", 1f);

        // truck + tire pressure
        final float truckH = H * 0.74f, truckW = truckH * (100f / 220f);
        final float tx = W / 2f - truckW / 2f, ty = H * 0.04f;
        drawTruck(c, tx, ty, truckW, truckH);

        // dual gauges
        drawDual(c, speedX, dualY, r2,
                data.fuelPercent(), FUEL_MIN, FUEL_MAX, "FUEL", Fmt.percent(data.fuelPercent()),
                fOf(data.coolantC()), COOL_MIN_F, COOL_MAX_F, "TEMP", Fmt.tempF(data.coolantC()));
        drawDual(c, rpmX, dualY, r2,
                data.batteryVolts(), BATT_MIN, BATT_MAX, "BATT", Fmt.volts(data.batteryVolts()),
                fOf(data.outsideC()), OUT_MIN_F, OUT_MAX_F, "OUTSIDE", Fmt.tempF(data.outsideC()));

        // status line under the truck
        textLabel.setTextSize(r2 * 0.26f);
        String status = data.available() ? "CAN BUS" : (speedSource.isEmpty() ? "NO VEHICLE DATA" : speedSource + " · NO CAN DATA");
        c.drawText(status, W / 2f, H * 0.90f, textLabel);
    }

    private static Float fOf(Float celsius) { return celsius == null ? null : celsius * 9f / 5f + 32f; }

    /**
     * 270-degree dial: track, progress arc, ticks, optional red zone, needle,
     * value in the middle, unit in the bottom gap, label near the top.
     */
    private void drawDial(Canvas c, float cx, float cy, float R, Float value, float min, float max,
                          int majors, int minorsPerMajor, float redFrom, float redTo,
                          String valueText, String unit, String label, float alpha) {
        final float start = 135f, sweep = 270f;
        final float frac = value == null ? 0f : clamp((value - min) / (max - min));
        final float arcR = R * 0.90f;
        rect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR);

        track.setStrokeWidth(R * 0.075f);
        c.drawArc(rect, start, sweep, false, track);
        if (redFrom >= 0) {
            redline.setStrokeWidth(R * 0.075f);
            float a0 = start + sweep * (redFrom - min) / (max - min);
            float a1 = start + sweep * (redTo - min) / (max - min);
            c.drawArc(rect, a0, a1 - a0, false, redline);
        }
        if (frac > 0f) {
            progress.setStrokeWidth(R * 0.075f);
            progress.setColor(redFrom >= 0 && value != null && value >= redFrom ? colorRed : colorTeal);
            c.drawArc(rect, start, sweep * frac, false, progress);
        }

        // ticks + numbers
        textSmall.setTextSize(R * 0.13f);
        textSmall.setColor(colorSecondary);
        int total = majors * minorsPerMajor;
        for (int i = 0; i <= total; i++) {
            boolean major = i % minorsPerMajor == 0;
            double a = Math.toRadians(start + sweep * i / total);
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            float ro = R * 0.80f, ri = major ? R * 0.70f : R * 0.75f;
            tick.setStrokeWidth(major ? R * 0.02f : R * 0.012f);
            c.drawLine(cx + cos * ro, cy + sin * ro, cx + cos * ri, cy + sin * ri, tick);
            if (major) {
                float v = min + (max - min) * i / total;
                String s = String.valueOf(Math.round(v));
                float lr = R * 0.62f;
                c.drawText(s, cx + cos * lr, cy + sin * lr + textSmall.getTextSize() * 0.35f, textSmall);
            }
        }

        // needle
        double na = Math.toRadians(start + sweep * frac);
        float ncos = (float) Math.cos(na), nsin = (float) Math.sin(na);
        needle.setStrokeWidth(R * 0.035f);
        needle.setColor(value == null ? Color.argb(120, 242, 169, 75) : colorAmber);
        c.drawLine(cx - ncos * R * 0.10f, cy - nsin * R * 0.10f, cx + ncos * R * 0.80f, cy + nsin * R * 0.80f, needle);
        c.drawCircle(cx, cy, R * 0.09f, hub);
        hubRing.setStrokeWidth(R * 0.02f);
        c.drawCircle(cx, cy, R * 0.09f, hubRing);

        // texts
        textLabel.setTextSize(R * 0.10f);
        c.drawText(label, cx, cy - R * 0.24f, textLabel);
        textBig.setTextSize(R * 0.33f);
        c.drawText(valueText, cx, cy + R * 0.40f, textBig);
        textSmall.setTextSize(R * 0.13f);
        c.drawText(unit, cx, cy + R * 0.62f, textSmall);
    }

    /**
     * Classic dual gauge: one dial, two 90-degree scales. Left scale climbs the
     * left side (lower-left = min, upper-left = max), right scale climbs the
     * right side. Two needles share the hub. Values and labels sit underneath.
     */
    private void drawDual(Canvas c, float cx, float cy, float r,
                          Float lv, float lmin, float lmax, String llabel, String ltext,
                          Float rv, float rmin, float rmax, String rlabel, String rtext) {
        final float arcR = r * 0.88f;
        rect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR);
        track.setStrokeWidth(r * 0.09f);
        c.drawArc(rect, 135f, 90f, false, track);      // left:  135 -> 225
        c.drawArc(rect, 315f, 90f, false, track);      // right: 315 -> 405 (= 45)

        float lf = lv == null ? 0f : clamp((lv - lmin) / (lmax - lmin));
        float rf = rv == null ? 0f : clamp((rv - rmin) / (rmax - rmin));
        progress.setStrokeWidth(r * 0.09f);
        if (lf > 0f) { progress.setColor(lv != null && lf < 0.15f ? colorAmber : colorTeal); c.drawArc(rect, 135f, 90f * lf, false, progress); }
        if (rf > 0f) { progress.setColor(rv != null && rf > 0.85f ? colorRed : colorTeal); c.drawArc(rect, 45f, -90f * rf, false, progress); }

        // end + mid ticks
        tick.setStrokeWidth(r * 0.025f);
        for (float a : new float[]{135f, 180f, 225f, 315f, 0f, 45f}) {
            double ra = Math.toRadians(a);
            float cos = (float) Math.cos(ra), sin = (float) Math.sin(ra);
            c.drawLine(cx + cos * r * 0.76f, cy + sin * r * 0.76f, cx + cos * r * 0.68f, cy + sin * r * 0.68f, tick);
        }

        // needles
        needle.setStrokeWidth(r * 0.05f);
        needle.setColor(lv == null ? Color.argb(120, 242, 169, 75) : colorAmber);
        drawNeedle(c, cx, cy, r * 0.74f, 135f + 90f * lf);
        needle.setColor(rv == null ? Color.argb(120, 242, 169, 75) : colorAmber);
        drawNeedle(c, cx, cy, r * 0.74f, 45f - 90f * rf);
        c.drawCircle(cx, cy, r * 0.11f, hub);
        hubRing.setStrokeWidth(r * 0.025f);
        c.drawCircle(cx, cy, r * 0.11f, hubRing);

        // min/max markers in the gaps: E/F style letters for fuel, C/H for temp are overkill; use labels below
        textBig.setTextSize(r * 0.30f);
        textLabel.setTextSize(r * 0.16f);
        float vy = cy + r * 0.98f, ly = vy + r * 0.24f;
        c.drawText(ltext, cx - r * 0.62f, vy, textBig);
        c.drawText(llabel, cx - r * 0.62f, ly, textLabel);
        c.drawText(rtext, cx + r * 0.62f, vy, textBig);
        c.drawText(rlabel, cx + r * 0.62f, ly, textLabel);
    }

    private void drawNeedle(Canvas c, float cx, float cy, float len, float deg) {
        double a = Math.toRadians(deg);
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        c.drawLine(cx, cy, cx + cos * len, cy + sin * len, needle);
    }

    // ---- truck ----

    private void buildTruck() {
        // body: rounded nose, slight flare at the front fenders, straight sides, rounded tail
        body.moveTo(22, 6); body.lineTo(78, 6); body.quadTo(90, 6, 91, 16);
        body.lineTo(92, 60); body.lineTo(92, 206); body.quadTo(92, 214, 84, 214);
        body.lineTo(16, 214); body.quadTo(8, 214, 8, 206);
        body.lineTo(8, 60); body.lineTo(9, 16); body.quadTo(10, 6, 22, 6); body.close();
        hoodCrease.moveTo(50, 12); hoodCrease.lineTo(50, 54);
        hoodCrease.moveTo(12, 56); hoodCrease.lineTo(88, 56);
        windshield.moveTo(14, 58); windshield.lineTo(86, 58); windshield.lineTo(80, 78); windshield.lineTo(20, 78); windshield.close();
        roof.addRoundRect(20, 80, 80, 118, 6, 6, Path.Direction.CW);
        rearWindow.moveTo(22, 120); rearWindow.lineTo(78, 120); rearWindow.lineTo(76, 130); rearWindow.lineTo(24, 130); rearWindow.close();
        bed.addRoundRect(14, 136, 86, 206, 4, 4, Path.Direction.CW);
        bedFloor.addRoundRect(18, 140, 82, 198, 3, 3, Path.Direction.CW);
        tailgate.moveTo(14, 200); tailgate.lineTo(86, 200);
        mirrors.addRoundRect(1, 62, 10, 72, 2, 2, Path.Direction.CW);
        mirrors.addRoundRect(90, 62, 99, 72, 2, 2, Path.Direction.CW);
        headlights.addRoundRect(14, 10, 30, 15, 2, 2, Path.Direction.CW);
        headlights.addRoundRect(70, 10, 86, 15, 2, 2, Path.Direction.CW);
        taillights.addRoundRect(10, 207, 24, 212, 2, 2, Path.Direction.CW);
        taillights.addRoundRect(76, 207, 90, 212, 2, 2, Path.Direction.CW);
    }

    private void drawTruck(Canvas c, float x, float y, float w, float h) {
        final float s = h / 220f;
        truckMatrix.reset();
        truckMatrix.setScale(s, s);
        truckMatrix.postTranslate(x, y);
        bodyStroke.setStrokeWidth(Math.max(1.5f, s * 1.6f));
        detail.setStrokeWidth(Math.max(1f, s * 1.0f));
        wheelStroke.setStrokeWidth(Math.max(1.5f, s * 1.8f));

        drawPath(c, body, bodyFill); drawPath(c, body, bodyStroke);
        drawPath(c, hoodCrease, detail);
        drawPath(c, windshield, glass);
        drawPath(c, roof, bodyFill); drawPath(c, roof, detail);
        drawPath(c, rearWindow, glass);
        drawPath(c, bed, detail);
        drawPath(c, bedFloor, bedFill);
        drawPath(c, tailgate, detail);
        drawPath(c, mirrors, bodyFill); drawPath(c, mirrors, detail);
        drawPath(c, headlights, lamp);
        lamp.setColor(Color.argb(220, 229, 62, 62)); drawPath(c, taillights, lamp);
        lamp.setColor(Color.argb(200, 255, 236, 180));

        // wheels, stroked in the tire-pressure state colour, with psi beside them
        textBig.setTextSize(h * 0.075f);
        textLabel.setTextSize(h * 0.040f);
        for (int i = 0; i < 4; i++) {
            Float psi = data.tirePsi(i);
            RectF u = wheels[i];
            rect.set(x + u.left * s, y + u.top * s, x + u.right * s, y + u.bottom * s);
            c.drawRoundRect(rect, s * 3f, s * 3f, wheelFill);
            wheelStroke.setColor(psi == null ? Color.argb(120, 255, 255, 255) : (psi < PSI_LOW || psi > PSI_HIGH) ? colorAmber : colorGreen);
            c.drawRoundRect(rect, s * 3f, s * 3f, wheelStroke);

            boolean left = (i % 2 == 0);
            float tx = left ? rect.left - h * 0.05f : rect.right + h * 0.05f;
            textBig.setTextAlign(left ? Paint.Align.RIGHT : Paint.Align.LEFT);
            textLabel.setTextAlign(left ? Paint.Align.RIGHT : Paint.Align.LEFT);
            float midY = rect.centerY();
            c.drawText(Fmt.psi(psi), tx, midY + textBig.getTextSize() * 0.1f, textBig);
            c.drawText("PSI", tx, midY + textBig.getTextSize() * 0.1f + textLabel.getTextSize() * 1.3f, textLabel);
        }
        textBig.setTextAlign(Paint.Align.CENTER);
        textLabel.setTextAlign(Paint.Align.CENTER);
    }

    private void drawPath(Canvas c, Path unit, Paint p) {
        scratch.reset();
        unit.transform(truckMatrix, scratch);
        c.drawPath(scratch, p);
    }

    private static float clamp(float f) { return f < 0f ? 0f : (f > 1f ? 1f : f); }
}
