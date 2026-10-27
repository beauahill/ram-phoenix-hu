package com.ramphoenix.launcher;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Parked, screen-on test of keyboard-visible Save/Cancel, validation and a real commit. */
final class MaintenanceProbe {
    private final Instrumentation runner;
    MaintenanceProbe(Instrumentation runner){this.runner=runner;}
    private void ui(Runnable action){java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();runner.runOnMainSync(()->{try{action.run();}catch(Throwable e){failure.set(e);}});if(failure.get()!=null)throw new AssertionError(failure.get());}
    private String shell(String command)throws Exception{
        try(ParcelFileDescriptor fd=runner.getUiAutomation().executeShellCommand(command);FileInputStream in=new FileInputStream(fd.getFileDescriptor())){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}
    }
    void run(){
        Bundle result=new Bundle();MaintenanceEditorActivity activity=null;String created=null;int code=0;
        String label="QA form "+UUID.randomUUID();
        try{
            if(!shell("dumpsys display").contains("mState=ON"))throw new IllegalStateException("Parked ACC/Run with display on is required");
            if(runner.getTargetContext().getSharedPreferences("maintenance_drafts",0).contains("edit:new:new"))throw new IllegalStateException("A user draft exists; finish or discard it before this QA run");
            activity=(MaintenanceEditorActivity)runner.startActivitySync(new Intent(runner.getTargetContext(),MaintenanceEditorActivity.class).putExtra("mode","edit").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MaintenanceEditorActivity editor=activity;
            ui(()->{EditText input=editor.findViewById(R.id.maint_field_name);input.requestFocus();((InputMethodManager)editor.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);});
            SystemClock.sleep(1500);runner.waitForIdleSync();
            ui(()->{
                WindowInsets insets=editor.getWindow().getDecorView().getRootWindowInsets();int ime=insets.getInsets(WindowInsets.Type.ime()).bottom;
                if(ime<=0)throw new AssertionError("Keyboard did not open; not accepting a hidden-keyboard pass");
                int keyboardTop=editor.getWindowManager().getCurrentWindowMetrics().getBounds().bottom-ime;
                for(int id:new int[]{R.id.maint_save,R.id.maint_cancel}){
                    View button=editor.findViewById(id);Rect visible=new Rect();int[] xy=new int[2];button.getLocationOnScreen(xy);
                    if(!button.isShown() || !button.getGlobalVisibleRect(visible) || visible.height()!=button.getHeight() || xy[1]+button.getHeight()>keyboardTop)
                        throw new AssertionError("Save/Cancel is clipped or beneath the keyboard");
                }
                editor.findViewById(R.id.maint_save).performClick();
                if(editor.isFinishing() || ((EditText)editor.findViewById(R.id.maint_field_name)).getError()==null)
                    throw new AssertionError("Invalid form dismissed or failed to identify the empty name");
                ((EditText)editor.findViewById(R.id.maint_field_name)).setText(label);
                ((EditText)editor.findViewById(R.id.maint_field_interval)).setText("5000");
                ((EditText)editor.findViewById(R.id.maint_field_months)).setText("6");
                ((EditText)editor.findViewById(R.id.maint_field_notes)).setText("Temporary parked UI test");
                ((EditText)editor.findViewById(R.id.maint_field_notes)).onEditorAction(EditorInfo.IME_ACTION_DONE);
            });
            SystemClock.sleep(500);
            MaintenanceStore store=new MaintenanceStore(runner.getTargetContext());
            for(MaintenanceBook.Item item:store.items())if(item.name.equals(label))created=item.id;
            if(created==null || store.byId(created).intervalMiles!=5000 || store.byId(created).intervalMonths!=6 || !activity.isFinishing())throw new AssertionError("IME Done did not save the entered values");
            result.putString("result","Passed: visible Save/Cancel with IME, retained invalid form, Done committed correct data");code=-1;
        }catch(Throwable e){result.putString("failure",e.toString());}
        finally{
            try{
            MaintenanceStore cleanup=new MaintenanceStore(runner.getTargetContext());
            for(MaintenanceBook.Item item:cleanup.items())if(item.name.equals(label)){cleanup.removeItem(item.id);break;}
            if(activity!=null && !activity.isFinishing()){MaintenanceEditorActivity editor=activity;ui(()->{try{java.lang.reflect.Method discard=MaintenanceEditorActivity.class.getDeclaredMethod("discard");discard.setAccessible(true);discard.invoke(editor);}catch(Exception e){throw new RuntimeException(e);}});}
            }catch(Throwable e){code=0;result.putString("cleanupFailure",e.toString());}
        }
        runner.finish(code,result);
    }
}
