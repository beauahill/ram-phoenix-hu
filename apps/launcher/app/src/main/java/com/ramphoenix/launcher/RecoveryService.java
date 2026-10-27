package com.ramphoenix.launcher;

import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Log;

/** Small foreground watchdog: recover the signed camera monitor even while another
 * app owns the screen. Never opens an activity or restarts a vendor service. */
public final class RecoveryService extends Service {
    private CameraRecovery camera;
    private SyuIpc ignition;
    private boolean acc=true;
    private boolean stopped;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final BroadcastReceiver screen=new BroadcastReceiver(){
        public void onReceive(Context c,Intent intent){refresh();}
    };
    static void start(Context context){
        try{context.startForegroundService(new Intent(context,RecoveryService.class));}
        catch(RuntimeException failure){Log.w("PhoenixRecovery","Recovery service unavailable",failure);}
    }
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("phoenix_ready","Phoenix services",NotificationManager.IMPORTANCE_MIN));
        startForeground(4,new Notification.Builder(this,"phoenix_ready").setSmallIcon(R.drawable.ic_camera)
                .setContentTitle("Phoenix").setContentText("Vehicle interface ready").setOngoing(true).build());
        camera=new CameraRecovery(this);camera.ensureMonitor();
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screen,filter,Context.RECEIVER_NOT_EXPORTED);
        ignition=new SyuIpc(this);ignition.subscribe(SyuIpc.MODULE_MAIN,SyuIpc.U_ACC_ON,(code,ints,f,s)->{
            if(ints!=null&&ints.length>0){boolean on=ints[0]==1;main.post(()->{if(!stopped){acc=on;refresh();}});}
        });ignition.connect();refresh();
        Log.i("PhoenixRecovery","Background recovery monitor started");
    }
    private void refresh(){
        if(stopped||camera==null)return;
        PowerManager power=getSystemService(PowerManager.class);
        camera.visible(acc&&power!=null&&power.isInteractive());
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){refresh();return START_STICKY;}
    @Override public void onDestroy(){stopped=true;main.removeCallbacksAndMessages(null);camera.visible(false);ignition.disconnect();unregisterReceiver(screen);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
