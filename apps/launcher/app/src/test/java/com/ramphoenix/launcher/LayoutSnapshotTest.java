package com.ramphoenix.launcher;

import android.app.Activity;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import com.ramphoenix.appearance.GlassDrawable;
import com.ramphoenix.appearance.WallpaperScene;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.*;
import java.lang.reflect.*;
import static org.junit.Assert.*;

/** Real XML/native graphics, synthetic data, no connections or vehicle commands.
 * Bounds checks gate CI; PNGs let a reviewer inspect the same state matrix. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,qualifiers="w540dp-h960dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LayoutSnapshotTest {
    @Test public void dashboardStatesKeepMapAndSeatControlsReachable() throws Exception {
        for(boolean night:new boolean[]{false,true}) {
            preview(night,true,true,false,false);
            preview(night,false,true,true,false);
            preview(night,true,false,false,false);
            preview(night,false,true,false,true);
        }
    }
    private void preview(boolean night,boolean idle,boolean dockOpen,boolean climateOpen,boolean split)throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).create().get();activity.setTheme(R.style.Theme_RamLauncher);
        android.content.res.Configuration config=new android.content.res.Configuration(activity.getResources().getConfiguration());
        config.fontScale=1.25f;activity.getResources().updateConfiguration(config,activity.getResources().getDisplayMetrics());
        View root=activity.getLayoutInflater().inflate(R.layout.activity_launcher,null,false);root.setBackgroundColor(Color.TRANSPARENT);
        ((TextView)root.findViewById(R.id.clock_time)).setText("12:34 pm");
        ((TextView)root.findViewById(R.id.clock_date)).setText("Tue, Oct 27");
        ((TextView)root.findViewById(R.id.status_outside)).setText("61°");
        LinearLayout dock=root.findViewById(R.id.dock);Dock.build(activity,dock);dock.setVisibility(dockOpen?View.VISIBLE:View.GONE);
        for(int i=0;i<dock.getChildCount();i++)dock.getChildAt(i).setAlpha(1);
        LinearLayout full=root.findViewById(R.id.full_container);View splitRoot=root.findViewById(R.id.split_container);
        View map=activity.getLayoutInflater().inflate(R.layout.widget_map,full,false);
        ((FrameLayout)map.findViewById(R.id.map_container)).addView(new PreviewMap(activity));
        MapToolsLayout tools=new MapToolsLayout(map);tools.split(split);
        View media=activity.getLayoutInflater().inflate(R.layout.widget_media,full,false);
        MediaWidget player=new MediaWidget(activity,media);player.setLarge(split);
        Method compact=MediaWidget.class.getDeclaredMethod("idle",boolean.class);compact.setAccessible(true);compact.invoke(player,idle);
        ((TextView)media.findViewById(R.id.media_title)).setText("Night Drive");
        ((TextView)media.findViewById(R.id.media_artist)).setText("Phoenix preview");
        ((TextView)media.findViewById(R.id.media_src_name)).setText("Music");
        Bitmap art=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888);Canvas album=new Canvas(art);Paint p=new Paint();
        p.setShader(new LinearGradient(0,0,300,300,0xff7b8f91,0xff243444,Shader.TileMode.CLAMP));album.drawPaint(p);
        Method setArt=MediaWidget.class.getDeclaredMethod("setArt",Bitmap.class,int.class);setArt.setAccessible(true);setArt.invoke(player,art,R.drawable.ic_music);
        View drive=activity.getLayoutInflater().inflate(R.layout.widget_drive,full,false);
        VehicleCardView readings=drive.findViewById(R.id.vehicle_card);readings.setData(fixture());readings.setServiceText("OIL 6,270 MI");
        Field pressure=VehicleCardView.class.getDeclaredField("airPressure");pressure.setAccessible(true);pressure.set(readings,"6");
        View climate=activity.getLayoutInflater().inflate(R.layout.widget_climate,full,false);
        new ClimateWidget(activity,climate);climate.setAlpha(1);
        climate.findViewById(R.id.climate_expanded).setVisibility(climateOpen?View.VISIBLE:View.GONE);
        ((TextView)climate.findViewById(R.id.climate_status)).setText("PREVIEW");
        ((TextView)climate.findViewById(R.id.cl_temp_l)).setText("68°");((TextView)climate.findViewById(R.id.cl_temp_r)).setText("70°");
        ((ClimateSeatButton)climate.findViewById(R.id.cl_heat_l)).setClimateLevel(3,true);
        ((ClimateSeatButton)climate.findViewById(R.id.cl_vent_r)).setClimateLevel(2,false);
        if(split){
            full.setVisibility(View.GONE);splitRoot.setVisibility(View.VISIBLE);
            ((FrameLayout)root.findViewById(R.id.pane_a)).addView(map,new FrameLayout.LayoutParams(-1,-1));
            ((FrameLayout)root.findViewById(R.id.pane_b)).addView(media,new FrameLayout.LayoutParams(-1,-1));
        }else{
            View[] cards={map,media,drive,climate};String[] keys={CardsConfig.MAP,CardsConfig.MEDIA,CardsConfig.VEHICLE,CardsConfig.CLIMATE};
            for(int i=0;i<cards.length;i++){
                LinearLayout.LayoutParams lp=DashboardSizing.card(keys[i],player.isIdle(),2,config.fontScale);lp.topMargin=i==0?0:24;full.addView(cards[i],lp);
            }
        }
        // Unattached snapshots have no ViewRoot to perform layout-during-layout's
        // second traversal. Force each pass to honor changed child parameters.
        for(int i=0;i<4;i++){force(root);root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));root.layout(0,0,1080,1920);tools.fit();}
        for(int id:new int[]{R.id.btn_zoom_in,R.id.btn_zoom_out,R.id.btn_layers,R.id.btn_3d,R.id.btn_traffic}){
            View button=root.findViewById(id);assertTrue("Map control clipped: "+activity.getResources().getResourceEntryName(id)
                    +" map="+rect(map)+" button="+rect(button)+" idle="+idle+" dock="+dockOpen+" expanded="+climateOpen+" split="+split,rect(map).contains(rect(button)));
            assertTrue(button.getWidth()>=88);assertTrue(button.getHeight()>=88);
        }
        if(!split){
            assertEquals(idle?152:0,idle?media.getHeight():0);
            for(int id:new int[]{R.id.cl_heat_l,R.id.cl_vent_l,R.id.cl_heat_r,R.id.cl_vent_r})assertTrue("Seat control clipped",rect(climate).contains(rect(root.findViewById(id))));
            assertTrue("Climate exceeds display",rect(climate).bottom<=1920);
            if(idle)assertEquals(View.VISIBLE,media.findViewById(R.id.media_idle).getVisibility());
        }
        for(int id:idle?new int[]{R.id.media_idle_play}:new int[]{R.id.media_prev,R.id.media_play,R.id.media_next})
            assertTrue("Media control clipped: "+activity.getResources().getResourceEntryName(id),rect(media).contains(rect(root.findViewById(id))));
        GlassDrawable.night(night);
        Bitmap screen=Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(screen);
        WallpaperScene backdrop=new WallpaperScene(activity);backdrop.layout(0,0,1080,1920);
        Field dim=WallpaperScene.class.getDeclaredField("dim");dim.setAccessible(true);dim.set(backdrop,night?55:32);
        backdrop.draw(canvas);root.draw(canvas);
        File dir=new File(System.getProperty("phoenix.preview.dir","build/reports/layout-previews"));assertTrue(dir.isDirectory()||dir.mkdirs());
        String name=(night?"night":"day")+"-"+(split?"split":idle?"idle":"playing")+"-dock"+dockOpen+"-climate"+climateOpen+".png";
        try(OutputStream out=new FileOutputStream(new File(dir,name))){assertTrue(screen.compress(Bitmap.CompressFormat.PNG,100,out));}
        screen.recycle();activity.finish();
    }
    private static Rect rect(View view){
        int x=0,y=0;for(View v=view;v!=null;v=v.getParent() instanceof View?(View)v.getParent():null){x+=v.getLeft();y+=v.getTop();}
        return new Rect(x,y,x+view.getWidth(),y+view.getHeight());
    }
    private static void force(View view){view.forceLayout();if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)force(((ViewGroup)view).getChildAt(i));}
    private VehicleData fixture(){return new VehicleData(){
        public GaugeReading reading(Gauge gauge){return GaugeReading.choose(gauge==Gauge.COOLANT?95f:gauge==Gauge.TRANSMISSION?50f:gauge==Gauge.OIL_PRESSURE?35f:92f,null);}
        public Float tirePsi(int corner){return new float[]{61,63,72,75}[corner];}public Float fuelPercent(){return null;}
        public Float coolantC(){return 95f;}public Float oilTempC(){return 92f;}public Float transC(){return 50f;}
        public Float oilPsi(){return 35f;}public Float batteryVolts(){return 14.1f;}public Float outsideC(){return 16f;}
        public Float speedKph(){return 0f;}public Float rpm(){return 0.7f;}public int doorsOpen(){return 0;}public boolean available(){return true;}
    };}
    private static class PreviewMap extends View {
        PreviewMap(android.content.Context c){super(c);}
        protected void onDraw(Canvas c){
            c.drawColor(0xff17202a);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(0xff334454);p.setStrokeWidth(3);
            for(int i=-300;i<getWidth()+400;i+=180)c.drawLine(i,0,i+260,getHeight(),p);
            for(int i=80;i<getHeight();i+=150)c.drawLine(0,i,getWidth(),i-90,p);
            p.setColor(0xff708b9b);p.setStrokeWidth(10);c.drawLine(0,getHeight()*.8f,getWidth(),getHeight()*.3f,p);
            p.setColor(0xff5aa6fa);c.drawCircle(getWidth()*.45f,getHeight()*.48f,12,p);
            p.setColor(0xffA9BAC9);p.setTextSize(21);c.drawText("Map preview",28,getHeight()-24,p);
        }
    }
}
