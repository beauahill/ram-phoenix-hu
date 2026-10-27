package com.ramphoenix.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.*;

/** Durable local service records. Loading never re-saves or resets unreadable data. */
public final class MaintenanceStore {
    private static final Object SAVE_LOCK=new Object();
    private final SharedPreferences prefs;
    final OdometerRepository mileage;
    private MaintenanceBook book;
    private boolean readError;
    public MaintenanceStore(Context context) {
        this(context.getSharedPreferences("maintenance",Context.MODE_PRIVATE),new OdometerRepository(context));
    }
    MaintenanceStore(SharedPreferences prefs,OdometerRepository mileage) {
        this.prefs=prefs;this.mileage=mileage;
        String raw=prefs.getString("json",null);
        if(raw==null) book=MaintenanceBook.starter();
        else try {book=MaintenanceBook.parse(raw);mileage.migrateLegacy(book.legacyOdometer);}
        catch(Exception e) {book=new MaintenanceBook();readError=true;}
    }
    boolean readError() {return readError;}
    List<MaintenanceBook.Item> items() {return Collections.unmodifiableList(book.items);}
    List<MaintenanceBook.Record> history() {
        List<MaintenanceBook.Record> result=new ArrayList<>(book.history);
        result.sort(Comparator.comparing((MaintenanceBook.Record r)->r.date>0?r.serviceDay():java.time.LocalDate.MIN).reversed().thenComparing(Comparator.comparingInt((MaintenanceBook.Record r)->r.miles).reversed()));
        return result;
    }
    MaintenanceBook.Item byId(String id) {return book.item(id);}
    MaintenanceBook.Record recordById(String id) {return book.record(id);}
    public int odometer() {return mileage.miles();}
    private void writable() {if(readError)throw new IllegalStateException("Saved data could not be opened. Export it before making changes.");}
    private void save() {
        writable();
        try {if(!prefs.edit().putString("json",book.json(odometer()).toString()).commit()) throw new IllegalStateException("Save failed; please try again");}
        catch(IllegalStateException e) {throw e;}
        catch(Exception e) {throw new IllegalStateException("Maintenance data could not be saved",e);}
    }
    private void latestForWrite() {
        writable();String raw=prefs.getString("json",null);
        try {book=raw==null?MaintenanceBook.starter():MaintenanceBook.parse(raw);}
        catch(Exception e){readError=true;throw new IllegalStateException("Saved data changed and could not be opened; export a backup before editing",e);}
    }
    void putItem(MaintenanceBook.Item item) {synchronized(SAVE_LOCK){latestForWrite();book.putItem(item);save();}}
    void putRecord(MaintenanceBook.Record record) {synchronized(SAVE_LOCK){latestForWrite();book.putRecord(record,System.currentTimeMillis());save();}}
    void removeItem(String id) {synchronized(SAVE_LOCK){latestForWrite();book.removeItem(id);save();}}
    void removeRecord(String id) {synchronized(SAVE_LOCK){latestForWrite();book.removeRecord(id);save();}}
    String exportJson() throws Exception {
        if(readError)return prefs.getString("json","");
        return book.json(odometer()).put("odometerSource",mileage.json()).toString(2);
    }
    public String nextDueText() {
        if(readError)return null;
        int odo=odometer();long now=System.currentTimeMillis();MaintenanceBook.Item best=null;double bestScore=Double.MAX_VALUE;
        for(MaintenanceBook.Item item:book.items) {
            if(item.status(odo,now)==MaintenanceBook.UNKNOWN)continue;
            Integer m=item.milesLeft(odo),d=item.daysLeft(now);
            double score=Math.min(m==null?Double.MAX_VALUE:m/Math.max(1d,item.intervalMiles),d==null?Double.MAX_VALUE:d/Math.max(1d,item.intervalMonths*30.44));
            if(score<bestScore){best=item;bestScore=score;}
        }
        if(best==null)return null;
        Integer m=best.milesLeft(odo),d=best.daysLeft(now);String name=shortName(best.name);
        if(best.status(odo,now)>=MaintenanceBook.DUE)return name+(best.status(odo,now)==MaintenanceBook.OVERDUE?" OVERDUE":" DUE");
        if(m!=null && (d==null || m/Math.max(1d,best.intervalMiles)<=d/Math.max(1d,best.intervalMonths*30.44)))
            return String.format(Locale.US,"%s %,d MI",name,m);
        return d==null?null:name+" "+d+" D";
    }
    private static String shortName(String name) {
        String n=name.toUpperCase(Locale.US);
        if(n.startsWith("ENGINE OIL"))return "OIL";if(n.startsWith("FUEL FILTER"))return "FUEL FLT";
        if(n.startsWith("TIRE"))return "TIRES";if(n.startsWith("AIR FILTER"))return "AIR FLT";
        if(n.startsWith("TRANSMISSION"))return "TRANS";if(n.startsWith("TRANSFER"))return "T-CASE";
        return n.length()>9?n.substring(0,9):n;
    }
}
