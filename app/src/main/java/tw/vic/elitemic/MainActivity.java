package tw.vic.elitemic;

import android.Manifest;
import android.app.Activity;
import android.provider.Settings;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.widget.*;

public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private TextView status;
    private Button enable, disable, prepare;
    private SharedPreferences prefs;
    private int dp(int x) { return Math.round(getResources().getDisplayMetrics().density*x); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = Prefs.of(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24),dp(32),dp(24),dp(24));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);
        TextView title = new TextView(this);
        title.setTextSize(26); title.setText("耳機聽寫待命"); box.addView(title);
        status = new TextView(this);
        status.setPadding(0,dp(20),0,dp(20)); status.setTextSize(18); box.addView(status);
        prepare=new Button(this); prepare.setText("準備本次聽寫"); box.addView(prepare);
        prepare.setOnClickListener(v -> startForegroundService(new Intent(this,StandbyService.class).setAction(StandbyService.PREPARE)));
        Button trace = new Button(this);
        trace.setText("查看系統收音紀錄");
        box.addView(trace);
        trace.setOnClickListener(v -> {
            String report = SystemAudioTrace.report(prefs);
            new AlertDialog.Builder(this).setTitle("系統收音紀錄")
                .setMessage(report).setNegativeButton("關閉",null)
                .setNeutralButton("藍牙設定",(dialog,which) -> {
                    Intent settings = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                    try { startActivity(settings); }
                    catch (android.content.ActivityNotFoundException unavailable) { notice("系統未提供此設定頁面"); }
                })
                .setPositiveButton("複製紀錄",(dialog,which) -> {
                    android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("系統收音紀錄",report));
                }).show();
        });
        Button meter=new Button(this);
        meter.setText("量目前輸入的音量（六秒）"); box.addView(meter);
        meter.setOnClickListener(v->startActivity(new Intent(this,LevelActivity.class)));
        TextView description = new TextView(this);
        description.setText("啟用後自動使用已連線的藍牙通話耳機。尚未連線時會等待耳機，系統進入通話時讓路。離開此畫面仍會待命。\n\nTypeless 負責收音。Android 16 以上會先嘗試開啟傳統藍牙耳機的語音通道。較早版本、LE 耳麥或耳機未確認時，會請 Android 把通訊路由與待命音軌指定到耳機。日常待命只保留系統狀態；手動量音量才會詢問麥克風權限，不儲存音檔。偵測到影片或音樂播放時會釋放耳機語音通道。預設使用一般耳機模式，可以聽音樂和使用通透。播放或暫停影片都不會開啟耳麥。勾選下方的「持續開啟耳麥」才會在閒置時保留語音通道。藍牙通話耳機新連線時會自動準備一次。通知顯示就緒後 20 秒內可直接點浮球。逾時回到日常耳機模式，之後直接點 Typeless 可能先用到手機麥克風。可先從通知點「準備本次聽寫」，看到「藍牙輸入已列出」再點浮球。就緒後保留 20 秒等第一句。每句結束後保留 12 秒，可直接再點原浮球。停頓超過 12 秒會恢復一般耳機模式。可在系統收音紀錄比對這次準備後的第一個與最近錄音輸入。藍牙待命可能增加耗電。來電、接上 USB／有線麥克風或停止待命時會釋放。");
        box.addView(description);
        enable=new Button(this); enable.setText("啟用自動待命"); box.addView(enable);
        enable.setOnClickListener(v -> {
            begin();
        });
        CheckBox keepVoiceReady = new CheckBox(this);
        keepVoiceReady.setText("持續開啟耳麥（選用，可能影響耳機通透）");
        keepVoiceReady.setChecked(Prefs.keepVoiceReady(this));
        box.addView(keepVoiceReady);
        keepVoiceReady.setOnCheckedChangeListener((button,checked) -> {
            prefs.edit().putBoolean(Prefs.KEEP_VOICE_READY,checked).apply();
            if (prefs.getBoolean("enabled",false) && Prefs.permitted(this))
                startForegroundService(new Intent(this,StandbyService.class));
        });
        disable=new Button(this); disable.setText("停止待命"); box.addView(disable);
        disable.setOnClickListener(v -> {
            prefs.edit().putBoolean("enabled",false).putString("status","已停止").apply();
            stopService(new Intent(this,StandbyService.class));
        });
        if(state==null && !prefs.contains("enabled") && !prefs.getBoolean("introduced",false)){
            prefs.edit().putBoolean("introduced",true).apply();
            begin();
        }
    }
    private void begin(){
        if(!Prefs.permitted(this)){requestPermissions(Prefs.REQUIRED,1);return;}
        prefs.edit().putBoolean("enabled",true).putString("status","正在尋找已連線的耳機").apply();
        startForegroundService(new Intent(this,StandbyService.class));
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2);
    }
    private void notice(String s){new AlertDialog.Builder(this).setMessage(s).setPositiveButton("確定",null).show();}
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] results){
        super.onRequestPermissionsResult(code,p,results);
        if(code==1){if(Prefs.permitted(this)) begin(); else notice("附近裝置用於辨識耳機；電話狀態用於來電讓路。兩項權限均需要，無需麥克風或通話紀錄權限。");}
    }
    private void render() {
        boolean on=prefs.getBoolean("enabled",false);
        status.setText(getString(R.string.standby_status, on ? "自動待命已開啟" : "自動待命已關閉", prefs.getString("status",on ? "啟動中" : "已停止")));
        if(on) status.append("\n\n目前系統公開的錄音輸入（無法辨認是否屬於 Typeless）\n"+
            prefs.getString("observed","目前沒有可見的錄音"));
        prepare.setEnabled(on);
        enable.setVisibility(on ? android.view.View.GONE : android.view.View.VISIBLE);
        disable.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key){render();}
    @Override protected void onStart(){super.onStart();prefs.registerOnSharedPreferenceChangeListener(this);render();
        if(prefs.getBoolean("enabled",false) && Prefs.permitted(this)) startForegroundService(new Intent(this,StandbyService.class));
    }
    @Override protected void onResume(){super.onResume();render();}
    @Override protected void onStop(){prefs.unregisterOnSharedPreferenceChangeListener(this);super.onStop();}
}
