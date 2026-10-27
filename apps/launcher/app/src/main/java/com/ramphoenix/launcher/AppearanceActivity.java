package com.ramphoenix.launcher;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
import com.ramphoenix.appearance.WallpaperScene;
import java.util.concurrent.*;
import static com.ramphoenix.launcher.MaintenanceUi.*;

/** Wallpaper selection and restrained glass settings with live background preview. */
public final class AppearanceActivity extends Activity {
    private static final int PICK=71;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private TextView choose,reset,status;
    private boolean busy;
    @Override protected void attachBaseContext(Context base) {super.attachBaseContext(base);Ui.applyDensity(this);}
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root=column(this);root.setPadding(dp(this,16),dp(this,16),dp(this,16),dp(this,16));
        WallpaperScene.content(this,root);
        LinearLayout header=row(this);ImageView back=iconButton(this,R.drawable.ic_m_keyboard_arrow_down,"Back");
        back.setOnClickListener(v -> finish());header.addView(back,new LinearLayout.LayoutParams(dp(this,44),dp(this,44)));
        TextView title=text(this,"Appearance",22,R.color.text_primary);title.setPadding(dp(this,12),0,0,0);header.addView(title);
        root.addView(header,match(dp(this,52)));gap(root,16);
        ScrollView scroll=new ScrollView(this);LinearLayout body=column(this);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout preview=card(this);preview.addView(text(this,"SUBTLE GLASS",11,R.color.text_tertiary));gap(preview,12);
        preview.addView(text(this,"Your wallpaper, behind the controls",20,R.color.text_primary));gap(preview,10);
        preview.addView(text(this,"Soft graphite panels and clear, familiar icons.",14,R.color.text_secondary));
        body.addView(preview,match(dp(this,140)));gap(body,16);
        LinearLayout options=card(this);options.addView(text(this,"WALLPAPER",11,R.color.text_tertiary));gap(options,12);
        choose=action(this,"Choose image…",0,true);choose.setId(R.id.appearance_choose);
        choose.setOnClickListener(v -> {
            Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            // FYT's file manager claims OPEN_DOCUMENT but opens Gallery without a result.
            // Prefer Android's permission-free photo picker when this firmware provides it.
            if(android.os.Build.VERSION.SDK_INT>=33) {
                Intent photos=new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*");
                if(photos.resolveActivity(getPackageManager())!=null)picker=photos;
            }
            try {startActivityForResult(picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),PICK);}
            catch(ActivityNotFoundException unavailable) {status.setText("No image picker is installed");}
        });options.addView(choose,match(dp(this,50)));gap(options,8);
        reset=action(this,"Use default wallpaper",0,false);reset.setId(R.id.appearance_reset);
        reset.setOnClickListener(v -> {AppearanceStore.clear(this);refreshStatus();});options.addView(reset,match(dp(this,48)));gap(options,12);
        status=text(this,"",13,R.color.text_secondary);status.setId(R.id.appearance_status);options.addView(status,match(-2));gap(options,10);
        options.addView(text(this,"Choose a photo saved on the head unit. For USB photos, copy them to Pictures first. Images fill the screen from the center.",12,R.color.text_tertiary));
        body.addView(options,match(-2));gap(body,16);
        LinearLayout levels=card(this);
        slider(levels,"Wallpaper dimming", "dim",20,80,AppearanceStore.dim(this),R.id.appearance_dim);
        gap(levels,14);slider(levels,"Wallpaper softness","softness",0,20,AppearanceStore.softness(this),R.id.appearance_softness);
        body.addView(levels,match(-2));refreshStatus();
    }
    private void slider(LinearLayout parent,String title,String key,int low,int high,int initial,int id) {
        TextView label=text(this,title+" · "+initial+(key.equals("dim")?"%":""),14,R.color.text_primary);parent.addView(label);
        SeekBar bar=new SeekBar(this);bar.setId(id);bar.setContentDescription(title);bar.setMax(high-low);bar.setProgress(initial-low);
        parent.addView(bar,match(dp(this,48)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar v,int progress,boolean user) {
                int value=progress+low;label.setText(title+" · "+value+(key.equals("dim")?"%":""));
                if(user)AppearanceStore.level(AppearanceActivity.this,key,value);
            }
            public void onStartTrackingTouch(SeekBar v) {}
            public void onStopTrackingTouch(SeekBar v) {}
        });
    }
    private void refreshStatus() {status.setText(AppearanceStore.image(this).isFile()?"Custom wallpaper saved":"Default wallpaper");}
    @Override protected void onResume() {super.onResume();MaintenanceUi.immersive(this);}
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=PICK || result!=RESULT_OK || data==null || data.getData()==null || busy)return;
        busy=true;choose.setEnabled(false);reset.setEnabled(false);status.setText("Saving wallpaper…");
        android.net.Uri image=data.getData();Context app=getApplicationContext();int ticket=AppearanceStore.beginImport();
        io.execute(() -> {
            String failure=null;
            try {AppearanceStore.importImage(app,image,ticket);}
            catch(java.io.IOException|RuntimeException|OutOfMemoryError problem) {failure="Could not load this image. Choose a standard photo smaller than 32 MB.";}
            final String message=failure;
            runOnUiThread(() -> {
                if(isDestroyed() || isFinishing())return;
                busy=false;choose.setEnabled(true);reset.setEnabled(true);
                if(message==null)refreshStatus();else status.setText(message);
            });
        });
    }
    @Override protected void onDestroy() {io.shutdown();super.onDestroy();}
}
