package com.ramphoenix.launcher;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import static com.ramphoenix.launcher.MaintenanceUi.*;

/** A separate, accessible air-spring entry in the vehicle card; never confused with TPMS. */
public class SuspensionWidget extends LinearLayout {
    private final TextView pressure;
    private final WirelessOneClient client;
    private final Runnable update = this::refresh;
    public SuspensionWidget(Context context) { this(context, null); }
    public SuspensionWidget(Context context, AttributeSet attrs) {
        super(context, attrs); client = WirelessOneClient.get(context);
        setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(context,12),0,dp(context,12),0); setMinimumHeight(dp(context,44));
        setBackgroundResource(R.drawable.climate_tile); setClickable(true); setFocusable(true);
        android.widget.ImageView icon = new android.widget.ImageView(context);
        icon.setImageDrawable(icon(context,R.drawable.ic_air_spring,context.getColor(R.color.text_secondary),22));
        addView(icon,new LayoutParams(dp(context,26),dp(context,26)));
        TextView title = text(context,"Air suspension",12,R.color.text_secondary);
        title.setPadding(dp(context,8),0,0,0); addView(title,new LayoutParams(0,-2,1));
        pressure = text(context,"— psi",16,R.color.text_primary); addView(pressure);
        TextView chevron = text(context,"›",24,R.color.text_tertiary);
        chevron.setPadding(dp(context,12),0,0,0); addView(chevron);
        setOnClickListener(v -> context.startActivity(new Intent(context,SuspensionActivity.class)));
        refresh();
    }
    private void refresh() {
        String value = client.actualText();
        pressure.setText(value + " psi");
        setContentDescription("Air suspension. Measured pressure " + value + " psi. Open pressure presets and setup.");
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow();client.listen(update);refresh(); }
    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if(visible)client.start(this);else client.stop(this);
    }
    @Override protected void onDetachedFromWindow() { client.unlisten(update);client.stop(this);super.onDetachedFromWindow(); }
}
