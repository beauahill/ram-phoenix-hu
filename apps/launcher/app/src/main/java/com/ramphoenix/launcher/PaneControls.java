package com.ramphoenix.launcher;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Small overlay hit target, rather than reserving a permanent strip beside each pane. */
final class PaneControls {
    private final Activity activity;
    private final Runnable choose;
    private final WindowManager windows;
    private ImageView chevron;
    private View menu;
    PaneControls(Activity activity,Runnable choose) {
        this.activity=activity; this.choose=choose; windows=activity.getSystemService(WindowManager.class);
    }
    private int dp(int value) { return Math.round(value*activity.getResources().getDisplayMetrics().density); }
    private WindowManager.LayoutParams params(int width,int height,int x,int y) {
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT; p.x=x; p.y=y; p.setFitInsetsTypes(0); return p;
    }
    void update(boolean show,Rect bounds) {
        if(!show || !Settings.canDrawOverlays(activity)) { hide(); return; }
        WindowManager.LayoutParams p=params(dp(44),dp(44),bounds.right-dp(44),bounds.top);
        if(chevron==null) {
            chevron=new ImageView(activity); chevron.setPadding(dp(11),dp(11),dp(11),dp(11));
            chevron.setImageResource(R.drawable.ic_m_keyboard_arrow_down);
            chevron.setBackgroundResource(R.drawable.glass_circle);
            chevron.setContentDescription("Choose pane content"); chevron.setOnClickListener(v -> choose.run());
            windows.addView(chevron,p);
        } else windows.updateViewLayout(chevron,p);
    }
    void menu(Rect bounds,Pane.Content selected,java.util.function.Consumer<Pane.Content> selection) {
        dismissMenu();
        if(!Settings.canDrawOverlays(activity)) return;
        LinearLayout list=new LinearLayout(activity); list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8),dp(8),dp(8),dp(8)); list.setBackgroundResource(R.drawable.glass_card);
        String[] labels={"Map","Media","Vehicle","Climate","Apps"};
        Pane.Content[] values=Pane.Content.values();
        for(int i=0;i<values.length;i++) {
            final Pane.Content value=values[i]; TextView item=new TextView(activity);
            item.setText(labels[i]+(value==selected?"  •":"")); item.setTextSize(16);
            item.setTextColor(0xffedf0f5); item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(14),0,dp(14),0);
            list.addView(item,new LinearLayout.LayoutParams(dp(160),dp(44)));
            item.setOnClickListener(v -> { dismissMenu(); selection.accept(value); });
        }
        WindowManager.LayoutParams p=params(dp(176),WindowManager.LayoutParams.WRAP_CONTENT,bounds.right-dp(176),bounds.top+dp(42));
        p.flags|=WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        list.setOnTouchListener((v,e) -> { if(e.getAction()==android.view.MotionEvent.ACTION_OUTSIDE) dismissMenu(); return false; });
        menu=list; windows.addView(menu,p);
    }
    void dismissMenu() { if(menu!=null) { windows.removeView(menu); menu=null; } }
    void hide() { dismissMenu(); if(chevron!=null) { windows.removeView(chevron); chevron=null; } }
}
