package com.ramphoenix.launcher;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Read-only snapshot of the actual factory climate/profile callbacks. Sends no commands. */
final class ClimateProbe {
    private final Instrumentation runner;
    ClimateProbe(Instrumentation runner) {this.runner=runner;}
    void run() {
        Bundle result=new Bundle();int status=0;
        SyuIpc ipc=new SyuIpc(runner.getTargetContext());
        ConcurrentHashMap<String,String> values=new ConcurrentHashMap<>();
        CountDownLatch connected=new CountDownLatch(1);
        ipc.setConnectionListener(ok -> {if(ok)connected.countDown();});
        for(int code:new int[]{1000,10,11,13,21,27,28,29,30,31,32,37,66})
            ipc.subscribe(SyuIpc.MODULE_CANBUS,code,(c,i,f,s) -> values.put("CAN."+c,Arrays.toString(i)));
        ipc.subscribe(SyuIpc.MODULE_MAIN,50,(c,i,f,s) -> values.put("MAIN."+c,Arrays.toString(i)));
        try {
            runner.runOnMainSync(ipc::connect);
            if(!connected.await(6,TimeUnit.SECONDS))throw new IllegalStateException("Factory IPC unavailable");
            SystemClock.sleep(1500);
            for(java.util.Map.Entry<String,String> entry:values.entrySet())result.putString(entry.getKey(),entry.getValue());
            result.putString("result","Factory callback snapshot; no commands sent");status=-1;
        }catch(Throwable failure) {result.putString("failure",failure.toString());}
        finally {runner.runOnMainSync(ipc::disconnect);}
        runner.finish(status,result);
    }
}
