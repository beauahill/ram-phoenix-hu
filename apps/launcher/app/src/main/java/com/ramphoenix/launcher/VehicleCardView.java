package com.ramphoenix.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.text.TextPaint;
import android.text.TextUtils;
import java.util.Locale;

/** Vehicle readings retain their sources; the presentation uses a rendered SRW truck and digital gauges. */
public class VehicleCardView extends View {
    private final TextPaint value = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Bitmap truck;
    private final int primary, secondary, tertiary, stroke, accent, red;
    private VehicleData data = VehicleData.STUB;
    private String serviceText, obdStatus;

    public VehicleCardView(Context c) { this(c, null); }
    public VehicleCardView(Context c, AttributeSet a) {
        super(c, a);
        primary = c.getColor(R.color.text_primary);
        secondary = c.getColor(R.color.text_secondary);
        tertiary = c.getColor(R.color.text_tertiary);
        stroke = c.getColor(R.color.glass_stroke);
        accent = c.getColor(R.color.accent);
        red = c.getColor(R.color.bad_red);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setLetterSpacing(0.06f);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inSampleSize = 2; // 512x768 is ample for this card; decode once.
        truck = BitmapFactory.decodeResource(getResources(), R.drawable.truck_oem_v1, options);
    }

    public void setData(VehicleData v) { data = v == null ? VehicleData.STUB : v; invalidate(); }
    public void setServiceText(String text) { serviceText = text; invalidate(); }
    public void setObdStatus(String text) { obdStatus = text; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float unit = Math.min(w / 500f, h / 190f);
        label.setTextSize(9f * unit);
        label.setColor(tertiary);
        label.setTextAlign(Paint.Align.LEFT);
        c.drawText("TIRE PRESSURES", w * 0.025f, h * 0.075f, label);

        float truckH = Math.min(h * 0.85f, w * 0.33f);
        float truckW = truckH * truck.getWidth() / truck.getHeight();
        float cx = w * 0.17f, top = h * 0.11f;
        rect.set(cx - truckW / 2, top, cx + truckW / 2, top + truckH);
        ink.setColor(primary);
        c.drawBitmap(truck, null, rect, ink);

        for (int i = 0; i < 4; i++) {
            Float psi = data.tirePsi(i);
            boolean left = i % 2 == 0;
            float x = cx + (left ? -1 : 1) * truckW * 0.44f;
            float y = top + truckH * (i < 2 ? 0.20f : 0.79f);
            value.setTextAlign(left ? Paint.Align.RIGHT : Paint.Align.LEFT);
            value.setTextSize(17f * unit);
            value.setColor(psi == null ? secondary : psi < 30f ? red : psi > 85f ? accent : primary);
            c.drawText(Fmt.psi(psi), x, y, value);
            label.setTextAlign(value.getTextAlign());
            label.setTextSize(7.5f * unit);
            c.drawText("PSI", x, y + 11f * unit, label);
        }

        ink.setColor(stroke);
        c.drawRect(w * 0.335f, h * 0.12f, w * 0.335f + unit * 0.5f, h * 0.88f, ink);
        float mx = w * 0.375f, mw = w * 0.155f, gap = w * 0.045f;
        GaugeReading coolant = data.reading(VehicleData.Gauge.COOLANT), trans = data.reading(VehicleData.Gauge.TRANSMISSION);
        GaugeReading oilPressure = data.reading(VehicleData.Gauge.OIL_PRESSURE), oilTemperature = data.reading(VehicleData.Gauge.OIL_TEMPERATURE);
        metric(c, mx, h * 0.12f, mw, h * 0.34f, "COOLANT", Fmt.tempF(coolant.value), fahrenheit(coolant.value), 100, 260, unit);
        metric(c, mx + mw + gap, h * 0.12f, mw, h * 0.34f, "TRANS", Fmt.tempF(trans.value), fahrenheit(trans.value), 100, 260, unit);
        Float oil = oilPressure.value;
        metric(c, mx, h * 0.55f, mw, h * 0.34f, "OIL PSI", oil == null ? Fmt.NONE : String.format(Locale.US, "%.0f", oil), oil, 0, 80, unit);
        metric(c, mx + mw + gap, h * 0.55f, mw, h * 0.34f, "OIL TEMP", Fmt.tempF(oilTemperature.value), fahrenheit(oilTemperature.value), 100, 280, unit);

        float rx = w * 0.79f, right = w * 0.98f;
        row(c, rx, right, h * 0.18f, "OUTSIDE", Fmt.tempF(data.outsideC()), unit);
        row(c, rx, right, h * 0.34f, "BATTERY", Fmt.volts(data.batteryVolts()), unit);
        row(c, rx, right, h * 0.50f, "SPEED", data.speedKph() == null ? Fmt.NONE : String.format(Locale.US, "%.0f", data.speedKph() * 0.621371f), unit);
        row(c, rx, right, h * 0.66f, "DOORS", data.doorsOpen() < 0 ? Fmt.NONE : data.doorsOpen() == 0 ? "CLOSED" : data.doorsOpen() + " OPEN", unit);
        row(c, rx, right, h * 0.82f, "SERVICE", serviceText == null ? Fmt.NONE : serviceText, unit);
        label.setTextSize(7.5f * unit);
        label.setTextAlign(Paint.Align.RIGHT);
        String source = data.available() ? GaugeReading.summary(coolant, trans, oilPressure, oilTemperature) : "NO VEHICLE DATA";
        boolean allCan = coolant.source == GaugeReading.Source.CAN && trans.source == GaugeReading.Source.CAN
                && oilPressure.source == GaugeReading.Source.CAN && oilTemperature.source == GaugeReading.Source.CAN;
        if (!allCan && obdStatus != null) source += " · " + obdStatus;
        label.setLetterSpacing(0f);
        source = TextUtils.ellipsize(source, label, right - w * 0.35f, TextUtils.TruncateAt.END).toString();
        c.drawText(source, right, h * 0.97f, label);
        label.setLetterSpacing(0.06f);
    }

    private void metric(Canvas c, float x, float y, float w, float h, String title, String text,
                        Float reading, float min, float max, float unit) {
        label.setTextAlign(Paint.Align.LEFT);
        label.setTextSize(8f * unit);
        c.drawText(title, x, y + 10 * unit, label);
        value.setTextAlign(Paint.Align.LEFT);
        value.setColor(reading == null ? secondary : primary);
        value.setTextSize(22f * unit);
        c.drawText(text, x, y + h * 0.69f, value);
        ink.setColor(stroke);
        rect.set(x, y + h - 3 * unit, x + w, y + h);
        c.drawRoundRect(rect, unit, unit, ink);
        if (reading != null) {
            ink.setColor(getContext().getColor(R.color.teal));
            rect.right = x + w * Math.max(0f, Math.min(1f, (reading - min) / (max - min)));
            c.drawRoundRect(rect, unit, unit, ink);
        }
    }

    private void row(Canvas c, float x, float right, float y, String title, String text, float unit) {
        label.setTextSize(8.5f * unit);
        label.setTextAlign(Paint.Align.LEFT);
        c.drawText(title, x, y, label);
        value.setTextSize(12f * unit);
        value.setTextAlign(Paint.Align.RIGHT);
        value.setColor(primary);
        // Service text can be user supplied: keep it inside its existing column.
        float available = Math.max(0, right - x - label.measureText(title) - 5 * unit);
        c.drawText(TextUtils.ellipsize(text, value, available, TextUtils.TruncateAt.END).toString(), right, y, value);
    }

    private static Float fahrenheit(Float c) { return c == null ? null : c * 9 / 5 + 32; }
}
