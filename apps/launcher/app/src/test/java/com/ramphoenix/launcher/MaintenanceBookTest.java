package com.ramphoenix.launcher;

import org.junit.Test;
import static org.junit.Assert.*;
import java.time.LocalDate;

public class MaintenanceBookTest {
    private static long day(String s){return MaintenanceBook.dateValue(LocalDate.parse(s));}
    private MaintenanceBook book(){MaintenanceBook b=new MaintenanceBook();b.items.add(new MaintenanceBook.Item("oil","Engine oil & filter",15000,12));return b;}
    @Test public void monthsFollowTheCalendarIncludingLeapYears(){
        MaintenanceBook.Item i=new MaintenanceBook.Item("oil","Oil",0,1);i.lastDate=day("2024-01-31");
        assertEquals(Integer.valueOf(0),i.daysLeft(day("2024-02-29")));i.lastDate=day("2025-01-31");assertEquals(Integer.valueOf(0),i.daysLeft(day("2025-02-28")));
    }
    @Test public void exactlyDueIsNotMerelyDueSoon(){
        MaintenanceBook.Item i=new MaintenanceBook.Item("oil","Oil",1000,0);i.lastMiles=70000;
        assertEquals(MaintenanceBook.DUE,i.status(71000,day("2026-10-26")));assertEquals(MaintenanceBook.OVERDUE,i.status(71001,day("2026-10-26")));
    }
    @Test public void missingMileageDoesNotClaimEverythingIsCurrent(){
        MaintenanceBook.Item i=new MaintenanceBook.Item("oil","Oil",15000,12);i.lastMiles=60000;i.lastDate=day("2026-10-01");
        assertEquals(MaintenanceBook.UNKNOWN,i.status(-1,day("2026-10-26")));assertNull(i.milesLeft(59000));
    }
    @Test public void zeroMilesIsAValidServiceBaseline(){MaintenanceBook.Item i=new MaintenanceBook.Item("oil","Oil",1000,0);i.lastMiles=0;assertEquals(Integer.valueOf(500),i.milesLeft(500));}
    @Test public void backdatedEntryDoesNotResetTheCurrentService(){
        MaintenanceBook b=book();b.putRecord(new MaintenanceBook.Record("new","oil","Oil",70000,day("2026-10-01"),"New"),day("2026-10-26"));
        b.putRecord(new MaintenanceBook.Record("old","oil","Oil",55000,day("2025-10-01"),"Old"),day("2026-10-26"));
        assertEquals(70000,b.item("oil").lastMiles);assertEquals(2,b.history.size());
    }
    @Test public void editingAndDeletingRecordsRecomputesTheReminder(){
        MaintenanceBook b=book();b.putRecord(new MaintenanceBook.Record("a","oil","Oil",55000,day("2025-10-01"),""),day("2026-10-26"));
        b.putRecord(new MaintenanceBook.Record("b","oil","Oil",70000,day("2026-10-01"),""),day("2026-10-26"));
        b.putRecord(new MaintenanceBook.Record("b","oil","Oil",70500,day("2026-10-01"),"Corrected"),day("2026-10-26"));
        assertEquals(2,b.history.size());assertEquals(70500,b.item("oil").lastMiles);b.removeRecord("b");assertEquals(55000,b.item("oil").lastMiles);
    }
    @Test public void reminderDeletionKeepsTheHistory(){MaintenanceBook b=book();b.putRecord(new MaintenanceBook.Record("a","oil","Oil",70000,day("2026-10-01"),"Part number"),day("2026-10-26"));b.removeItem("oil");assertEquals(1,b.history.size());assertNull(b.item("oil"));}
    @Test public void impossibleChronologyStaysUnchanged(){
        MaintenanceBook b=book();b.putRecord(new MaintenanceBook.Record("a","oil","Oil",70000,day("2026-10-01"),""),day("2026-10-26"));
        assertThrows(IllegalArgumentException.class,()->b.putRecord(new MaintenanceBook.Record("bad","oil","Oil",60000,day("2026-10-10"),""),day("2026-10-26")));assertEquals(1,b.history.size());
        assertThrows(IllegalArgumentException.class,()->b.putRecord(new MaintenanceBook.Record("future","oil","Oil",80000,day("2027-01-01"),""),day("2026-10-26")));
    }
    @Test public void legacyMigrationPreservesValuesAndDoesNotInventDates() throws Exception {
        String raw="{\"odometer\":71000,\"items\":[{\"id\":\"oil\",\"name\":\"Oil\",\"intervalMiles\":15000,\"intervalMonths\":12,\"lastMiles\":60000,\"lastDate\":0,\"notes\":\"filter #123\"}]}";
        MaintenanceBook b=MaintenanceBook.parse(raw);assertEquals(71000,b.legacyOdometer);assertEquals(0,b.item("oil").lastDate);assertEquals(1,b.history.size());assertEquals("filter #123",b.history.get(0).notes);
        MaintenanceBook again=MaintenanceBook.parse(b.json(71000).toString());assertEquals(1,again.history.size());assertEquals(60000,again.item("oil").lastMiles);assertEquals(-1,again.legacyOdometer);
    }
    @Test public void invalidDataIsNotSilentlyReplacedWithDefaults(){
        assertThrows(Exception.class,()->MaintenanceBook.parse("{broken"));assertThrows(Exception.class,()->MaintenanceBook.parse("{\"items\":[{}]}"));
        assertThrows(Exception.class,()->MaintenanceBook.parse("{\"version\":99,\"items\":[]}"));
    }
    @Test public void savedCalendarDatesDoNotMoveWhenTheTruckChangesTimeZone() throws Exception {
        java.util.TimeZone old=java.util.TimeZone.getDefault();
        try{
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));MaintenanceBook b=book();
            b.putRecord(new MaintenanceBook.Record("a","oil","Oil",70000,day("2026-10-01"),""),day("2026-10-26"));String saved=b.json(71000).toString();
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"));MaintenanceBook loaded=MaintenanceBook.parse(saved);
            assertEquals(LocalDate.parse("2026-10-01"),loaded.item("oil").serviceDay());assertEquals(LocalDate.parse("2026-10-01"),loaded.history.get(0).serviceDay());
        }finally{java.util.TimeZone.setDefault(old);}
    }
    @Test public void inputValidationKeepsBlankSeparateFromZero(){
        assertEquals(-1,MaintenanceInput.number("","Miles",-1,3_000_000));assertEquals(0,MaintenanceInput.number("0","Miles",-1,3_000_000));
        assertEquals(71000,MaintenanceInput.number("71,000","Miles",-1,3_000_000));
        for(String bad:new String[]{"-1","71.2","71,00","2147483648","abc"})assertThrows(IllegalArgumentException.class,()->MaintenanceInput.number(bad,"Miles",-1,3_000_000));
        assertThrows(IllegalArgumentException.class,()->MaintenanceInput.name("  "));
    }
}
