package com.ramphoenix.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.ImageView;

/** Familiar seat symbol with three reported-level bars; no HEAT/VENT text tile. */
public final class ClimateSeatButton extends ImageView {
    private final Paint indicator=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int level=-1;
    private int activeColor;

    public ClimateSeatButton(Context context,AttributeSet attributes) {
        super(context,attributes);
        activeColor=context.getColor(R.color.seat_heat);
    }

    void setClimateLevel(int reported,boolean heat) {
        level=reported<0?-1:Math.min(3,reported);
        activeColor=getContext().getColor(heat?R.color.seat_heat:R.color.seat_cool);
        setImageTintList(ColorStateList.valueOf(level>0?activeColor:getContext().getColor(R.color.text_secondary)));
        setSelected(level>0);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d=getResources().getDisplayMetrics().density;
        float bar=5*d,gap=3*d,left=(getWidth()-(3*bar+2*gap))/2f,y=getHeight()-9*d;
        if(level<0) {
            indicator.setColor(getContext().getColor(R.color.text_tertiary));
            canvas.drawRoundRect(getWidth()/2f-5*d,y,getWidth()/2f+5*d,y+2*d,d,d,indicator);
            return;
        }
        for(int i=0;i<3;i++) {
            indicator.setColor(i<level?activeColor:getContext().getColor(R.color.text_tertiary));
            canvas.drawRoundRect(left+i*(bar+gap),y,left+i*(bar+gap)+bar,y+3*d,d,d,indicator);
        }
    }
}
