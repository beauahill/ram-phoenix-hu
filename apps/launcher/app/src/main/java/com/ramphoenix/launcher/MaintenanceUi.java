package com.ramphoenix.launcher;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.*;
import android.widget.*;
import java.util.Locale;

/** Small native view helpers shared by maintenance pages and keyboard-safe forms. */
final class MaintenanceUi {
    private MaintenanceUi() {}
    static int dp(Context c,float n) {return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static LinearLayout column(Context c) {LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.VERTICAL);return v;}
    static LinearLayout row(Context c) {LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    static TextView text(Context c,String value,int size,int color) {
        TextView v=new TextView(c);v.setText(value);v.setTextSize(size);v.setTextColor(c.getColor(color));v.setIncludeFontPadding(false);return v;
    }
    static LinearLayout.LayoutParams match(int height) {return new LinearLayout.LayoutParams(-1,height);}
    static void gap(LinearLayout parent,int height) {parent.addView(new View(parent.getContext()),match(dp(parent.getContext(),height)));}
    static Drawable icon(Context c,int resource,int color,int size) {
        Drawable d=c.getDrawable(resource).mutate();d.setTint(color);int s=dp(c,size);d.setBounds(0,0,s,s);return d;
    }
    static TextView action(Context c,String label,int resource,boolean primary) {
        TextView v=text(c,label,14,primary?R.color.bg_base:R.color.text_primary);
        v.setGravity(Gravity.CENTER);v.setMinHeight(dp(c,48));v.setPadding(dp(c,12),0,dp(c,12),0);
        v.setBackgroundResource(primary?R.drawable.maint_primary:R.drawable.climate_tile);
        v.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));v.setClickable(true);v.setFocusable(true);
        if(resource!=0) {v.setCompoundDrawables(icon(c,resource,c.getColor(primary?R.color.bg_base:R.color.text_secondary),20),null,null,null);v.setCompoundDrawablePadding(dp(c,8));}
        return v;
    }
    static ImageView iconButton(Context c,int resource,String description) {
        ImageView v=new ImageView(c);v.setImageDrawable(icon(c,resource,c.getColor(R.color.text_secondary),24));v.setPadding(dp(c,10),dp(c,10),dp(c,10),dp(c,10));
        v.setContentDescription(description);v.setTooltipText(description);v.setBackgroundResource(R.drawable.climate_tile);v.setClickable(true);v.setFocusable(true);return v;
    }
    static LinearLayout card(Context c) {LinearLayout v=column(c);int p=dp(c,16);v.setPadding(p,p,p,p);v.setBackgroundResource(R.drawable.glass_card);return v;}
    static String miles(int value) {return value<0?"—":String.format(Locale.US,"%,d mi",value);}
    static String updated(long value){return java.time.format.DateTimeFormatter.ofPattern("MMM d · h:mm a",Locale.US).format(java.time.Instant.ofEpochMilli(value).atZone(java.time.ZoneId.systemDefault()));}
    static int color(int state) {return state==MaintenanceBook.OVERDUE?R.color.bad_red:state==MaintenanceBook.DUE?R.color.warn_amber:state==MaintenanceBook.SOON?R.color.accent:state==MaintenanceBook.CURRENT?R.color.ok_green:R.color.text_secondary;}
    static void immersive(Activity activity) {
        OemBars.keepHidden(activity);
        // FLAG_FULLSCREEN prevents adjustResize on some IMEs. Use the inset controller instead.
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        activity.getWindow().setDecorFitsSystemWindows(true);
        activity.getWindow().getDecorView().setSystemUiVisibility(0);
        android.view.WindowInsetsController controller=activity.getWindow().getInsetsController();
        if(controller!=null) {controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);controller.hide(WindowInsets.Type.systemBars());}
    }
}
