package com.ramphoenix.launcher;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/** ImageView that stays square: its width follows an exact height (or vice versa). */
public class SquareImageView extends ImageView {
    public SquareImageView(Context c) { super(c); }
    public SquareImageView(Context c, AttributeSet a) { super(c, a); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int wMode = MeasureSpec.getMode(widthSpec), hMode = MeasureSpec.getMode(heightSpec);
        if (hMode == MeasureSpec.EXACTLY) {
            int s = MeasureSpec.getSize(heightSpec);
            super.onMeasure(MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY));
        } else if (wMode == MeasureSpec.EXACTLY) {
            int s = MeasureSpec.getSize(widthSpec);
            super.onMeasure(MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY));
        } else {
            super.onMeasure(widthSpec, heightSpec);
        }
    }
}
