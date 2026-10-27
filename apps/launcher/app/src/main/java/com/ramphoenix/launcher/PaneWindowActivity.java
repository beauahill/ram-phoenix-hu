package com.ramphoenix.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Own the root of a pane task so AppTask can dismiss it without system privileges. */
public abstract class PaneWindowActivity extends Activity {
    static final String TARGET = "pane_target";
    static final String KEY = "window_key", GENERATION = "window_generation";
    private boolean childStarted;
    private String key;
    private long generation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new android.widget.FrameLayout(this));
        getWindow().setBackgroundDrawableResource(R.color.bg_base);
        ImmersiveUi.apply(this);
        childStarted = state != null && state.getBoolean("child_started");
        key = getIntent().getStringExtra(KEY);
        generation = getIntent().getLongExtra(GENERATION, -1);
    }

    @Override public void onResume() {
        super.onResume();
        if (childStarted || key == null || !PaneWindows.current(this, key, generation)) {
            finishAndRemoveTask(); return;
        }
        Intent target = getIntent().getParcelableExtra(TARGET);
        if (target == null || target.getComponent() == null) { finishAndRemoveTask(); return; }
        childStarted = true;
        OemBars.keepHidden(this);
        try {
            // Keep the foreign activity inside our task. NEW_TASK here would lose ownership.
            target.setFlags(target.getFlags() & ~(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            startActivity(target);
        } catch (RuntimeException failure) {
            android.util.Log.w("PhoenixLauncher", "Pane child could not launch", failure);
            finishAndRemoveTask();
        }
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putBoolean("child_started", childStarted);
        super.onSaveInstanceState(state);
    }

    @Override public void onDestroy() {
        if (key != null && !isChangingConfigurations()) PaneWindows.finished(this, key, generation);
        super.onDestroy();
    }
}
