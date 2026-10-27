package com.ramphoenix.launcher;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Parked-device regression: checks native tasks, not launcher-owned wrapper activities. */
public final class PaneWindowProbe extends Instrumentation {
    private String targetPackage, operation;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        targetPackage=args.getString("target","com.google.android.youtube");
        operation=args.getString("operation","verify"); start();
    }
    private String shell(String command) throws Exception {
        try(ParcelFileDescriptor descriptor=getUiAutomation().executeShellCommand(command);
            FileInputStream input=new FileInputStream(descriptor.getFileDescriptor())) {
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private String task(int id) throws Exception {
        String dump=shell("dumpsys activity activities");
        Pattern tasks=Pattern.compile("(?m)^  \\* Task\\{.*?#(\\d+) .*?(?=^  \\* Task\\{|^ResumedActivity|\\z)",Pattern.DOTALL);
        Matcher match=tasks.matcher(dump);
        while(match.find()) if(Integer.parseInt(match.group(1))==id) return match.group();
        return "";
    }
    private int visibleTarget() throws Exception {
        Matcher match=Pattern.compile("(?m)^  \\* Task\\{[^\\n]*#(\\d+) [^\\n]*A=\\d+:"
                +Pattern.quote(targetPackage)+" [^\\n]*visible=true[^\\n]*mode=freeform").matcher(shell("dumpsys activity activities"));
        if(!match.find()) throw new AssertionError("No visible freeform task for "+targetPackage);
        return Integer.parseInt(match.group(1));
    }
    private void report(String stage,int id) throws Exception {
        Bundle result=new Bundle();result.putString("stage",stage);result.putInt("task",id);result.putString("native",task(id));sendStatus(0,result);
    }
    @Override public void onStart() {
        if(operation.equals("climate-read")){new ClimateProbe(this).run();return;}
        if(operation.equals("appearance")){new AppearanceProbe(this).run();return;}
        if(operation.equals("maintenance")){new MaintenanceProbe(this).run();return;}
        if(operation.startsWith("device-")){new DeviceReadProbe(this).run(operation);return;}
        Bundle result=new Bundle();
        try {
            if(!shell("dumpsys display").contains("mState=ON")) throw new IllegalStateException("Display off; parked ACC/Run required");
            getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setClass(getTargetContext(),LauncherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(2000);
            WindowBridge bridge=WindowBridge.get(getTargetContext());
            if(operation.equals("setup")) {
                CountDownLatch ready=new CountDownLatch(1);bridge.setup(ready::countDown);
                if(!ready.await(100,TimeUnit.SECONDS) || !bridge.ready()) throw new AssertionError(bridge.status());
                result.putString("result","Setup complete");finish(-1,result);return;
            }
            if(!bridge.ready()) throw new IllegalStateException(bridge.status());
            ComponentName component=getTargetContext().getPackageManager().getLaunchIntentForPackage(targetPackage).getComponent();
            runOnMainSync(() -> PaneWindows.open(getTargetContext(),"pane_b",component,new Rect(32,1048,1048,1888)));
            SystemClock.sleep(2500);int first=visibleTarget();report("opened",first);
            for(int i=0;i<4;i++) shell("input swipe 310 510 690 620 350");
            SystemClock.sleep(600);
            if(visibleTarget()!=first) throw new AssertionError("Map interaction lost or replaced the app");
            report("after-map-gestures",first);
            runOnMainSync(() -> PaneWindows.resize(getTargetContext(),"pane_b",component,new Rect(32,800,1048,1888)));
            SystemClock.sleep(800);
            if(visibleTarget()!=first || !task(first).contains("mBounds=Rect(32, 800 - 1048, 1888)"))
                throw new AssertionError("Resize did not preserve the native app task");
            report("resized-in-place",first);
            runOnMainSync(() -> PaneWindows.reverse(getTargetContext(),true));SystemClock.sleep(700);
            if(!task(first).isEmpty()) throw new AssertionError("Reverse guard left the app above the camera");
            runOnMainSync(() -> PaneWindows.reverse(getTargetContext(),false));SystemClock.sleep(2000);int restored=visibleTarget();
            report("reverse-guard-released",restored);
            shell("input keyevent HOME");SystemClock.sleep(1000);
            report("after-home",restored);
            String afterHome=task(restored);
            if(afterHome.contains("visible=true") || afterHome.contains("ActivityRecord{"))
                throw new AssertionError("Home did not dismiss the owned app");
            result.putString("result","Passed: map gestures, in-place resize, reverse gate, Home dismissal");finish(-1,result);
        } catch(Throwable failure) {
            result.putString("failure",failure.toString());finish(0,result);
        } finally {
            runOnMainSync(() -> { PaneWindows.reverse(getTargetContext(),false);PaneWindows.closeAll(getTargetContext()); });
        }
    }
}
