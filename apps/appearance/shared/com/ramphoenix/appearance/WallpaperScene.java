package com.ramphoenix.appearance;

import android.app.Activity;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.*;
import android.widget.FrameLayout;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Opaque background only: effects never blur text, controls or camera video. */
public final class WallpaperScene extends View {
    public static final String AUTHORITY="com.ramphoenix.launcher.appearance";
    public static final String PERMISSION="com.ramphoenix.launcher.permission.APPEARANCE";
    public static final Uri ROOT=Uri.parse("content://"+AUTHORITY);
    public static final Uri SETTINGS=Uri.withAppendedPath(ROOT,"settings");
    public static final Uri IMAGE=Uri.withAppendedPath(ROOT,"wallpaper");
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static Bitmap cached;
    private static long cachedVersion=Long.MIN_VALUE;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final int[] position=new int[2];
    private Bitmap image;
    private int dim=38, softness=7, generation;
    private final Runnable reload=this::load;
    private final Runnable clock=new Runnable(){public void run(){load();postDelayed(this,60000);}};
    private final ContentObserver observer=new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {removeCallbacks(reload);postDelayed(reload,50);}
    };
    public WallpaperScene(Context c) {this(c,null);}
    public WallpaperScene(Context c,AttributeSet attrs) {super(c,attrs);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    public static void content(Activity activity,int layout) {
        content(activity,activity.getLayoutInflater().inflate(layout,null,false));
    }
    public static void content(Activity activity,View foreground) {
        foreground.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout root=new FrameLayout(activity);
        root.addView(new WallpaperScene(activity),new FrameLayout.LayoutParams(-1,-1));
        root.addView(foreground,new FrameLayout.LayoutParams(-1,-1));
        activity.setContentView(root);
    }
    @Override public boolean isOpaque() {return true;}
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {getContext().getContentResolver().registerContentObserver(ROOT,true,observer);} catch(RuntimeException ignored) {}
        load();postDelayed(clock,60000);
    }
    @Override protected void onDetachedFromWindow() {
        generation++;removeCallbacks(reload);removeCallbacks(clock);
        getContext().getContentResolver().unregisterContentObserver(observer);
        super.onDetachedFromWindow();
    }
    private void load() {
        final int request=++generation;final Context context=getContext().getApplicationContext();
        IO.execute(() -> {
            int darkness=38,blur=7;long version=0;boolean custom=false;
            boolean dark=AppearanceProfile.night("auto",java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY));
            try {
                android.content.pm.ProviderInfo provider=context.getPackageManager().resolveContentProvider(AUTHORITY,0);
                if(provider==null || !"com.ramphoenix.launcher".equals(provider.packageName)
                        || context.getPackageManager().checkSignatures(context.getPackageName(),provider.packageName)!=0)
                    throw new SecurityException("Appearance provider unavailable");
                try(Cursor cursor=context.getContentResolver().query(SETTINGS,null,null,null,null)) {
                    if(cursor!=null && cursor.moveToFirst()) {
                        darkness=cursor.getInt(cursor.getColumnIndexOrThrow("dim"));
                        blur=cursor.getInt(cursor.getColumnIndexOrThrow("softness"));
                        version=cursor.getLong(cursor.getColumnIndexOrThrow("version"));
                        custom=cursor.getInt(cursor.getColumnIndexOrThrow("custom"))==1;
                        int mode=cursor.getColumnIndex("night");if(mode>=0)dark=cursor.getInt(mode)==1;
                    }
                }
            } catch(RuntimeException ignored) { /* A missing companion never blocks the camera. */ }
            final int safeDim=Math.max(20,Math.min(80,darkness)),safeBlur=Math.max(0,Math.min(20,blur));
            final Bitmap picture=bitmap(context,custom,version);
            final boolean night=dark;
            post(() -> {
                if(request!=generation || !isAttachedToWindow())return;
                image=picture;dim=safeDim;softness=safeBlur;
                GlassDrawable.night(night);
                float radius=softness*getResources().getDisplayMetrics().density;
                setRenderEffect(radius==0?null:RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP));
                invalidate();
            });
        });
    }
    private static synchronized Bitmap bitmap(Context context,boolean custom,long version) {
        if(!custom) {cached=null;cachedVersion=Long.MIN_VALUE;return null;}
        if(cachedVersion==version)return cached;
        cached=null;cachedVersion=version;
        try {
            cached=ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(),IMAGE),(decoder,info,source) -> {
                int w=info.getSize().getWidth(),h=info.getSize().getHeight();
                if(w<=0 || h<=0 || (long)w*h>100_000_000L)throw new IllegalArgumentException("Image dimensions");
                double scale=Math.min(1,2048.0/Math.max(w,h));
                decoder.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } catch(java.io.IOException|RuntimeException|OutOfMemoryError failure) {
            android.util.Log.w("PhoenixAppearance","Wallpaper decode unavailable; using default");
        }
        return cached;
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Rect screen=getContext().getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        float w=screen.width(),h=screen.height();getLocationInWindow(position);
        canvas.save();canvas.translate(-position[0],-position[1]);
        canvas.drawColor(0xff0b1420);
        paint.setShader(new LinearGradient(0,0,w,h,new int[]{0xff283c50,0xff101b2b,0xff171b2b},null,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,paint);
        paint.setShader(new RadialGradient(w*.12f,h*.26f,w*.95f,new int[]{0x995c9caa,0x005c9caa},null,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,paint);
        paint.setShader(new RadialGradient(w*.95f,h*.86f,w,new int[]{0x88796354,0x00796354},null,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,paint);paint.setShader(null);paint.setColor(Color.WHITE);
        if(image!=null) {
            float scale=Math.max(w/image.getWidth(),h/image.getHeight());
            float bw=image.getWidth()*scale,bh=image.getHeight()*scale;
            canvas.drawBitmap(image,null,new RectF((w-bw)/2,(h-bh)/2,(w+bw)/2,(h+bh)/2),paint);
        }
        int alpha=Math.round(dim*2.55f),header=Math.max(alpha,160);
        paint.setShader(new LinearGradient(0,0,0,h,new int[]{Color.argb(header,4,10,17),
                Color.argb(alpha,4,10,17),Color.argb(Math.min(230,alpha+25),4,10,17)},new float[]{0,.3f,1},Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,paint);paint.setShader(null);canvas.restore();
    }
}
