package com.ramphoenix.launcher;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.ramphoenix.launcher.MaintenanceUi.*;

/** Vehicle mileage, actionable reminders and an editable service history. */
public class MaintenanceActivity extends Activity {
    private MaintenanceStore store;
    private ObdClient obd;
    private LinearLayout root,list;
    private TextView odo,source,observed,summary,remindersTab,historyTab;
    private final TextView[] totals=new TextView[3];
    private boolean history;
    private int lastOdo=-2;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable(){public void run(){updateMileage();ui.postDelayed(this,1000);}};
    static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("MMM d, yyyy",Locale.US);
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(base);Ui.applyDensity(this);}
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);store=new MaintenanceStore(this);obd=new ObdClient(this,this::updateMileage);
        history=state!=null && state.getBoolean("history");
        root=column(this);root.setPadding(dp(this,16),dp(this,16),dp(this,16),dp(this,12));com.ramphoenix.appearance.WallpaperScene.content(this,root);
        LinearLayout header=row(this);ImageView back=iconButton(this,R.drawable.ic_m_keyboard_arrow_down,"Back to dashboard");
        back.setId(R.id.maint_back);back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(dp(this,44),dp(this,44)));
        TextView title=text(this,"Maintenance",21,R.color.text_primary);title.setPadding(dp(this,14),0,0,0);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        ImageView export=iconButton(this,R.drawable.ic_maint_export,"Export maintenance backup");export.setId(R.id.maint_export);export.setOnClickListener(v->export());
        header.addView(export,new LinearLayout.LayoutParams(dp(this,44),dp(this,44)));gap(root,4);root.addView(header,match(dp(this,52)));gap(root,16);

        LinearLayout hero=card(this);hero.setId(R.id.maint_odo_row);hero.setClickable(true);hero.setFocusable(true);
        hero.setContentDescription("Odometer and mileage source settings");hero.setOnClickListener(v->open("odometer",null,null));
        hero.addView(text(this,"TRUCK ODOMETER",10,R.color.text_tertiary));gap(hero,8);
        odo=text(this,"—",32,R.color.text_primary);odo.setId(R.id.maint_odo);hero.addView(odo);
        gap(hero,8);source=text(this,"Waiting for vehicle mileage",12,R.color.text_secondary);hero.addView(source);
        gap(hero,4);observed=text(this,"Tap to manage mileage sources",11,R.color.text_tertiary);hero.addView(observed);root.addView(hero,match(-2));gap(root,12);
        LinearLayout counts=row(this);
        String[] labels={"Due now","Due soon","Need setup"};
        for(int i=0;i<3;i++) {
            LinearLayout stat=card(this);stat.setPadding(dp(this,12),dp(this,10),dp(this,12),dp(this,10));
            totals[i]=text(this,"0",24,i==0?R.color.warn_amber:i==1?R.color.accent:R.color.text_secondary);stat.addView(totals[i]);gap(stat,4);stat.addView(text(this,labels[i],10,R.color.text_tertiary));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(i>0)p.leftMargin=dp(this,8);counts.addView(stat,p);
        }
        root.addView(counts,match(-2));gap(root,14);
        LinearLayout tabs=row(this);remindersTab=action(this,"Reminders",R.drawable.ic_m_build,false);historyTab=action(this,"History",R.drawable.ic_m_history,false);
        remindersTab.setOnClickListener(v->{history=false;refreshList();});historyTab.setOnClickListener(v->{history=true;refreshList();});
        tabs.addView(remindersTab,new LinearLayout.LayoutParams(0,dp(this,48),1));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(0,dp(this,48),1);hp.leftMargin=dp(this,8);tabs.addView(historyTab,hp);root.addView(tabs,match(-2));gap(root,10);
        summary=text(this,"",11,R.color.text_tertiary);root.addView(summary,match(-2));gap(root,8);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setClipToPadding(false);list=column(this);list.setId(R.id.maint_list);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        gap(root,8);TextView add=action(this,"Add reminder",R.drawable.ic_m_add,true);add.setId(R.id.maint_add);add.setOnClickListener(v->open("edit",null,null));root.addView(add,match(dp(this,48)));
    }
    @Override protected void onResume(){super.onResume();store=new MaintenanceStore(this);MaintenanceUi.immersive(this);refreshList();obd.start();ui.post(tick);}
    @Override protected void onPause(){ui.removeCallbacks(tick);obd.stop();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle b){b.putBoolean("history",history);super.onSaveInstanceState(b);}
    private void updateMileage() {
        if(odo==null || isDestroyed())return;
        MileageState s=store.mileage.state();int value=s.value();odo.setText(miles(value));
        boolean live=s.usingAutomatic() && obd.hasFreshMileage(s.source,s.automatic);
        source.setText(store.mileage.label(live));
        long at=s.usingAutomatic()?s.automaticAt:s.manualAt;
        String detail=s.candidate>=0?"Vehicle reading found · tap to review":at>0?"Updated "+updated(at)+" · tap to manage":"Tap to manage mileage sources";
        observed.setText(detail);
        if(value!=lastOdo){lastOdo=value;refreshList();}
    }
    private void refreshList() {
        if(list==null)return;
        int[] count=new int[3];long now=System.currentTimeMillis();int odo=store.odometer();
        for(MaintenanceBook.Item it:store.items()){int s=it.status(odo,now);if(s>=MaintenanceBook.DUE)count[0]++;else if(s==MaintenanceBook.SOON)count[1]++;else if(s==MaintenanceBook.UNKNOWN && (it.intervalMiles>0 || it.intervalMonths>0))count[2]++;}
        for(int i=0;i<3;i++)totals[i].setText(String.valueOf(count[i]));
        remindersTab.setSelected(!history);historyTab.setSelected(history);list.removeAllViews();
        if(store.readError()) {summary.setText("Saved data could not be opened. Export a backup before changing anything.");empty("Data needs attention","Your existing saved file has been preserved.");return;}
        summary.setText(history?"Tap a record to review or correct it.":"Editable reminders · set your intervals and last service.");
        if(history) {
            for(MaintenanceBook.Record record:store.history())historyRow(record);
            if(store.history().isEmpty())empty("No service records yet","Choose Record service on a reminder to add today’s or an earlier service.");
        } else {
            List<MaintenanceBook.Item> items=new ArrayList<>(store.items());
            items.sort(Comparator.comparingInt((MaintenanceBook.Item it)->it.status(odo,now)).reversed().thenComparing(it->it.name));
            for(MaintenanceBook.Item it:items)reminderRow(it,odo,now);
            if(items.isEmpty())empty("No reminders","Add a service and choose a mileage or calendar interval.");
        }
    }
    private void reminderRow(MaintenanceBook.Item it,int odometer,long now) {
        LinearLayout card=card(this),heading=row(this);int state=it.status(odometer,now);
        TextView name=text(this,it.name,16,R.color.text_primary);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        String badge=state==MaintenanceBook.OVERDUE?"Overdue":state==MaintenanceBook.DUE?"Due now":state==MaintenanceBook.SOON?"Due soon":state==MaintenanceBook.CURRENT?"Current":it.intervalMiles==0&&it.intervalMonths==0?"No schedule":it.lastDate==0&&it.lastMiles<0?"Set up":it.intervalMiles>0&&it.milesLeft(odometer)==null?"Mileage needed":"Date needed";
        TextView status=text(this,badge,11,color(state));status.setPadding(dp(this,10),dp(this,5),0,dp(this,5));heading.addView(status);card.addView(heading);gap(card,10);
        Integer miles=it.milesLeft(odometer),days=it.daysLeft(now);String due;
        if(it.intervalMiles==0 && it.intervalMonths==0)due="History only · no reminder interval";
        else if(miles==null && days==null)due="No service baseline yet";
        else {
            List<String> parts=new ArrayList<>();if(miles!=null)parts.add(miles<0?String.format(Locale.US,"%,d mi overdue",-miles):miles==0?"Mileage due now":String.format(Locale.US,"%,d mi remaining",miles));
            if(days!=null)parts.add(days<0?-days+" days overdue":days==0?"Due today":days+" days remaining");due=String.join(" · ",parts);
        }
        TextView dueText=text(this,due,17,color(state));dueText.setMaxLines(2);card.addView(dueText);gap(card,8);
        List<String> intervals=new ArrayList<>();if(it.intervalMiles>0)intervals.add(miles(it.intervalMiles));if(it.intervalMonths>0)intervals.add(it.intervalMonths+" months");
        card.addView(text(this,intervals.isEmpty()?"No reminder interval":"Every "+String.join(" or ",intervals),11,R.color.text_secondary));gap(card,5);
        String last=it.lastDate>0?DATE.format(it.serviceDay()):"Date not recorded";
        if(it.lastMiles>=0)last+=" · "+miles(it.lastMiles);
        card.addView(text(this,it.lastMiles<0 && it.lastDate==0?"Record your last service to start tracking":"Last service: "+last,11,R.color.text_tertiary));gap(card,12);
        LinearLayout buttons=row(this);TextView edit=action(this,"Edit",R.drawable.ic_m_edit,false),record=action(this,"Record service",R.drawable.ic_m_check,true);
        edit.setOnClickListener(v->open("edit",it.id,null));record.setOnClickListener(v->open("record",it.id,null));
        buttons.addView(edit,new LinearLayout.LayoutParams(0,dp(this,48),1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(this,48),1.45f);p.leftMargin=dp(this,8);buttons.addView(record,p);card.addView(buttons);addCard(card);
    }
    private void historyRow(MaintenanceBook.Record r) {
        LinearLayout card=card(this);LinearLayout heading=row(this);TextView name=text(this,r.name,16,R.color.text_primary);heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        ImageView edit=iconButton(this,R.drawable.ic_m_edit,"Edit service record");edit.setOnClickListener(v->open("record",r.itemId,r.id));heading.addView(edit,new LinearLayout.LayoutParams(dp(this,44),dp(this,44)));card.addView(heading);gap(card,4);
        card.addView(text(this,(r.date>0?DATE.format(r.serviceDay()):"Date not recorded")+" · "+(r.miles>=0?miles(r.miles):"Mileage not recorded"),13,R.color.text_secondary));
        if(!r.notes.isEmpty()){gap(card,10);TextView notes=text(this,r.notes,12,R.color.text_tertiary);notes.setMaxLines(3);notes.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(notes);}
        card.setOnClickListener(v->open("record",r.itemId,r.id));addCard(card);
    }
    private void empty(String title,String body){LinearLayout card=card(this);card.addView(text(this,title,18,R.color.text_primary));gap(card,10);card.addView(text(this,body,13,R.color.text_secondary));addCard(card);}
    private void addCard(View card){LinearLayout.LayoutParams p=match(-2);p.bottomMargin=dp(this,10);list.addView(card,p);}
    private void open(String mode,String item,String record){startActivity(new Intent(this,MaintenanceEditorActivity.class).putExtra("mode",mode).putExtra("item",item).putExtra("record",record));}
    private void export(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"phoenix-maintenance-"+LocalDate.now()+".json");
        try{startActivityForResult(i,40);}catch(ActivityNotFoundException e){Toast.makeText(this,"No file picker is installed on this head unit",Toast.LENGTH_LONG).show();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=40||result!=RESULT_OK||data==null||data.getData()==null)return;
        try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")) {if(out==null)throw new java.io.IOException();out.write(new MaintenanceStore(this).exportJson().getBytes(StandardCharsets.UTF_8));Toast.makeText(this,"Maintenance backup saved",Toast.LENGTH_LONG).show();}
        catch(Exception e){Toast.makeText(this,"Backup could not be saved",Toast.LENGTH_LONG).show();}
    }
}
