package com.ramphoenix.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Park-assist display styled after the RAM instrument cluster (Beau's photo,
 * 2026-10-25): a white top-down truck with three concentric arc bands ahead of
 * the nose and behind the tailgate. Each band is split into four segments that
 * map to the four sensors on that end. The outer band lights amber for a far
 * object, the middle band amber for a medium one, the inner band red (flashing)
 * for a close one. A thin blue arc hugs each bumper while the sensors report,
 * the way the cluster marks park assist as active.
 *
 * Fed by a {@link ParkingSensorSource}; proximity 0 = clear, 1 = touching.
 */
public class ParkingSensorView extends View {

    private static final int AMBER = Color.rgb(0xF5, 0xA6, 0x23);
    private static final int RED   = Color.rgb(0xE8, 0x3A, 0x2E);
    private static final int BLUE  = Color.rgb(0x3F, 0x8C, 0xFF);
    private static final int BAND_OFF = Color.argb(0x28, 0xFF, 0xFF, 0xFF);

    // proximity thresholds for the three bands
    private static final float FAR = 0.05f, MID = 0.40f, NEAR = 0.75f;

    private final Paint fill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint band   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text   = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path body = new Path(), windshield = new Path(), rearWindow = new Path(),
            roof = new Path(), bed = new Path(), hood = new Path(), wheels = new Path(),
            mirrors = new Path();
    private final Path scratch = new Path();
    private final Matrix m = new Matrix();
    private final RectF oval = new RectF();

    private ParkingSensorSource source = ParkingSensorSource.STUB;

    public ParkingSensorView(Context c) { this(c, null); }
    public ParkingSensorView(Context c, AttributeSet a) {
        super(c, a);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        band.setStyle(Paint.Style.STROKE);
        band.setStrokeCap(Paint.Cap.BUTT);
        text.setColor(Color.argb(150, 230, 230, 230));
        text.setTextSize(26f);
        text.setTextAlign(Paint.Align.CENTER);
        buildTruck();
    }

    public void setSource(ParkingSensorSource s) {
        this.source = (s == null) ? ParkingSensorSource.STUB : s;
    }

    /** Drive repaints at ~15fps while visible so the near-band flash animates. */
    public void tick() { if (getVisibility() == VISIBLE) invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        final float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Truck occupies the middle ~52% of the height, nose up; arcs need room above and below.
        final float truckH = h * 0.52f;
        final float truckW = truckH * (TruckOutline.WIDTH / TruckOutline.LENGTH);
        final float cx = w / 2f, cy = h / 2f;
        final float top = cy - truckH / 2f, bottom = cy + truckH / 2f;
        final float s = truckH / TruckOutline.LENGTH;

        final float[] p = source.proximity();
        final boolean avail = source.available();
        final boolean flash = ((SystemClock.uptimeMillis() / 160L) % 2L) == 0L;

        // ---- arcs ----
        // Each end: three bands centred on the bumper midpoint, radii growing outward.
        final float bandW = truckW * 0.16f;
        final float gap = bandW * 0.38f;
        final float r0 = truckW * 0.62f;             // inner band radius
        for (int end = 0; end < 2; end++) {
            final boolean front = end == 0;
            final float ox = cx, oy = front ? top + truckW * 0.10f : bottom - truckW * 0.10f;
            final int z0 = front ? ParkingSensorSource.FL : ParkingSensorSource.RL;
            // the arc spans 150 degrees over the bumper, split into 4 segments with gaps
            final float span = 150f, segGap = 4f;
            final float start = front ? 195f : 15f;        // canvas angles: 0 = 3 o'clock, clockwise
            final float seg = (span - segGap * 3f) / 4f;
            for (int b = 0; b < 3; b++) {
                final float r = r0 + b * (bandW + gap);
                oval.set(ox - r, oy - r, ox + r, oy + r);
                band.setStrokeWidth(bandW);
                for (int i = 0; i < 4; i++) {
                    // front segments run left->right across the top; rear left->right across the bottom
                    final int zone = z0 + (front ? i : 3 - i);
                    final float prox = p[zone];
                    int color = BAND_OFF;
                    boolean lit = false;
                    if (b == 0 && prox >= FAR)  { color = AMBER; lit = true; }
                    if (b == 1 && prox >= MID)  { color = AMBER; lit = true; }
                    if (b == 2 && prox >= NEAR) { color = flash ? RED : Color.argb(0x60, 0xE8, 0x3A, 0x2E); lit = true; }
                    if (!avail) color = BAND_OFF;
                    band.setColor(color);
                    // unlit bands draw faintly so the layout reads even when clear
                    final float a = start + i * (seg + segGap);
                    canvas.drawArc(oval, a, seg, false, band);
                    if (lit && b == 2 && flash) {
                        // glow for the near band
                        band.setColor(Color.argb(0x50, 0xE8, 0x3A, 0x2E));
                        band.setStrokeWidth(bandW * 1.6f);
                        canvas.drawArc(oval, a, seg, false, band);
                        band.setStrokeWidth(bandW);
                    }
                }
            }
            // active marker: thin blue arc tight to the bumper
            if (avail) {
                final float r = r0 - bandW * 0.9f;
                oval.set(ox - r, oy - r, ox + r, oy + r);
                band.setStrokeWidth(bandW * 0.28f);
                band.setColor(BLUE);
                canvas.drawArc(oval, start + 15f, span - 30f, false, band);
            }
        }

        // ---- truck ----
        m.reset();
        m.setScale(s, s);
        m.postTranslate(cx - truckW / 2f, top);
        drawPath(canvas, body, Color.rgb(0xF2, 0xF2, 0xF2), 0, 0f);
        drawPath(canvas, hood, Color.rgb(0xE2, 0xE2, 0xE2), 0, 0f);
        drawPath(canvas, windshield, Color.rgb(0x2A, 0x2F, 0x38), 0, 0f);
        drawPath(canvas, roof, Color.rgb(0xEA, 0xEA, 0xEA), 0, 0f);
        drawPath(canvas, rearWindow, Color.rgb(0x2A, 0x2F, 0x38), 0, 0f);
        drawPath(canvas, bed, Color.rgb(0x1C, 0x1E, 0x22), 0, 0f);
        drawPath(canvas, wheels, Color.rgb(0x16, 0x16, 0x18), 0, 0f);
        drawPath(canvas, mirrors, Color.rgb(0xD8, 0xD8, 0xD8), 0, 0f);
        drawPath(canvas, body, 0, Color.argb(0xB0, 0x60, 0x60, 0x66), 2.5f);

        if (!avail) {
            canvas.drawText("PARK ASSIST", cx, h - 12f, text);
        }
    }

    private void drawPath(Canvas c, Path src, int fillColor, int strokeColor, float strokeW) {
        src.transform(m, scratch);
        if (fillColor != 0) { fill.setColor(fillColor); c.drawPath(scratch, fill); }
        if (strokeColor != 0) { stroke.setColor(strokeColor); stroke.setStrokeWidth(strokeW); c.drawPath(scratch, stroke); }
    }

    // ---- truck geometry from TruckOutline (traced blueprint, unit box WIDTH x LENGTH, nose up) ----

    private static void polygon(Path p, float[] xy) {
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.close();
    }

    private static void rect(Path p, float[] r, float radius) {
        p.addRoundRect(r[0], r[1], r[2], r[3], radius, radius, Path.Direction.CW);
    }

    private void buildTruck() {
        polygon(body, TruckOutline.BODY);
        rect(hood, TruckOutline.HOOD_BULGE, 8f);
        polygon(windshield, TruckOutline.WINDSHIELD);
        rect(roof, TruckOutline.ROOF, 14f);
        polygon(rearWindow, TruckOutline.REAR_WINDOW);
        rect(bed, TruckOutline.BED_FLOOR, 6f);
        for (float[] wh : TruckOutline.WHEELS) rect(wheels, wh, 6f);
        for (float[] mi : TruckOutline.MIRRORS) polygon(mirrors, mi);
    }
}
