package tw.vic.elitemic;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import android.text.format.DateFormat;
import android.widget.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import tw.vic.defaultinput.Measurement;
import tw.vic.defaultinput.RatePlan;

/** Independent foreground recorder. Does not change global or per-recorder routing. */
public final class LevelActivity extends Activity {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static volatile int meterId=-1;
    private static volatile long ignoreUntil;
    private final AtomicInteger generation=new AtomicInteger();
    private Button start;
    private TextView state;

    static boolean isMeterSession(int id) {
        return id>0 && id==meterId && SystemClock.elapsedRealtime()<ignoreUntil;
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(20*getResources().getDisplayMetrics().density);
        box.setPadding(pad,pad,pad,pad);
        state=new TextView(this);
        state.setText("先結束浮球聽寫。按開始後先安靜兩秒，再依提示說話四秒。量測使用系統預設錄音輸入，只保留裝置與音量數字。");
        box.addView(state);
        start=new Button(this); start.setText("開始六秒量測"); box.addView(start);
        start.setOnClickListener(v -> {
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1);
            else begin();
        });
        setContentView(box);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code!=1) return;
        if(grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED && !isFinishing()) begin();
        else state.setText("未授予麥克風權限；系統紀錄與日常待命仍可使用。");
    }
    private void begin() {
        if(!start.isEnabled())return;
        int token=generation.incrementAndGet();
        start.setEnabled(false); state.setText("請保持安靜，正在開啟系統預設麥克風");
        WORKER.execute(()->capture(token));
    }
    private void eligible(AudioManager audio,int self) {
        if(audio.getMode()!=AudioManager.MODE_NORMAL) throw new IllegalStateException("通話或通訊中，量測已讓路");
        for(AudioRecordingConfiguration c:audio.getActiveRecordingConfigurations())
            if(self<=0 || c.getClientAudioSessionId()!=self)
                throw new IllegalStateException("有其他可見錄音，請等聽寫或錄音結束");
    }
    private AudioRecord open(int rate) {
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("麥克風權限未授予");
        int min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0)throw new IllegalArgumentException("不支援此取樣率");
        AudioRecord r=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(Math.max(min,rate/20*8)).build();
        boolean ok=false;
        try {
            meterId=r.getAudioSessionId(); ignoreUntil=Long.MAX_VALUE;
            r.startRecording();
            if(r.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("錄音未啟動");
            ok=true; return r;
        } finally { if(!ok){r.release();ignoreUntil=SystemClock.elapsedRealtime()+2000;} }
    }
    private void capture(int token) {
        AudioManager audio=getSystemService(AudioManager.class);
        AudioRecord recorder=null;
        String result="量測未完成", group=null;
        try {
            if(generation.get()!=token)return;
            eligible(audio,-1);
            recorder=RatePlan.open(RatePlan.candidates(new int[0]),rate->{
                if(generation.get()!=token)throw new java.util.concurrent.CancellationException();
                return open(rate);
            });
            int rate=recorder.getSampleRate();
            short[] frame=new short[rate/20];
            Measurement measurement=new Measurement(rate);
            long limit=SystemClock.elapsedRealtime()+10000, nextCheck=0;
            int frames=0,used=0;
            while(frames<Measurement.TOTAL && generation.get()==token){
                long now=SystemClock.elapsedRealtime();
                if(now>=limit)throw new IllegalStateException("音訊讀取逾時");
                if(now>=nextCheck){eligible(audio,recorder.getAudioSessionId());nextCheck=now+250;}
                int n=recorder.read(frame,used,frame.length-used,AudioRecord.READ_NON_BLOCKING);
                if(n<0)throw new IllegalStateException("無法讀取樣本（"+n+"）");
                if(n==0){SystemClock.sleep(10);continue;}
                used+=n;
                if(used!=frame.length)continue;
                used=0;
                AudioDeviceInfo port=recorder.getRoutedDevice();
                AudioRecordingConfiguration config=recorder.getActiveRecordingConfiguration();
                measurement.frame(frame,port==null?-1:port.getId(),SystemAudioTrace.type(port),config!=null&&config.isClientSilenced());
                if(++frames==Measurement.QUIET)
                    runOnUiThread(()->{if(generation.get()==token)state.setText("現在對麥克風說話四秒");});
            }
            if(generation.get()!=token)return;
            group=measurement.stableType();
            result=measurement.result(-1)+"\n"+measurement.sampleSummary()+" · "+rate+" Hz";
        } catch(RuntimeException e){result="量測未完成："+e.getMessage();}
        finally {
            if(recorder!=null){try{recorder.release();}finally{ignoreUntil=SystemClock.elapsedRealtime()+2000;}}
        }
        final String outcome=result, device=group;
        runOnUiThread(()->{
            if(generation.get()!=token)return;
            String stamp=DateFormat.format("MM-dd HH:mm:ss",System.currentTimeMillis()).toString();
            var edit=Prefs.of(this).edit().putString("lastLevelAttempt",stamp+"　"+outcome);
            if(device!=null && List.of(InputComparison.TYPES).contains(device))
                edit.putString("level."+device,stamp+"　"+outcome);
            edit.apply();
            state.setText(outcome); start.setEnabled(true);
        });
    }
    @Override protected void onStop(){
        generation.incrementAndGet();
        if(!start.isEnabled())state.setText("離開畫面，量測已取消。");
        start.setEnabled(true);
        super.onStop();
    }
}
