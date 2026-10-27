package com.ramphoenix.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.List;
import static com.ramphoenix.launcher.MaintenanceUi.*;

/** Named load profiles with measured/target separation and a keyboard-safe setup page. */
public class SuspensionActivity extends Activity {
    private WirelessOneClient client;
    private LinearLayout root, body, footer;
    private TextView actual, target, status, detail, apply, deviceChoice, error;
    private final TextView[] profiles = new TextView[4];
    private EditText kit, minimum, maximum;
    private int selected = -1;
    private boolean setup;
    private String selectedAddress = "", selectedName = "";
    private final Runnable update = this::refresh;
    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(base);Ui.applyDensity(this); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);client = WirelessOneClient.get(this);
        if(state!=null && state.getBoolean("setup")) {
            showSetup();selectedAddress=state.getString("address","");selectedName=state.getString("name","");
            kit.setText(state.getString("kit",""));minimum.setText(state.getString("minimum",""));maximum.setText(state.getString("maximum",""));
            deviceChoice.setText(selectedAddress.isEmpty()?"Choose paired WirelessOne":selectedName);
        } else showControls();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("setup",setup);
        if(setup) {
            state.putString("address",selectedAddress);state.putString("name",selectedName);state.putString("kit",kit.getText().toString());
            state.putString("minimum",minimum.getText().toString());state.putString("maximum",maximum.getText().toString());
        }
        // Do not restore a selected pressure or an in-flight command after recreation.
        super.onSaveInstanceState(state);
    }
    @Override protected void onStart() { super.onStart();client.listen(update);client.start(this);refresh(); }
    @Override protected void onResume() { super.onResume();MaintenanceUi.immersive(this); }
    @Override protected void onStop() { client.unlisten(update);client.stop(this);super.onStop(); }
    @Override public void onBackPressed() { if(setup) showControls();else super.onBackPressed(); }

    private void page(String title) {
        root=column(this);root.setBackgroundColor(getColor(R.color.bg_base));
        root.setPadding(dp(this,16),dp(this,12),dp(this,16),dp(this,12));setContentView(root);
        LinearLayout header=row(this);ImageView back=iconButton(this,R.drawable.ic_m_keyboard_arrow_down,"Back");
        back.setOnClickListener(v -> onBackPressed());header.addView(back,new LinearLayout.LayoutParams(dp(this,44),dp(this,44)));
        TextView heading=text(this,title,21,R.color.text_primary);heading.setPadding(dp(this,12),0,0,0);
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));root.addView(header,match(dp(this,52)));gap(root,12);
        ScrollView scroll=new ScrollView(this);body=column(this);scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));gap(root,10);
        footer=column(this);root.addView(footer,match(-2));
    }
    private void showControls() {
        setup=false;hideKeyboard();page("Air suspension");
        LinearLayout hero=card(this);hero.addView(text(this,"MEASURED SPRING PRESSURE",10,R.color.text_tertiary));gap(hero,8);
        actual=text(this,"— psi",44,R.color.text_primary);hero.addView(actual);gap(hero,8);
        target=text(this,"Controller target —",14,R.color.text_secondary);hero.addView(target);gap(hero,6);
        status=text(this,"",12,R.color.text_secondary);hero.addView(status);body.addView(hero,match(-2));gap(body,14);
        body.addView(text(this,"LOAD PROFILES",10,R.color.text_tertiary));gap(body,8);
        for(int rowIndex=0;rowIndex<2;rowIndex++) {
            LinearLayout row=row(this);
            for(int column=0;column<2;column++) {
                int index=rowIndex*2+column;
                TextView button=text(this,"",14,R.color.text_primary);profiles[index]=button;
                button.setGravity(Gravity.CENTER);button.setMinHeight(dp(this,94));button.setPadding(dp(this,8),dp(this,12),dp(this,8),dp(this,12));
                button.setBackgroundResource(R.drawable.air_profile);button.setClickable(true);button.setFocusable(true);
                button.setOnClickListener(v -> {selected=index;refresh();});
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(column>0)p.leftMargin=dp(this,8);row.addView(button,p);
            }
            body.addView(row,match(-2));gap(body,8);
        }
        detail=text(this,"",12,R.color.text_tertiary);body.addView(detail,match(-2));gap(body,14);
        LinearLayout actions=row(this);
        TextView connect=action(this,"Connect",R.drawable.ic_bluetooth,false);connect.setOnClickListener(v -> connect());
        TextView settings=action(this,"Setup",R.drawable.ic_settings,false);settings.setOnClickListener(v -> showSetup());
        actions.addView(connect,new LinearLayout.LayoutParams(0,dp(this,48),1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(this,48),1);p.leftMargin=dp(this,8);actions.addView(settings,p);
        body.addView(actions,match(-2));gap(body,8);
        TextView official=action(this,"Open WirelessOne",0,false);official.setOnClickListener(v -> openOfficial());body.addView(official,match(dp(this,48)));gap(body,8);
        TextView disconnect=action(this,"Disconnect",0,false);disconnect.setOnClickListener(v -> client.disconnect());body.addView(disconnect,match(dp(this,44)));
        apply=action(this,"Select a load profile",0,true);apply.setOnClickListener(v -> {
            if(selected<0)return;
            SuspensionConfig config=new SuspensionConfig(this);
            client.setPressure(WirelessOneProtocol.presetPsi(selected,config.minimum));refresh();
        });footer.addView(apply,match(dp(this,52)));refresh();
    }

    private void refresh() {
        if(setup || actual==null)return;
        SuspensionConfig config=new SuspensionConfig(this);
        actual.setText(client.actualText()+" psi");target.setText("Controller target  "+client.targetText());
        String block=client.blocked();
        String message=client.status();
        if(client.connected() && SuspensionState.fresh(client.state.actualAt,android.os.SystemClock.elapsedRealtime())
                && !"Connected".equals(client.state.activity())) message=client.state.activity()+" · "+message;
        if(block!=null && !message.contains(block)) message+="\n"+block;
        status.setText(message);
        for(int i=0;i<4;i++) {
            int psi=WirelessOneProtocol.presetPsi(i,config.minimum);
            String label=psi+" psi\n"+WirelessOneProtocol.NAMES[i];
            android.text.SpannableString styled=new android.text.SpannableString(label);
            styled.setSpan(new android.text.style.RelativeSizeSpan(1.6f),0,label.indexOf('\n'),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            profiles[i].setText(styled);profiles[i].setSelected(selected==i);
            profiles[i].setContentDescription(WirelessOneProtocol.NAMES[i]+", target "+psi+" psi");
        }
        int psi=selected<0?-1:WirelessOneProtocol.presetPsi(selected,config.minimum);
        boolean inRange=WirelessOneProtocol.validLimits(config.minimum,config.maximum) && psi>=config.minimum && psi<=config.maximum;
        apply.setText(selected<0?"Select a load profile":client.pending()?"Checking target…":"Set "+psi+" psi");
        apply.setEnabled(selected>=0 && block==null && inRange);apply.setAlpha(apply.isEnabled()?1f:0.45f);
        detail.setText(config.kit.isEmpty()?"Set your air-spring kit limits in Setup before applying a profile."
                : config.kit+" · allowed targets "+config.minimum+"–"+config.maximum+" psi"
                +(selected>=0 && !inRange?"\nThis profile is outside your kit limits.":""));
    }

    private EditText field(String label,String value,boolean number) {
        body.addView(text(this,label,12,R.color.text_secondary));gap(body,5);
        EditText edit=new EditText(this);edit.setText(value);edit.setTextSize(16);edit.setTextColor(getColor(R.color.text_primary));edit.setSingleLine(true);
        edit.setInputType(number?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT);
        edit.setImeOptions(EditorInfo.IME_ACTION_NEXT|EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_FLAG_NO_FULLSCREEN);
        body.addView(edit,match(dp(this,48)));gap(body,14);return edit;
    }
    private void showSetup() {
        setup=true;page("Suspension setup");SuspensionConfig config=new SuspensionConfig(this);
        selectedAddress=config.address;selectedName=config.name;
        body.addView(text(this,"Pair the head unit with the manifold in WirelessOne first. Phoenix uses that same Bluetooth pairing.",14,R.color.text_secondary));gap(body,10);
        deviceChoice=action(this,config.address.isEmpty()?"Choose paired WirelessOne":config.name,R.drawable.ic_bluetooth,false);
        deviceChoice.setOnClickListener(v -> chooseDevice());body.addView(deviceChoice,match(dp(this,52)));gap(body,16);
        kit=field("Air-spring kit / part number",config.kit,false);
        minimum=field("Minimum allowed target (psi, at least 5)",config.minimum<0?"":String.valueOf(config.minimum),true);
        maximum=field("Maximum allowed target (psi, up to 100)",config.maximum<0?"":String.valueOf(config.maximum),true);
        maximum.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_FLAG_NO_FULLSCREEN);
        maximum.setOnEditorActionListener((v,action,event) -> {if(action==EditorInfo.IME_ACTION_DONE){saveSetup();return true;}return false;});
        body.addView(text(this,"Use the limits from your air-spring kit manual. WirelessOne maintains at least 5 psi. Empty uses your configured minimum. Saving setup does not change pressure.",12,R.color.text_tertiary));gap(body,8);
        error=text(this,"",13,R.color.warn_amber);body.addView(error,match(-2));
        LinearLayout buttons=row(this);TextView cancel=action(this,"Cancel",0,false),save=action(this,"Save setup",0,true);
        cancel.setOnClickListener(v -> showControls());save.setOnClickListener(v -> saveSetup());
        buttons.addView(cancel,new LinearLayout.LayoutParams(0,dp(this,52),1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(this,52),1);p.leftMargin=dp(this,8);buttons.addView(save,p);footer.addView(buttons,match(-2));
        TextView open=action(this,"Open WirelessOne for pairing",0,false);open.setOnClickListener(v -> openOfficial());gap(body,12);body.addView(open,match(dp(this,48)));
    }
    private void saveSetup() {
        try {
            int lo=Integer.parseInt(minimum.getText().toString().trim()),hi=Integer.parseInt(maximum.getText().toString().trim());
            SuspensionConfig.save(this,selectedAddress,selectedName,kit.getText().toString(),lo,hi);
            selected=-1;showControls();client.connect(true);
        } catch(NumberFormatException invalid) {error.setText("Enter whole-number minimum and maximum pressures.");}
        catch(IllegalArgumentException invalid) {error.setText("Choose a paired manifold, name the kit, and enter limits between 5 and 100 psi. Minimum must be no higher than maximum.");}
        catch(IllegalStateException failed) {error.setText(failed.getMessage());}
    }
    private void chooseDevice() {
        if(!permission()) {requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},41);return;}
        List<BluetoothDevice> devices=client.paired();
        if(devices.isEmpty()) {error.setText("No paired WirelessOne manifold found. Turn on Bluetooth and pair it using WirelessOne first.");return;}
        String[] names=new String[devices.size()];for(int i=0;i<names.length;i++)names[i]=devices.get(i).getName()+"\n"+devices.get(i).getAddress();
        new AlertDialog.Builder(this).setTitle("Paired WirelessOne manifolds").setItems(names,(dialog,index) -> {
            BluetoothDevice chosen=devices.get(index);selectedAddress=chosen.getAddress();selectedName=chosen.getName();deviceChoice.setText(selectedName);
        }).setNegativeButton("Cancel",null).show();
    }
    private boolean permission() {return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private void connect() {if(!permission())requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},41);else client.connect(true);}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==41 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) {if(setup)chooseDevice();else client.connect(true);}
    }
    private void openOfficial() {
        Intent launch=getPackageManager().getLaunchIntentForPackage(WirelessOneProtocol.PACKAGE);
        if(launch==null){Toast.makeText(this,"Install Air Lift WirelessOne on this head unit first",Toast.LENGTH_LONG).show();return;}
        client.yieldToOfficialApp();AppLaunch.fullscreen(this,launch);
    }
    private void hideKeyboard() {
        if(getCurrentFocus()!=null) {
            InputMethodManager manager=getSystemService(InputMethodManager.class);
            if(manager!=null)manager.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(),0);
        }
    }
}
