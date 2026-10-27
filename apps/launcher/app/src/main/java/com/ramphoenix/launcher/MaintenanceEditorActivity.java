package com.ramphoenix.launcher;

import android.app.*;
import android.content.Context;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.*;
import static com.ramphoenix.launcher.MaintenanceUi.*;

/** Dedicated forms: scrollable fields with Save/Cancel outside the keyboard's resized body. */
public class MaintenanceEditorActivity extends Activity {
    private MaintenanceStore store;
    private ObdClient obd;
    private MaintenanceBook.Item item;
    private MaintenanceBook.Record record;
    private String mode,itemId,recordId,original,pendingSource="";
    private int pendingMiles=-1;
    private LinearLayout fields;
    private TextView error,dateButton,mileageHelp,readingText,candidateText,candidateButton;
    private EditText name,intervalMiles,months,serviceMiles,notes,manual;
    private RadioGroup sourceMode;
    private RadioButton automatic,manualOnly;
    private long serviceDate;
    private boolean milesEdited,settingMileage;
    private boolean finishedClean;
    private String draftKey;
    private final List<EditText> inputs=new ArrayList<>();

    @Override protected void attachBaseContext(Context base){super.attachBaseContext(base);Ui.applyDensity(this);}
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);store=new MaintenanceStore(this);mode=getIntent().getStringExtra("mode");if(mode==null)mode="edit";
        itemId=getIntent().getStringExtra("item");recordId=getIntent().getStringExtra("record");item=store.byId(itemId);record=store.recordById(recordId);
        draftKey=mode+":"+(itemId==null?"new":itemId)+":"+(recordId==null?"new":recordId);
        if(mode.equals("record") && item==null && record==null){Toast.makeText(this,"Reminder no longer exists",Toast.LENGTH_LONG).show();finish();return;}
        if(itemId!=null && item==null && !mode.equals("record")){Toast.makeText(this,"Reminder no longer exists",Toast.LENGTH_LONG).show();finish();return;}
        if(itemId==null)itemId=saved!=null?saved.getString("itemId"):UUID.randomUUID().toString();
        if(mode.equals("record") && recordId==null)recordId=saved!=null?saved.getString("recordId"):UUID.randomUUID().toString();
        serviceDate=saved!=null?saved.getLong("date"):record!=null?(record.date>0?MaintenanceBook.dateValue(record.serviceDay()):0):MaintenanceBook.dateValue(LocalDate.now());
        milesEdited=saved!=null?saved.getBoolean("milesEdited"):record!=null;
        if(saved!=null){pendingSource=saved.getString("pendingSource","");pendingMiles=saved.getInt("pendingMiles",-1);}
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root=column(this);root.setPadding(dp(this,20),dp(this,16),dp(this,20),dp(this,12));com.ramphoenix.appearance.WallpaperScene.content(this,root);
        LinearLayout heading=row(this);ImageView back=iconButton(this,R.drawable.ic_m_keyboard_arrow_down,"Cancel editing");back.setOnClickListener(v->cancel());heading.addView(back,new LinearLayout.LayoutParams(dp(this,44),dp(this,44)));
        String title=mode.equals("odometer")?"Mileage source":mode.equals("record")?(record==null?"Record service":"Edit service record"):item==null?"New reminder":"Edit reminder";
        TextView caption=text(this,title,20,R.color.text_primary);caption.setPadding(dp(this,14),0,0,0);heading.addView(caption,new LinearLayout.LayoutParams(0,-2,1));root.addView(heading,match(dp(this,56)));gap(root,10);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);fields=column(this);fields.setPadding(0,dp(this,4),0,dp(this,16));scroll.addView(fields);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(mode.equals("odometer"))mileageForm(saved);else if(mode.equals("record"))recordForm();else reminderForm();
        error=text(this,"",12,R.color.warn_amber);error.setId(R.id.maint_form_error);error.setVisibility(View.GONE);root.addView(error,match(-2));
        LinearLayout footer=row(this);footer.setPadding(0,dp(this,12),0,0);TextView cancel=action(this,"Cancel",0,false),save=action(this,"Save",R.drawable.ic_m_check,true);
        cancel.setId(R.id.maint_cancel);save.setId(R.id.maint_save);cancel.setOnClickListener(v->cancel());save.setOnClickListener(v->save());
        footer.addView(cancel,new LinearLayout.LayoutParams(0,dp(this,48),1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(this,48),1.4f);p.leftMargin=dp(this,12);footer.addView(save,p);root.addView(footer,match(dp(this,60)));
        for(int i=0;i<inputs.size();i++) {
            EditText input=inputs.get(i);input.setImeOptions((i==inputs.size()-1?EditorInfo.IME_ACTION_DONE:EditorInfo.IME_ACTION_NEXT)|EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_FLAG_NO_FULLSCREEN);
            input.setOnEditorActionListener((view,action,event)->{if(action==EditorInfo.IME_ACTION_DONE){save();return true;}return false;});
        }
        original=saved==null?signature():saved.getString("original",signature());
        if(saved==null)restoreDraft();
        if(store.readError() && !mode.equals("odometer")){save.setEnabled(false);save.setAlpha(.5f);showError("Saved data could not be opened. Export it before making changes.");}
        if(!mode.equals("edit"))obd=new ObdClient(this,this::refreshMileage);
    }
    private void reminderForm() {
        section("Reminder details","Choose the service and when you want a reminder. Blank or zero intervals are not tracked.");
        name=input("Service name",R.id.maint_field_name,item==null?"":item.name,false,false);
        intervalMiles=input("Mileage interval · mi",R.id.maint_field_interval,item==null?"":Integer.toString(item.intervalMiles),true,false);
        months=input("Calendar interval · months",R.id.maint_field_months,item==null?"":Integer.toString(item.intervalMonths),true,false);
        notes=input("Reminder notes",R.id.maint_field_notes,item==null?"":item.notes,false,true);
        if(item!=null) {
            section("Service baseline",item.lastDate==0 && item.lastMiles<0?"No service recorded yet. Use Record service on the reminder to enter a date and mileage.":
                    "Last service: "+(item.lastDate>0?MaintenanceActivity.DATE.format(item.serviceDay()):"Date unknown")+" · "+miles(item.lastMiles)+". Use Record service to update it.");
            deleteButton("Delete reminder",()->store.removeItem(item.id),"Delete this reminder? Its service history will be kept.");
        }
    }
    private void recordForm() {
        String name=record!=null?record.name:item.name;
        section(name,"Record today’s service or change the date to enter an earlier service.");
        serviceMiles=input("Mileage at service · mi",R.id.maint_field_miles,record!=null?(record.miles<0?"":Integer.toString(record.miles)):
                store.odometer()<0?"":Integer.toString(store.odometer()),true,false);
        mileageHelp=text(this,"",11,R.color.text_tertiary);fields.addView(mileageHelp,match(-2));gap(fields,12);
        serviceMiles.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){if(!settingMileage)milesEdited=true;}public void afterTextChanged(Editable e){}});
        dateField();notes=input("Service notes",R.id.maint_field_notes,record==null?"":record.notes,false,true);
        if(record!=null)deleteButton("Delete service record",()->store.removeRecord(record.id),"Delete this service record? The reminder will return to its previous service baseline.");
    }
    private void mileageForm(Bundle saved) {
        section("Automatic vehicle mileage","Use the vehicle reading when available. A saved reading is labeled with its date; it is never presented as a live connection.");
        readingText=text(this,"",24,R.color.text_primary);fields.addView(readingText,match(-2));gap(fields,6);
        mileageHelp=text(this,"",12,R.color.text_secondary);fields.addView(mileageHelp,match(-2));gap(fields,16);
        sourceMode=new RadioGroup(this);automatic=new RadioButton(this);automatic.setId(R.id.maint_auto_source);automatic.setText("Automatic · OBD");manualOnly=new RadioButton(this);manualOnly.setId(R.id.maint_manual_source);manualOnly.setText("Manual override");
        for(RadioButton button:new RadioButton[]{automatic,manualOnly}){button.setTextSize(14);button.setTextColor(getColor(R.color.text_primary));sourceMode.addView(button,new RadioGroup.LayoutParams(-1,dp(this,52)));}
        sourceMode.check((saved!=null?saved.getBoolean("manualOnly"):store.mileage.state().manualOnly)?manualOnly.getId():automatic.getId());fields.addView(sourceMode);gap(fields,12);
        manual=input("Manual mileage / fallback · mi",R.id.maint_field_manual,store.mileage.state().manual<0?"":Integer.toString(store.mileage.state().manual),true,false);
        TextView help=text(this,"Automatic mode keeps the last vehicle reading when disconnected. Choose Manual override to replace a saved reading yourself.",11,R.color.text_tertiary);fields.addView(help,match(-2));gap(fields,20);
        candidateText=text(this,"",13,R.color.text_secondary);fields.addView(candidateText,match(-2));gap(fields,10);
        candidateButton=action(this,"This matches the truck’s cluster",R.drawable.ic_m_check,false);candidateButton.setId(R.id.maint_confirm_mileage);
        candidateButton.setOnClickListener(v->{MileageState state=store.mileage.state();if(!state.candidateRecent(System.currentTimeMillis())){showError("Reconnect the vehicle to refresh this reading");return;}pendingSource=state.candidateSource;pendingMiles=state.candidate;automatic.setChecked(true);candidateButton.setText("Selected · Save to enable automatic updates");});fields.addView(candidateButton,match(dp(this,52)));
    }
    private void section(String title,String description){gap(fields,8);fields.addView(text(this,title,16,R.color.text_primary),match(-2));gap(fields,8);fields.addView(text(this,description,12,R.color.text_secondary),match(-2));gap(fields,18);}
    private EditText input(String label,int id,String value,boolean numeric,boolean multiline){
        TextView caption=text(this,label,12,R.color.text_secondary);caption.setLabelFor(id);fields.addView(caption,match(-2));gap(fields,7);
        EditText e=new EditText(this);e.setId(id);e.setTextSize(16);e.setTextColor(getColor(R.color.text_primary));e.setHintTextColor(getColor(R.color.text_tertiary));
        e.setInputType(numeric?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));
        e.setSingleLine(!multiline);e.setMinLines(multiline?3:1);e.setGravity(Gravity.TOP|Gravity.START);e.setPadding(dp(this,14),dp(this,14),dp(this,14),dp(this,14));e.setBackgroundResource(R.drawable.maint_field);e.setText(value);
        fields.addView(e,match(multiline?-2:dp(this,56)));gap(fields,18);inputs.add(e);return e;
    }
    private void dateField(){
        fields.addView(text(this,"Service date",12,R.color.text_secondary));gap(fields,7);LinearLayout line=row(this);
        dateButton=action(this,"",R.drawable.ic_m_history,false);dateButton.setId(R.id.maint_field_date);updateDate();dateButton.setOnClickListener(v->pickDate());line.addView(dateButton,new LinearLayout.LayoutParams(0,dp(this,52),1));
        TextView clear=action(this,"Clear",0,false);clear.setOnClickListener(v->{serviceDate=0;updateDate();refreshMileage();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(this,72),dp(this,52));p.leftMargin=dp(this,8);line.addView(clear,p);fields.addView(line);gap(fields,18);
    }
    private void pickDate(){
        hideKeyboard();LocalDate current=serviceDate>0?MaintenanceBook.localDate(serviceDate):LocalDate.now();
        DatePickerDialog picker=new DatePickerDialog(this,(view,y,m,d)->{
            serviceDate=MaintenanceBook.dateValue(LocalDate.of(y,m+1,d));updateDate();
            if(record==null && serviceMiles!=null && !milesEdited && !LocalDate.of(y,m+1,d).equals(LocalDate.now())){settingMileage=true;serviceMiles.setText("");settingMileage=false;}
            refreshMileage();
        },current.getYear(),current.getMonthValue()-1,current.getDayOfMonth());picker.getDatePicker().setMaxDate(System.currentTimeMillis());picker.show();
    }
    private void updateDate(){if(dateButton!=null)dateButton.setText(serviceDate>0?MaintenanceActivity.DATE.format(MaintenanceBook.localDate(serviceDate)):"Date not recorded");}
    private void refreshMileage(){
        if(isDestroyed()||mileageHelp==null)return;
        MileageState s=store.mileage.state();boolean live=obd!=null && s.usingAutomatic() && obd.hasFreshMileage(s.source,s.automatic);
        if(mode.equals("odometer")){
            readingText.setText(miles(s.value()));mileageHelp.setText(store.mileage.label(live)+(s.usingAutomatic()&&s.automaticAt>0?" · "+updated(s.automaticAt):""));
            boolean candidate=s.candidate>=0;candidateText.setVisibility(candidate?View.VISIBLE:View.GONE);candidateButton.setVisibility(candidate?View.VISIBLE:View.GONE);
            if(candidate){boolean recent=s.candidateRecent(System.currentTimeMillis());candidateText.setText((recent?"Vehicle reading found: ":"Saved vehicle reading: ")+miles(s.candidate)+(recent?". Compare it with the truck’s cluster before enabling this source.":". Reconnect the vehicle to refresh it before enabling this source."));candidateButton.setEnabled(recent);candidateButton.setAlpha(recent?1f:.5f);}
        }else{
            boolean today=serviceDate>0 && MaintenanceBook.localDate(serviceDate).equals(LocalDate.now());
            if(record==null && !milesEdited && today && s.value()>=0){boolean clean=original!=null && original.equals(signature());settingMileage=true;serviceMiles.setText(Integer.toString(s.value()));settingMileage=false;if(clean)original=signature();}
            mileageHelp.setText(record!=null?"Editing this record does not change the truck’s odometer.":!today?"Enter the mileage for that service, not today’s odometer.":s.value()<0?"Vehicle mileage unavailable. Enter mileage, or leave it blank for a date-only record.":store.mileage.label(live)+" · you can change the mileage for this service.");
        }
    }
    private int number(EditText e,String label,int empty,int max){try{return MaintenanceInput.number(e.getText().toString(),label,empty,max);}catch(IllegalArgumentException failure){e.setError(failure.getMessage());e.requestFocus();throw failure;}}
    private void save(){
        error.setVisibility(View.GONE);
        try{
            if(notes!=null && notes.length()>4000){notes.setError("Keep notes under 4,000 characters");notes.requestFocus();throw new IllegalArgumentException("Keep notes under 4,000 characters");}
            if(mode.equals("odometer")){
                int fallback=number(manual,"Manual mileage",-1,MaintenanceInput.MAX_MILES);
                if(manualOnly.isChecked() && fallback<0)throw new IllegalArgumentException("Enter mileage for Manual override");
                if(!pendingSource.isEmpty()){MileageState s=store.mileage.state();if(!s.candidateSource.equals(pendingSource)||Math.abs(s.candidate-pendingMiles)>1)throw new IllegalArgumentException("The vehicle reading changed; compare it with the cluster again");}
                store.mileage.configure(manualOnly.isChecked(),fallback,!pendingSource.isEmpty() && automatic.isChecked());
            }else if(mode.equals("record")){
                int miles=number(serviceMiles,"Service mileage",-1,MaintenanceInput.MAX_MILES);MileageState state=store.mileage.state();
                if(miles>=0 && state.usingAutomatic() && obd!=null && obd.hasFreshMileage(state.source,state.automatic) && miles>state.automatic)
                    throw new IllegalArgumentException("Service mileage is above the current vehicle odometer");
                store.putRecord(new MaintenanceBook.Record(recordId,itemId,record!=null?record.name:item.name,miles,serviceDate,notes.getText().toString().trim()));
            }else{
                String label;
                try{label=MaintenanceInput.name(name.getText().toString());}catch(IllegalArgumentException e){name.setError(e.getMessage());name.requestFocus();throw e;}
                MaintenanceBook.Item next=new MaintenanceBook.Item(itemId,label,number(intervalMiles,"Mileage interval",0,MaintenanceInput.MAX_INTERVAL),number(months,"Calendar interval",0,MaintenanceInput.MAX_MONTHS));
                next.notes=notes.getText().toString().trim();store.putItem(next);
            }
            finishedClean=true;clearDraft();hideKeyboard();Toast.makeText(this,"Saved",Toast.LENGTH_SHORT).show();finish();
        }catch(Exception failure){showError(failure.getMessage()==null?"Could not save; your entries are still here":failure.getMessage());}
    }
    private void deleteButton(String label,Runnable remove,String message){gap(fields,16);TextView button=action(this,label,R.drawable.ic_m_delete,false);button.setTextColor(getColor(R.color.warn_amber));button.setOnClickListener(v->{hideKeyboard();new AlertDialog.Builder(this).setTitle(label).setMessage(message).setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{try{remove.run();finishedClean=true;clearDraft();finish();}catch(Exception e){showError(e.getMessage());}}).show();});fields.addView(button,match(dp(this,48)));}
    private void showError(String message){if(error!=null){error.setText(message);error.setVisibility(View.VISIBLE);}}
    private void hideKeyboard(){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(),0);}
    private String signature(){StringBuilder value=new StringBuilder(mode).append('|').append(serviceDate).append('|').append(pendingSource);for(EditText e:inputs)value.append('|').append(e.getText());if(manualOnly!=null)value.append('|').append(manualOnly.isChecked());return value.toString();}
    private void discard(){finishedClean=true;clearDraft();finish();}
    private void cancel(){if(original!=null && !original.equals(signature())){hideKeyboard();new AlertDialog.Builder(this).setTitle("Discard changes?").setMessage("Your edits have not been saved.").setNegativeButton("Keep editing",null).setPositiveButton("Discard",(d,w)->discard()).show();}else discard();}
    private void clearDraft(){if(draftKey!=null)getSharedPreferences("maintenance_drafts",MODE_PRIVATE).edit().remove(draftKey).apply();}
    private void saveDraft(){
        if(finishedClean || original==null || original.equals(signature()))return;
        try{
            JSONObject values=new JSONObject();for(EditText e:inputs)values.put(getResources().getResourceEntryName(e.getId()),e.getText().toString());
            JSONObject draft=new JSONObject().put("values",values).put("item",itemId).put("record",recordId)
                    .put("day",serviceDate>0?MaintenanceBook.localDate(serviceDate).toString():"").put("original",original)
                    .put("milesEdited",milesEdited).put("manualOnly",manualOnly!=null && manualOnly.isChecked()).put("pending",pendingSource).put("pendingMiles",pendingMiles);
            getSharedPreferences("maintenance_drafts",MODE_PRIVATE).edit().putString(draftKey,draft.toString()).apply();
        }catch(Exception ignored){/* The visible form remains; no service record is written. */}
    }
    private void restoreDraft(){
        String raw=getSharedPreferences("maintenance_drafts",MODE_PRIVATE).getString(draftKey,null);if(raw==null)return;
        try{
            JSONObject draft=new JSONObject(raw),values=draft.getJSONObject("values");settingMileage=true;
            for(EditText e:inputs){String key=getResources().getResourceEntryName(e.getId());if(values.has(key))e.setText(values.getString(key));}
            settingMileage=false;
            if(getIntent().getStringExtra("item")==null)itemId=draft.optString("item",itemId);
            if(mode.equals("record") && getIntent().getStringExtra("record")==null)recordId=draft.optString("record",recordId);
            String day=draft.optString("day","");serviceDate=day.isEmpty()?0:MaintenanceBook.dateValue(LocalDate.parse(day));updateDate();
            milesEdited=draft.optBoolean("milesEdited",false)||(serviceMiles!=null && serviceMiles.length()>0);
            if(manualOnly!=null)sourceMode.check(draft.optBoolean("manualOnly")?manualOnly.getId():automatic.getId());
            pendingSource=draft.optString("pending","");pendingMiles=draft.optInt("pendingMiles",-1);original=draft.optString("original",original);
            Toast.makeText(this,"Unsaved draft restored",Toast.LENGTH_SHORT).show();
        }catch(Exception e){settingMileage=false;Toast.makeText(this,"A previous draft could not be restored",Toast.LENGTH_LONG).show();}
    }
    @Override public void onBackPressed(){cancel();}
    @Override protected void onResume(){super.onResume();MaintenanceUi.immersive(this);refreshMileage();if(obd!=null)obd.start();}
    @Override protected void onPause(){saveDraft();if(obd!=null)obd.stop();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("itemId",itemId);out.putString("recordId",recordId);out.putLong("date",serviceDate);out.putBoolean("milesEdited",milesEdited);out.putString("pendingSource",pendingSource);out.putInt("pendingMiles",pendingMiles);out.putString("original",original);out.putBoolean("manualOnly",manualOnly!=null && manualOnly.isChecked());super.onSaveInstanceState(out);}
}
