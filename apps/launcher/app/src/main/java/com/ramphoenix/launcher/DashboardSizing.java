package com.ramphoenix.launcher;

import android.widget.LinearLayout;

final class DashboardSizing {
    private DashboardSizing(){}
    static LinearLayout.LayoutParams card(String key,boolean idleMedia,float density,float fontScale){
        if(CardsConfig.MEDIA.equals(key))return new LinearLayout.LayoutParams(-1,
                Math.round((idleMedia?76:144+Math.max(0,fontScale-1)*48)*density),idleMedia?0:.3f);
        float weight=CardsConfig.weight(key);
        return weight>0?new LinearLayout.LayoutParams(-1,0,weight):new LinearLayout.LayoutParams(-1,-2);
    }
}
