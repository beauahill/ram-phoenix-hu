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
 * The vehicle info card:
 *
 *   (fuel | temp)   [ truck, top view ]   (batt | outside)
 *                    psi           psi
 *     status
 *
 * Speed / rpm dials were dropped on 2026-10-25 (the dash already has them).
 * Everything is laid out from the view's own size, so the same view works in
 * the FULL-mode slot and in a taller SPLIT pane. Pure Canvas, no per-frame
 * allocation. Values come from {@link VehicleData} (fuel, coolant, battery,
 * outside temp, tire psi). Unknown values draw the needle at rest and an em dash.
 */
public class VehicleCardView extends View {

    // ---- scales ----
    private static final float FUEL_MIN = 0f, FUEL_MAX = 100f;
    private static final float COOL_MIN_F = 100f, COOL_MAX_F = 260f;
    private static final float BATT_MIN = 10f, BATT_MAX = 16f;
    private static final float TRANS_MIN_F = 100f, TRANS_MAX_F = 260f;
    private static final float OIL_MIN = 0f, OIL_MAX = 80f;
    private static final float OIL_T_MIN_F = 100f, OIL_T_MAX_F = 280f;
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

    // truck geometry from TruckOutline (traced blueprint), unit box WIDTH x LENGTH, nose up
    private final Path body = new Path(), hoodBulge = new Path(), windshield = new Path(),
            roof = new Path(), rearWindow = new Path(), cabBack = new Path(), bed = new Path(),
            bedFloor = new Path(), tailgate = new Path(), mirrors = new Path(), boards = new Path(),
            lamps = new Path(), headlights = new Path(), taillights = new Path(), seams = new Path(),
            wheelsInner = new Path();
    private final Path scratch = new Path();
    private final Paint bulgeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boardFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lampOrange = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wheelInner = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int colorTeal, colorAmber, colorGreen, colorRed, colorPrimary, colorSecondary, colorTertiary, colorStroke;

    private VehicleData data = VehicleData.STUB;
    private String serviceText;
    public void setServiceText(String t) { serviceText = t; invalidate(); }

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
        bulgeFill.setColor(Color.argb(16, 255, 255, 255));
        boardFill.setColor(Color.argb(46, 255, 255, 255));
        lampOrange.setColor(Color.argb(215, 255, 190, 80));
        wheelInner.setColor(Color.rgb(0x23, 0x27, 0x30));
        buildTruck();
    }

    // ---- inputs ----

    public void setData(VehicleData v) { data = (v == null) ? VehicleData.STUB : v; invalidate(); }

    // ---- drawing ----

    @Override protected void onDraw(Canvas c) {
        final float W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;

        // Beau, 2026-10-25: truck on the left, two big dual gauges right beside it,
        // a data column on the right (maintenance will stack in there later).
        final float truckH = H * 0.92f, truckW = truckH * (TruckOutline.WIDTH / TruckOutline.LENGTH);
        final float readout = H * 0.92f * 0.17f * 2.1f;      // width of a "65" readout plus its gap
        final float tx = W * 0.02f + readout, ty = H * 0.04f;
        final float r = Math.min(H * 0.36f, W * 0.125f);
        final float gy = H * 0.40f;
        final float g1 = tx + truckW + readout + r * 1.0f, g2 = g1 + r * 2.3f;

        drawDual(c, g1, gy, r,
                fOf(data.coolantC()), COOL_MIN_F, COOL_MAX_F, "COOLANT", Fmt.tempF(data.coolantC()),
                fOf(data.transC()), TRANS_MIN_F, TRANS_MAX_F, "TRANS", Fmt.tempF(data.transC()));
        drawDual(c, g2, gy, r,
                data.oilPsi(), OIL_MIN, OIL_MAX, "OIL PSI", data.oilPsi() == null ? Fmt.NONE : String.format(java.util.Locale.US, "%.0f", data.oilPsi()),
                fOf(data.oilTempC()), OIL_T_MIN_F, OIL_T_MAX_F, "OIL TEMP", Fmt.tempF(data.oilTempC()));
        drawTruck(c, tx, ty, truckW, truckH);

        // data column
        final float colX = g2 + r * 1.25f, colW = W - colX - W * 0.03f;
        if (colW > r) {
            float rowH = H / 6.2f, y = H * 0.17f;
            textLabel.setTextSize(r * 0.17f); textLabel.setTextAlign(Paint.Align.LEFT);
            textSmall.setTextSize(r * 0.27f); textSmall.setTextAlign(Paint.Align.RIGHT); textSmall.setColor(colorPrimary);
            String[][] rows = {
                {"OUTSIDE", Fmt.tempF(data.outsideC())},
                {"BATTERY", Fmt.volts(data.batteryVolts())},
                {"SPEED", data.speedKph() == null ? Fmt.NONE : String.format(java.util.Locale.US, "%.0f", data.speedKph() * 0.621371f)},
                {"DOORS", data.doorsOpen() < 0 ? Fmt.NONE : data.doorsOpen() == 0 ? "CLOSED" : data.doorsOpen() + " OPEN"},
                {"SERVICE", serviceText != null ? serviceText : Fmt.NONE},
            };
            for (String[] row : rows) {
                c.drawText(row[0], colX, y, textLabel);
                c.drawText(row[1], colX + colW, y, textSmall);
                y += rowH;
            }
            textSmall.setColor(colorSecondary);
            textSmall.setTextAlign(Paint.Align.CENTER);
        }

        // source line, bottom of the data column (was under the truck, where it hit the tailgate)
        textLabel.setTextSize(r * 0.15f);
        textLabel.setTextAlign(Paint.Align.RIGHT);
        String src = data.available() ? (data.coolantC() != null ? "CAN BUS + OBD" : "CAN BUS") : "NO VEHICLE DATA";
        c.drawText(src, W - W * 0.03f, H * 0.95f, textLabel);
        textLabel.setTextAlign(Paint.Align.CENTER);
    }

    private static Float fOf(Float celsius) { return celsius == null ? null : celsius * 9f / 5f + 32f; }

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

    // ---- truck (TruckOutline = traced RAM 3500 blueprint) ----

    private static void polygon(Path p, float[] xy, boolean close) {
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        if (close) p.close();
    }

    private static void rect(Path p, float[] r, float radius) {
        p.addRoundRect(r[0], r[1], r[2], r[3], radius, radius, Path.Direction.CW);
    }

    private void buildTruck() {
        polygon(body, TruckOutline.BODY, true);
        rect(hoodBulge, TruckOutline.HOOD_BULGE, 8f);
        float[] w = TruckOutline.WINDSHIELD, bow = TruckOutline.COWL_BOW;
        windshield.moveTo(w[0], w[1]); windshield.lineTo(w[2], w[3]); windshield.lineTo(w[4], w[5]);
        windshield.lineTo(w[6], w[7]); windshield.quadTo(bow[0], bow[1], w[0], w[1]); windshield.close();
        rect(roof, TruckOutline.ROOF, 14f);
        polygon(rearWindow, TruckOutline.REAR_WINDOW, true);
        polygon(cabBack, TruckOutline.CAB_BACK, false);
        rect(bed, TruckOutline.BED, 4f);
        rect(bedFloor, TruckOutline.BED_FLOOR, 3f);
        rect(tailgate, TruckOutline.TAILGATE, 3f);
        for (float[] r : TruckOutline.LAMPS) rect(lamps, r, 1.5f);
        for (float[] r : TruckOutline.TAILLIGHTS) rect(taillights, r, 2f);
        for (float[] r : TruckOutline.BOARDS) rect(boards, r, 2f);
        for (float[] r : TruckOutline.WHEELS_INNER) rect(wheelsInner, r, 4f);
        for (float[] q : TruckOutline.HEADLIGHTS) polygon(headlights, q, true);
        for (float[] q : TruckOutline.MIRRORS) polygon(mirrors, q, true);
        for (float[] q : TruckOutline.DOOR_SEAMS) polygon(seams, q, false);
    }

    private void drawTruck(Canvas c, float x, float y, float w, float h) {
        final float s = h / TruckOutline.LENGTH;
        truckMatrix.reset();
        truckMatrix.setScale(s, s);
        truckMatrix.postTranslate(x, y);
        bodyStroke.setStrokeWidth(Math.max(1.5f, s * 1.8f));
        detail.setStrokeWidth(Math.max(1f, s * 1.2f));
        wheelStroke.setStrokeWidth(Math.max(1.5f, s * 2.2f));

        // wheels first: they sit under the fenders and show through the translucent body
        for (int i = 0; i < 4; i++) {
            Float psi = data.tirePsi(i);
            float[] u = TruckOutline.WHEELS[i];
            rect.set(x + u[0] * s, y + u[1] * s, x + u[2] * s, y + u[3] * s);
            c.drawRoundRect(rect, s * 4f, s * 4f, wheelFill);
            wheelStroke.setColor(psi == null ? Color.argb(120, 255, 255, 255)
                    : (psi < PSI_LOW || psi > PSI_HIGH) ? colorAmber : colorGreen);
            c.drawRoundRect(rect, s * 4f, s * 4f, wheelStroke);
        }
        drawPath(c, wheelsInner, wheelInner);

        drawPath(c, body, bodyFill); drawPath(c, body, bodyStroke);
        drawPath(c, hoodBulge, bulgeFill); drawPath(c, hoodBulge, detail);
        drawPath(c, windshield, glass);
        drawPath(c, roof, bodyFill); drawPath(c, roof, detail);
        drawPath(c, rearWindow, glass);
        drawPath(c, lamps, lampOrange);
        drawPath(c, headlights, lamp);
        lamp.setColor(Color.argb(220, 229, 62, 62)); drawPath(c, taillights, lamp);
        lamp.setColor(Color.argb(200, 255, 236, 180));
        drawPath(c, seams, detail);
        drawPath(c, cabBack, detail);
        drawPath(c, boards, boardFill);
        drawPath(c, mirrors, boardFill); drawPath(c, mirrors, bodyStroke);
        drawPath(c, bed, detail);
        drawPath(c, bedFloor, bedFill);
        drawPath(c, tailgate, bulgeFill); drawPath(c, tailgate, detail);

        // tire pressure beside each wheel: big enough to read at a glance, red
        // below PSI_LOW (Beau, 2026-10-25: 30 psi), amber above PSI_HIGH.
        textBig.setTextSize(h * 0.17f);
        textLabel.setTextSize(h * 0.075f);
        for (int i = 0; i < 4; i++) {
            Float psi = data.tirePsi(i);
            float[] u = TruckOutline.WHEELS[i];
            boolean left = (i % 2 == 0);
            float wx = left ? x + u[0] * s : x + u[2] * s;
            float tx = left ? wx - h * 0.04f : wx + h * 0.04f;
            float midY = y + (u[1] + u[3]) / 2f * s;
            textBig.setTextAlign(left ? Paint.Align.RIGHT : Paint.Align.LEFT);
            textLabel.setTextAlign(left ? Paint.Align.RIGHT : Paint.Align.LEFT);
            textBig.setColor(psi == null ? colorSecondary : psi < PSI_LOW ? colorRed : psi > PSI_HIGH ? colorAmber : colorPrimary);
            c.drawText(Fmt.psi(psi), tx, midY + textBig.getTextSize() * 0.15f, textBig);
            c.drawText("PSI", tx, midY + textBig.getTextSize() * 0.15f + textLabel.getTextSize() * 1.25f, textLabel);
        }
        textBig.setColor(colorPrimary);
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
