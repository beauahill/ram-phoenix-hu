package com.ramphoenix.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.List;

/**
 * The top dock: Apps, Camera, Navigation, Phone, Music, Radio, Settings.
 * Targets are resolved by intent category where Android has one (maps, music,
 * dialer) so we don't depend on guessed SYU package names; the fallbacks list
 * the packages seen on this unit. Resolution runs off the main thread (it can
 * scan every launchable app) and the buttons light up as targets arrive; an
 * item with no target stays dimmed. {@link #build} is cheap to call again
 * (package added/removed).
 */
public final class Dock {
    private Dock() {}

    private static final class Item {
        final int label, icon, color;
        Item(int label, int icon, int color) { this.label = label; this.icon = icon; this.color = color; }
    }

    private static final Item[] ITEMS = {
            new Item(R.string.apps,       R.drawable.ic_apps,     R.color.dock_apps),
            new Item(R.string.camera,     R.drawable.ic_camera,   R.color.dock_camera),
            new Item(R.string.navigation, R.drawable.ic_nav,      R.color.dock_nav),
            new Item(R.string.phone,      R.drawable.ic_phone,    R.color.dock_phone),
            new Item(R.string.music,      R.drawable.ic_music,    R.color.dock_music),
            new Item(R.string.radio,      R.drawable.ic_radio,    R.color.dock_radio),
            new Item(R.string.settings,   R.drawable.ic_settings, R.color.dock_settings),
    };

    public static void build(final Activity act, final LinearLayout dock) {
        dock.removeAllViews();
        final float d = act.getResources().getDisplayMetrics().density;
        final int size = Math.round(56 * d);
        final ImageView[] buttons = new ImageView[ITEMS.length];
        for (int i = 0; i < ITEMS.length; i++) {
            Item it = ITEMS[i];
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
            b.setAlpha(0.35f);
            b.setTag(null);
            final int label = it.label;
            b.setOnClickListener(v -> launch(act, (Intent) v.getTag(), label));
            dock.addView(b);
            buttons[i] = b;
        }
        // Apps and Settings resolve trivially; do them now.
        set(buttons[0], new Intent(act, AppDrawerActivity.class));
        set(buttons[6], new Intent(Settings.ACTION_SETTINGS));

        new Thread(() -> {
            final Intent cam = cameraIntent(act), nav = navIntent(act), phone = phoneIntent(act),
                    music = musicIntent(act), radio = radioIntent(act);
            act.runOnUiThread(() -> {
                if (dock.getChildCount() != ITEMS.length) return; // rebuilt meanwhile
                set(buttons[1], cam); set(buttons[2], nav); set(buttons[3], phone);
                set(buttons[4], music); set(buttons[5], radio);
            });
        }, "dock-resolve").start();
    }

    private static void set(ImageView b, Intent i) {
        b.setTag(i);
        b.setAlpha(i == null ? 0.35f : 1f);
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
            Toast.makeText(act, R.string.not_installed, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- target resolution (safe to call off the main thread) ----

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
        Intent f = firstInstalled(ctx, "com.syu.bt");
        return f != null ? f : byLabel(ctx, "bluetooth", "phone");
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

    /** Last resort: the first launchable app whose label contains one of the words. */
    private static Intent byLabel(Context ctx, String... words) {
        List<AppInfo> apps = AppRepository.loadLaunchable(ctx);
        for (String word : words) {
            for (AppInfo a : apps) {
                if (a.label.toLowerCase().contains(word)) {
                    return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(a.component);
                }
            }
        }
        return null;
    }
}
