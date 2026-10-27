package com.ramphoenix.launcher;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Calendar-correct reminders and service history, independent of Android views. */
final class MaintenanceBook {
    static final int UNKNOWN=-1,CURRENT=0,SOON=1,DUE=2,OVERDUE=3;
    static final class Item {
        String id,name,notes="",lastDay="";
        int intervalMiles,intervalMonths,lastMiles=-1;
        long lastDate;
        Item(String id,String name,int miles,int months) { this.id=id;this.name=name;intervalMiles=miles;intervalMonths=months; }
        Integer milesLeft(int odo) {
            return intervalMiles<=0 || lastMiles<0 || odo<lastMiles?null:lastMiles+intervalMiles-odo;
        }
        Integer daysLeft(long now) {
            if(intervalMonths<=0 || lastDate<=0) return null;
            LocalDate due=serviceDay().plusMonths(intervalMonths);
            return (int)ChronoUnit.DAYS.between(localDate(now),due);
        }
        LocalDate serviceDay(){return lastDay.isEmpty()?localDate(lastDate):LocalDate.parse(lastDay);}
        boolean incomplete(int odo) {
            return (intervalMiles>0 && milesLeft(odo)==null) || (intervalMonths>0 && daysLeft(System.currentTimeMillis())==null);
        }
        int status(int odo,long now) {
            Integer miles=milesLeft(odo),days=daysLeft(now);
            if((miles!=null && miles<0)||(days!=null && days<0)) return OVERDUE;
            if((miles!=null && miles==0)||(days!=null && days==0)) return DUE;
            if((miles!=null && miles<=Math.max(500,intervalMiles/10))||(days!=null && days<=30)) return SOON;
            if((miles==null && days==null)||(intervalMiles>0 && miles==null)||(intervalMonths>0 && days==null)) return UNKNOWN;
            return CURRENT;
        }
    }
    static final class Record {
        String id,itemId,name,notes="",day="";
        int miles=-1;
        long date;
        Record(String id,String itemId,String name,int miles,long date,String notes) {
            this.id=id;this.itemId=itemId;this.name=name;this.miles=miles;this.date=date;this.notes=notes;
            if(date>0)day=localDate(date).toString();
        }
        LocalDate serviceDay(){return day.isEmpty()?localDate(date):LocalDate.parse(day);}
    }
    final List<Item> items=new ArrayList<>();
    final List<Record> history=new ArrayList<>();
    int legacyOdometer=-1;
    static LocalDate localDate(long value) { return Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate(); }
    static long dateValue(LocalDate value) { return value.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
    Item item(String id) { for(Item item:items) if(item.id.equals(id)) return item;return null; }
    Record record(String id) { for(Record record:history) if(record.id.equals(id)) return record;return null; }

    void putItem(Item next) {
        validate(next);Item previous=item(next.id);
        if(previous==null) { if(items.size()>=200) throw new IllegalArgumentException("Reminder limit reached");items.add(next); }
        else {
            // Editing a reminder does not change its service baseline or vehicle odometer.
            previous.name=next.name;previous.intervalMiles=next.intervalMiles;previous.intervalMonths=next.intervalMonths;previous.notes=next.notes;
        }
    }
    void putRecord(Record next,long now) {
        validate(next);
        if(next.date>0 && next.serviceDay().isAfter(localDate(now))) throw new IllegalArgumentException("Service date cannot be in the future");
        if(next.miles<0 && next.date==0) throw new IllegalArgumentException("Enter service mileage or a service date");
        if(item(next.itemId)==null && record(next.id)==null) throw new IllegalArgumentException("This reminder no longer exists");
        for(Record old:history) if(old.itemId.equals(next.itemId) && !old.id.equals(next.id)
                && old.date>0 && next.date>0 && old.miles>=0 && next.miles>=0) {
            int dates=old.serviceDay().compareTo(next.serviceDay());
            if((dates<0 && old.miles>next.miles)||(dates>0 && old.miles<next.miles))
                throw new IllegalArgumentException("Mileage conflicts with another service date; check the date and mileage");
        }
        Record old=record(next.id);
        if(old!=null) history.remove(old);
        else if(history.size()>=5000) throw new IllegalArgumentException("Service history limit reached; export a backup");
        history.add(next);recompute(next.itemId);
    }
    void removeRecord(String id) { Record old=record(id);if(old!=null){history.remove(old);recompute(old.itemId);} }
    void removeItem(String id) { Item old=item(id);if(old!=null) items.remove(old); }
    private void recompute(String id) {
        Item item=item(id);if(item==null)return;
        Record latest=null;
        for(Record r:history) if(r.itemId.equals(id)) {
            if(latest==null || (r.date>0 && latest.date>0?r.serviceDay().isAfter(latest.serviceDay()) || (r.serviceDay().equals(latest.serviceDay()) && r.miles>latest.miles):
                    r.miles>=0 && latest.miles>=0?r.miles>latest.miles:r.date>latest.date)) latest=r;
        }
        item.lastMiles=latest==null?-1:latest.miles;item.lastDate=latest==null?0:latest.date;item.lastDay=latest==null?"":latest.day;
    }
    static void validate(Item item) {
        item.name=MaintenanceInput.name(item.name);
        if(!item.lastDay.isEmpty())LocalDate.parse(item.lastDay);
        if(item.id==null || item.id.isEmpty() || item.id.length()>100 || item.intervalMiles<0 || item.intervalMiles>MaintenanceInput.MAX_INTERVAL
                || item.intervalMonths<0 || item.intervalMonths>MaintenanceInput.MAX_MONTHS || item.lastMiles< -1
                || item.lastMiles>MaintenanceInput.MAX_MILES || item.lastDate<0 || item.notes==null || item.notes.length()>4000)
            throw new IllegalArgumentException("Invalid maintenance reminder");
    }
    static void validate(Record r) {
        if(!r.day.isEmpty())LocalDate.parse(r.day);
        if(r.id==null || r.id.isEmpty() || r.id.length()>100 || r.itemId==null || r.itemId.isEmpty() || r.itemId.length()>100
                || r.name==null || r.name.trim().isEmpty() || r.name.length()>100 || r.miles< -1
                || r.miles>MaintenanceInput.MAX_MILES || r.date<0 || r.notes==null || r.notes.length()>4000)
            throw new IllegalArgumentException("Invalid service record");
    }

    static MaintenanceBook parse(String raw) throws Exception {
        JSONObject root=new JSONObject(raw);
        int version=root.optInt("version",1);
        if(version<1 || version>2) throw new IllegalArgumentException("Unsupported maintenance data version");
        MaintenanceBook book=new MaintenanceBook();book.legacyOdometer=version==1?root.optInt("odometer",-1):-1;
        JSONArray entries=root.getJSONArray("items");
        if(entries.length()>200) throw new IllegalArgumentException("Too many reminders");
        Set<String> ids=new HashSet<>();
        for(int i=0;i<entries.length();i++) {
            JSONObject j=entries.getJSONObject(i);
            Item item=new Item(j.getString("id"),j.getString("name"),j.getInt("intervalMiles"),j.getInt("intervalMonths"));
            item.notes=j.optString("notes","");item.lastMiles=j.optInt("lastMiles",-1);item.lastDate=j.optLong("lastDate",0);
            item.lastDay=j.optString("lastDay",item.lastDate>0?localDate(item.lastDate).toString():"");
            validate(item);if(!ids.add(item.id)) throw new IllegalArgumentException("Duplicate reminder");book.items.add(item);
        }
        JSONArray records=root.optJSONArray("history");
        if(records!=null && records.length()>5000) throw new IllegalArgumentException("Too many service records");
        ids.clear();
        for(int i=0;records!=null && i<records.length();i++) {
            JSONObject j=records.getJSONObject(i);
            Record r=new Record(j.getString("id"),j.getString("itemId"),j.getString("name"),j.optInt("miles",-1),j.optLong("date",0),j.optString("notes",""));
            r.day=j.optString("day",r.day);
            validate(r);if(!ids.add(r.id)) throw new IllegalArgumentException("Duplicate service record");book.history.add(r);
        }
        if(version==1) for(Item item:book.items) if(item.lastMiles>=0 || item.lastDate>0)
            book.history.add(new Record("legacy:"+item.id,item.id,item.name,item.lastMiles,item.lastDate,item.notes));
        // Loading/migration never writes preferences or fabricates a service date.
        return book;
    }
    JSONObject json(int odometer) throws Exception {
        JSONObject root=new JSONObject().put("version",2).put("odometer",odometer);
        JSONArray entries=new JSONArray(),records=new JSONArray();
        for(Item item:items) entries.put(new JSONObject().put("id",item.id).put("name",item.name).put("notes",item.notes)
                .put("intervalMiles",item.intervalMiles).put("intervalMonths",item.intervalMonths).put("lastMiles",item.lastMiles).put("lastDate",item.lastDate).put("lastDay",item.lastDay));
        for(Record r:history) records.put(new JSONObject().put("id",r.id).put("itemId",r.itemId).put("name",r.name)
                .put("miles",r.miles).put("date",r.date).put("day",r.day).put("notes",r.notes));
        return root.put("items",entries).put("history",records);
    }
    static MaintenanceBook starter() {
        MaintenanceBook book=new MaintenanceBook();
        book.items.add(new Item("oil","Engine oil & filter",15000,12));book.items.add(new Item("fuel","Fuel filters (both)",15000,12));
        book.items.add(new Item("tires","Tire rotation",10000,6));book.items.add(new Item("air","Air filter",30000,24));
        book.items.add(new Item("def","DEF top-up",5000,0));book.items.add(new Item("trans","Transmission fluid & filter",60000,0));
        book.items.add(new Item("diff","Axle & transfer case fluids",30000,0));book.items.add(new Item("coolant","Coolant",100000,60));
        book.items.add(new Item("brake","Brake fluid",0,36));return book;
    }
}
