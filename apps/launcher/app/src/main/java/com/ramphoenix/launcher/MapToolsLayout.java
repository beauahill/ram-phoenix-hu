package com.ramphoenix.launcher;

import android.view.*;
import android.widget.*;

/** One responsive layout policy shared by the dashboard and native layout checks. */
final class MapToolsLayout {
    private final View map;
    private boolean split;
    MapToolsLayout(View map){this.map=map;map.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)fit();});}
    void split(boolean value){split=value;fit();}
    void fit(){
        LinearLayout tools=map.findViewById(R.id.map_tools);float d=map.getResources().getDisplayMetrics().density;
        int preferredTop=Math.round((split?54:14)*d),edge=Math.round(14*d),button=Math.round(44*d),divider=Math.round(d);
        boolean horizontal=map.getHeight()>0&&map.getHeight()<preferredTop+5*button+2*divider+edge;
        int height=horizontal?button:5*button+2*divider;
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)tools.getLayoutParams();
        int top=map.getHeight()>0?Math.max(0,Math.min(preferredTop,map.getHeight()-height-edge)):preferredTop;
        int width=horizontal?ViewGroup.LayoutParams.WRAP_CONTENT:button;
        int orientation=horizontal?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL;
        if(tools.getOrientation()!=orientation)tools.setOrientation(orientation);
        if(lp.topMargin!=top||lp.width!=width){lp.topMargin=top;lp.width=width;tools.setLayoutParams(lp);}
        for(int i=0;i<tools.getChildCount();i++){
            View child=tools.getChildAt(i);boolean control=child instanceof TextView||child instanceof ImageView;
            int cw=control?button:horizontal?divider:Math.round(24*d),ch=control?button:horizontal?Math.round(24*d):divider;
            LinearLayout.LayoutParams item=(LinearLayout.LayoutParams)child.getLayoutParams();
            if(item.width!=cw||item.height!=ch||item.gravity!=Gravity.CENTER){item.width=cw;item.height=ch;item.gravity=Gravity.CENTER;child.setLayoutParams(item);}
        }
    }
}
