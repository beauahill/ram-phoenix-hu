package com.ramphoenix.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.List;

/**
 * The bottom dock: Apps, Camera, Navigation, Phone, Music, Radio, Settings.
 * Targets are resolved by intent category where Android has one (maps, music,
 * dialer) so we don't depend on guessed SYU package names; the fallbacks list
 * the packages seen on this unit. A dock item with no target is shown dimmed.
 */
public final class Dock {
    private Dock() {}

    private static final class Item {
        final int label, icon, color; final Intent intent;
        Item(int label, int icon, int color, Intent intent) {
            this.label = label; this.icon = icon; this.color = color; this.intent = intent;
        }
    }

    public static void build(Activity act, LinearLayout dock) {
        dock.removeAllViews();
        Item[] items = {
                new Item(R.string.apps,       R.drawable.ic_apps,     R.color.dock_apps,
                        new Intent(act, AppDrawerActivity.class)),
                new Item(R.string.camera,     R.drawable.ic_camera,   R.color.dock_camera,   cameraIntent(act)),
                new Item(R.string.navigation, R.drawable.ic_nav,      R.color.dock_nav,      navIntent(act)),
                new Item(R.string.phone,      R.drawable.ic_phone,    R.color.dock_phone,    phoneIntent(act)),
                new Item(R.string.music,      R.drawable.ic_music,    R.color.dock_music,    musicIntent(act)),
                new Item(R.string.radio,      R.drawable.ic_radio,    R.color.dock_radio,    radioIntent(act)),
                new Item(R.string.settings,   R.drawable.ic_settings, R.color.dock_settings,
                        new Intent(Settings.ACTION_SETTINGS)),
        };
        final float d = act.getResources().getDisplayMetrics().density;
        final int size = Math.round(56 * d);
        for (Item it : items) {
            ImageView b = new ImageView(act);
            b.setImageResource(it.icon);
            b.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = Math.round(13 * d);
            b.setPadding(pad, pad, pad, pad);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(act.getColor(it.color));
            bg.setCornerRadius(17 * d);
            if (it.color == R.color.dock_apps) bg.setStroke(Math.round(d), act.getColor(R.color.glass_stroke));
            b.setBackground(bg);
            b.setContentDescription(act.getString(it.label));
            b.setClickable(true);
            b.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.leftMargin = lp.rightMargin = Math.round(8 * d);
            b.setLayoutParams(lp);
            if (it.intent == null) b.setAlpha(0.35f);
            b.setOnClickListener(v -> launch(act, it.intent, it.label));
            dock.addView(b);
        }
    }

    private static void launch(Activity act, Intent i, int label) {
        if (i == null) {
            Toast.makeText(act, act.getString(label) + ": " + act.getString(R.string.not_installed),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent c = new Intent(i);
            c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            act.startActivity(c);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(act, act.getString(R.string.not_installed), Toast.LENGTH_SHORT).show();
        }
    }

    // ---- target resolution ----

    public static Intent cameraIntent(Context ctx) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage("com.ramphoenix.camera");
        if (i != null) i.setAction("com.ramphoenix.camera.SHOW");
        return i;
    }

    public static Intent navIntent(Context ctx) {
        Intent i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS);
        if (resolves(ctx, i)) return i;
        return firstInstalled(ctx, "com.autonavi.amapauto", "com.google.android.apps.maps", "com.waze");
    }

    public static Intent phoneIntent(Context ctx) {
        Intent i = new Intent(Intent.ACTION_DIAL);
        if (resolves(ctx, i)) return i;
        return firstInstalled(ctx, "com.syu.bt");
    }

    public static Intent musicIntent(Context ctx) {
        Intent i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
        if (resolves(ctx, i)) return i;
        Intent f = firstInstalled(ctx, "com.syu.music", "com.spotify.music");
        return f != null ? f : byLabel(ctx, "music");
    }

    public static Intent radioIntent(Context ctx) {
        Intent f = firstInstalled(ctx, "com.syu.radio");
        return f != null ? f : byLabel(ctx, "radio");
    }

    private static boolean resolves(Context ctx, Intent i) {
        return ctx.getPackageManager().resolveActivity(i, 0) != null;
    }

    private static Intent firstInstalled(Context ctx, String... pkgs) {
        PackageManager pm = ctx.getPackageManager();
        for (String p : pkgs) {
            Intent i = pm.getLaunchIntentForPackage(p);
            if (i != null) return i;
        }
        return null;
    }

    /** Last resort: the first launchable app whose label contains the word. */
    private static Intent byLabel(Context ctx, String word) {
        List<AppInfo> apps = AppRepository.loadLaunchable(ctx);
        for (AppInfo a : apps) {
            if (a.label.toLowerCase().contains(word)) {
                return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(a.component);
            }
        }
        return null;
    }
}
