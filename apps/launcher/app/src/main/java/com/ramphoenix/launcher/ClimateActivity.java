package com.ramphoenix.launcher;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

/** Full-screen climate page. ClimateWidget drives it through the same ids as the card. */
public class ClimateActivity extends Activity {

    private ClimateWidget climate;
    private View barL, barR;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable barTick = new Runnable() {
        @Override public void run() { placeBars(); ui.postDelayed(this, 500); }
    };

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Ui.applyDensity(this);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_climate);
        View root = findViewById(R.id.climate_card);
        climate = new ClimateWidget(this, root);
        barL = findViewById(R.id.cl_bar_l);
        barR = findViewById(R.id.cl_bar_r);
        findViewById(R.id.climate_back).setOnClickListener(v -> finish());
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    /** Slide the marker on each colour bar to the set temperature (LO..HI = bottom..top). */
    private void placeBars() {
        place(barL, climate.raw(ClimateWidget.C_TEMP_L));
        place(barR, climate.raw(ClimateWidget.C_TEMP_R));
    }

    private void place(View bar, int raw) {
        View parent = (View) bar.getParent();
        int h = parent.getHeight();
        if (h <= 0) return;
        boolean known = raw == -2 || raw == -3 || (raw >= 20 && raw <= 128);
        bar.setVisibility(known ? View.VISIBLE : View.INVISIBLE);
        if (!known) return;
        float f;
        if (raw == -2) f = 0f; else if (raw == -3) f = 1f;
        else if (raw < 20 || raw > 128) f = 0.5f;
        else f = climate.raw(ClimateWidget.C_TEMP_UNIT) == 1 ? (raw - 60f) / 25f : (raw - 32f) / 32f;
        f = Math.max(0f, Math.min(1f, f));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        lp.topMargin = Math.round((1f - f) * (h - bar.getHeight()));
        bar.setLayoutParams(lp);
    }

    @Override protected void onResume() { super.onResume(); climate.onResume(); ui.post(barTick); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(barTick); climate.onPause(); }
}
