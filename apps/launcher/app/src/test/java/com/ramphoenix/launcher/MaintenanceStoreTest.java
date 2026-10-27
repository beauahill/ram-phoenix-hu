package com.ramphoenix.launcher;

import android.content.SharedPreferences;
import org.junit.Test;
import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.util.*;

public class MaintenanceStoreTest {
    private static final class Memory {
        final Map<String,Object> data=new HashMap<>();int writes;boolean fail;
        final SharedPreferences preferences=(SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("edit"))return editor();if(m.getName().equals("contains"))return data.containsKey(a[0]);
            if(m.getName().startsWith("get") && a!=null && a.length==2)return data.getOrDefault(a[0],a[1]);
            if(m.getName().equals("getAll"))return new HashMap<>(data);return null;
        });
        private Object editor(){Map<String,Object> pending=new HashMap<>();return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SharedPreferences.Editor.class},(p,m,a)->{
            if(m.getName().startsWith("put")){pending.put((String)a[0],a[1]);return p;}
            if(m.getName().equals("commit")||m.getName().equals("apply")){writes++;if(!fail)data.putAll(pending);return m.getName().equals("commit")?!fail:null;}
            return p;
        });}
    }
    @Test public void readingTheDashboardDoesNotRewriteTheServiceBook() throws Exception {
        Memory data=new Memory(),mileage=new Memory();data.data.put("json",MaintenanceBook.starter().json(-1).toString());
        for(int i=0;i<5;i++)new MaintenanceStore(data.preferences,new OdometerRepository(mileage.preferences)).nextDueText();assertEquals(0,data.writes);
    }
    @Test public void corruptSavedDataIsPreservedAndEditsAreBlocked() throws Exception {
        Memory data=new Memory(),mileage=new Memory();data.data.put("json","broken saved data");MaintenanceStore store=new MaintenanceStore(data.preferences,new OdometerRepository(mileage.preferences));
        assertTrue(store.readError());assertThrows(IllegalStateException.class,()->store.putItem(new MaintenanceBook.Item("x","Oil",1000,0)));assertEquals("broken saved data",store.exportJson());assertEquals(0,data.writes);
    }
    @Test public void failedCommitIsReportedAndCannotDestroyThePriorData() throws Exception {
        Memory data=new Memory(),mileage=new Memory();String original=MaintenanceBook.starter().json(-1).toString();data.data.put("json",original);data.fail=true;
        MaintenanceStore store=new MaintenanceStore(data.preferences,new OdometerRepository(mileage.preferences));assertThrows(IllegalStateException.class,()->store.putItem(new MaintenanceBook.Item("new","Custom service",1000,0)));assertEquals(original,data.data.get("json"));
    }
    @Test public void telemetryCannotOverwriteServiceRecords() throws Exception {
        Memory data=new Memory(),mileage=new Memory();String original=MaintenanceBook.starter().json(-1).toString();data.data.put("json",original);
        OdometerRepository repo=new OdometerRepository(mileage.preferences);repo.observe(ObdMileage.decode(ObdMileage.STANDARD,"41A600116F6B",100));
        assertEquals(original,data.data.get("json"));assertEquals(0,data.writes);assertTrue(repo.miles()>0);
    }
    @Test public void anOlderEditorCannotOverwriteOtherSavedReminders() throws Exception {
        Memory data=new Memory(),mileage=new Memory();data.data.put("json",MaintenanceBook.starter().json(-1).toString());OdometerRepository repo=new OdometerRepository(mileage.preferences);
        MaintenanceStore older=new MaintenanceStore(data.preferences,repo),newer=new MaintenanceStore(data.preferences,repo);
        newer.putItem(new MaintenanceBook.Item("one","First custom reminder",1000,0));older.putItem(new MaintenanceBook.Item("two","Second custom reminder",2000,0));
        MaintenanceStore loaded=new MaintenanceStore(data.preferences,repo);assertNotNull(loaded.byId("one"));assertNotNull(loaded.byId("two"));
    }
    @Test public void dashboardDoesNotPromiseFutureServiceWithoutRequiredMileage() throws Exception {
        Memory data=new Memory(),mileage=new Memory();MaintenanceBook b=new MaintenanceBook();MaintenanceBook.Item oil=new MaintenanceBook.Item("oil","Oil",15000,12);
        oil.lastMiles=60000;oil.lastDate=System.currentTimeMillis();b.items.add(oil);data.data.put("json",b.json(-1).toString());
        assertNull(new MaintenanceStore(data.preferences,new OdometerRepository(mileage.preferences)).nextDueText());
    }
}
