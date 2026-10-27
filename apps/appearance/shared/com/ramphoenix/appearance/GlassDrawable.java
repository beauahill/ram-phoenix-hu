package com.ramphoenix.appearance;

import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import org.xmlpull.v1.XmlPullParser;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/** Shared surface tokens, updated in place so appearance never rebuilds app windows. */
public class GlassDrawable extends Drawable {
    private static boolean night=AppearanceProfile.night("auto",java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY));
    private static final ArrayList<WeakReference<GlassDrawable>> live=new ArrayList<>();
    private final int role;
    private float density=1;
    private int alpha=255;
    private ColorFilter filter;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box=new RectF();
    private boolean selected,pressed;
    public static final class Panel extends GlassDrawable {public Panel(){super(0);}}
    public static final class Control extends GlassDrawable {public Control(){super(1);}}
    public static final class Pill extends GlassDrawable {public Pill(){super(2);}}
    public static final class Circle extends GlassDrawable {public Circle(){super(3);}}
    public GlassDrawable(){this(0);}
    private GlassDrawable(int role){this.role=role;synchronized(live){live.add(new WeakReference<>(this));}}
    public static void night(boolean value) {
        if(night==value)return;night=value;
        synchronized(live){for(int i=live.size()-1;i>=0;i--) {
            GlassDrawable drawable=live.get(i).get();
            if(drawable==null)live.remove(i);else drawable.invalidateSelf();
        }}
    }
    @Override public void inflate(Resources res,XmlPullParser parser,AttributeSet attrs,Resources.Theme theme)
            throws java.io.IOException,org.xmlpull.v1.XmlPullParserException {
        super.inflate(res,parser,attrs,theme);density=res.getDisplayMetrics().density;
    }
    @Override public boolean isStateful(){return true;}
    @Override protected boolean onStateChange(int[] states) {
        boolean on=false,down=false;
        for(int state:states){on|=state==android.R.attr.state_selected||state==android.R.attr.state_activated;down|=state==android.R.attr.state_pressed||state==android.R.attr.state_focused;}
        if(on==selected&&down==pressed)return false;selected=on;pressed=down;invalidateSelf();return true;
    }
    @Override public void draw(Canvas canvas) {
        Rect bounds=getBounds();float inset=density*.35f;box.set(bounds);box.inset(inset,inset);
        float radius=role>=2?Math.min(box.width(),box.height())/2f:(role==0?20:12)*density;
        int top=night?(role==0?0xCE263442:0xDC2D3D4D):(role==0?0xF0394A5B:0xF4435668);
        int bottom=night?(role==0?0xD0101C28:0xDE1D2C3B):(role==0?0xF5223243:0xF52A3D50);
        if(role==2){top=night?0xE0243241:0xF13A4A5C;bottom=night?0xE0142230:0xF2263748;}
        paint.setStyle(Paint.Style.FILL);paint.setAlpha(alpha);paint.setColorFilter(filter);
        paint.setShader(new LinearGradient(0,box.top,0,box.bottom,top,bottom,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(box,radius,radius,paint);paint.setShader(null);
        if(pressed){paint.setColor(Color.WHITE);paint.setAlpha(36*alpha/255);canvas.drawRoundRect(box,radius,radius,paint);}
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(density*.65f);
        int border=selected?0xDDE8AD63:night?0x30D3E4F2:0x52DCEAF5;
        paint.setColor(border);paint.setAlpha(Color.alpha(border)*alpha/255);
        canvas.drawRoundRect(box,radius,radius,paint);paint.setStyle(Paint.Style.FILL);
    }
    @Override public void setAlpha(int value){alpha=value;invalidateSelf();}
    @Override public void setColorFilter(ColorFilter value){filter=value;invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    @Override public void getOutline(Outline outline){Rect b=getBounds();outline.setRoundRect(b,role>=2?Math.min(b.width(),b.height())/2f:(role==0?20:12)*density);outline.setAlpha(1f);}
    @Override public ConstantState getConstantState() {
        final int kind=role;final float scale=density;
        return new ConstantState(){public int getChangingConfigurations(){return 0;}
            public Drawable newDrawable(){GlassDrawable d=new GlassDrawable(kind);d.density=scale;return d;}
            public Drawable newDrawable(Resources res){GlassDrawable d=new GlassDrawable(kind);d.density=res.getDisplayMetrics().density;return d;}};
    }
}
