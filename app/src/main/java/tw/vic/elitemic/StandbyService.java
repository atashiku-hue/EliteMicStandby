package tw.vic.elitemic;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import java.util.*;

public class StandbyService extends Service {
    private static final String CHANNEL="standby";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final RoutePolicy policy=new RoutePolicy();
    private AudioManager audio;
    private SystemAudioTrace trace;
    private HeadsetVoiceSession voice;
    private AudioTrack standbyTrack;
    private boolean trackPreferenceAccepted;
    private final AudioRouting.OnRoutingChangedListener playbackChanged = router -> queue();
    boolean directVoiceSupported() { return Build.VERSION.SDK_INT >= 36; }
    private TelecomManager telecom;
    private boolean requested, listening, receiverOn, devicesOn, modeOn, routeOn, recordingsOn;
    private int requestedId=-1;
    // Process-local flag for the optional hardware shortcut; never restore stale route state.
    static volatile boolean readyForShortcut;
    private String lastStatus="";
    private boolean multipleHeadsets;
    private final Runnable evaluate=this::evaluate;
    private final Runnable checkLater=this::queue;
    private void queue(){
        if(listening && !main.hasCallbacks(evaluate)) main.post(evaluate);
    }
    private void later(long delay){main.removeCallbacks(checkLater);main.postDelayed(checkLater,delay);}
    private final BroadcastReceiver events=new BroadcastReceiver(){
        @Override public void onReceive(Context ctx,Intent event){
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(event.getAction()) && voice != null
                    && event.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) bindVoice();
            if (BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(event.getAction()) && voice != null
                    && event.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_DISCONNECTED
                    && voice.disconnected(event.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE))) {
                release();
                policy.reset();
            }
            // Aggregate call state is read again in evaluate; never inspect numbers.
            queue();
            main.removeCallbacks(afterBroadcast); main.postDelayed(afterBroadcast,800);
        }
    };
    private final Runnable afterBroadcast=this::queue;
    private final AudioDeviceCallback devices=new AudioDeviceCallback(){
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] d){queue();}
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d){queue();}
    };
    private final AudioManager.OnModeChangedListener modes=m->queue();
    private final AudioManager.OnCommunicationDeviceChangedListener routes=d->queue();
    private final AudioManager.AudioRecordingCallback recordings=new AudioManager.AudioRecordingCallback(){
        @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs){
            if(listening) trace.recordingChanged(configs);
        }
    };
    @Override public void onCreate(){
        super.onCreate();
        audio=getSystemService(AudioManager.class);
        BluetoothManager manager=getSystemService(BluetoothManager.class);
        trace=new SystemAudioTrace(audio,manager==null ? null : manager.getAdapter(),Prefs.of(this));
        telecom=getSystemService(TelecomManager.class);
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"耳機待命",NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(String status){
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,2,new Intent(this,StandbyService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.headset)
            .setContentTitle("耳機聽寫待命").setContentText(status).setStyle(new Notification.BigTextStyle().bigText(status))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
    }
    private void report(String s){
        if(s.equals(lastStatus)) return;
        lastStatus=s;
        Prefs.of(this).edit().putString("status",s).apply();
        getSystemService(NotificationManager.class).notify(1,notification(s));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null && "stop".equals(intent.getAction())) Prefs.of(this).edit().putBoolean("enabled",false).putString("status","已停止").apply();
        if(!Prefs.of(this).getBoolean("enabled",false)){stopSelf();return START_NOT_STICKY;}
        if(!Prefs.permitted(this)){stopWithError(new SecurityException("permission revoked"));return START_NOT_STICKY;}
        try{
            startForeground(1,notification(lastStatus.isEmpty()?"啟動中":lastStatus));
            if(!listening) registerEvents();
            queue();return START_STICKY;
        }catch(RuntimeException e){
            stopWithError(e);return START_NOT_STICKY;
        }
    }
    private void registerEvents(){
        listening=true;
        IntentFilter f=new IntentFilter();
        for(String s:new String[]{TelephonyManager.ACTION_PHONE_STATE_CHANGED,AudioManager.ACTION_MICROPHONE_MUTE_CHANGED,BluetoothAdapter.ACTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED,BluetoothDevice.ACTION_ACL_CONNECTED,BluetoothDevice.ACTION_ACL_DISCONNECTED})
            f.addAction(s);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(events,f,Context.RECEIVER_EXPORTED); else registerReceiver(events,f);
        receiverOn=true;
        BluetoothManager bluetooth=getSystemService(BluetoothManager.class);
        voice=new HeadsetVoiceSession(bluetooth==null ? null : bluetooth.getAdapter(), this::queue, directVoiceSupported());
        bindVoice();
        audio.registerAudioDeviceCallback(devices,main);devicesOn=true;
        audio.addOnModeChangedListener(getMainExecutor(),modes);modeOn=true;
        audio.addOnCommunicationDeviceChangedListener(getMainExecutor(),routes);routeOn=true;
        Prefs.of(this).edit().remove("observed").apply();
        audio.registerAudioRecordingCallback(recordings,main);recordingsOn=true;
        try { trace.recordingChanged(audio.getActiveRecordingConfigurations()); }
        catch (RuntimeException unavailable) { trace.sample(); }
    }
    private void bindVoice() {
        try { voice.bind(this); } catch (RuntimeException unavailable) { /* Android routing stays available. */ }
    }
    private boolean callBusy(){
        if(checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("missing call-state permission");
        if(telecom!=null && telecom.isInCall()) return true;
        int mode=audio.getMode();
        return mode==AudioManager.MODE_IN_CALL || mode==AudioManager.MODE_RINGTONE ||
            mode==AudioManager.MODE_CALL_SCREENING || mode==AudioManager.MODE_IN_COMMUNICATION ||
            (Build.VERSION.SDK_INT>=33 && (mode==AudioManager.MODE_CALL_REDIRECT ||
                mode==AudioManager.MODE_COMMUNICATION_REDIRECT));
    }
    private AudioDeviceInfo target(AudioDeviceInfo current){
        List<AudioDeviceInfo> ports=new ArrayList<>();
        List<DeviceMatch.Port> choices=new ArrayList<>();
        for(AudioDeviceInfo d:audio.getAvailableCommunicationDevices()){
            if(d.getType()!=AudioDeviceInfo.TYPE_BLUETOOTH_SCO && d.getType()!=AudioDeviceInfo.TYPE_BLE_HEADSET) continue;
            ports.add(d);
            choices.add(new DeviceMatch.Port(d.getId(), d.getAddress(), String.valueOf(d.getProductName())));
        }
        int id=DeviceMatch.find(choices,current==null?-1:current.getId(),Prefs.of(this).getString("address",""));
        multipleHeadsets=id<0 && ports.size()>1;
        for(AudioDeviceInfo d:ports) if(d.getId()==id) return d;
        return null;
    }
    private void evaluate(){
        if(!listening) return;
        if(!Prefs.permitted(this)){stopWithError(new SecurityException("permission revoked"));return;}
        try{
            trace.sample();
            boolean call=callBusy();
            if (!call && hasExternalMicrophone()) {
                release();
                voice.reset();
                policy.reset();
                report("USB 或有線麥克風優先，已讓出藍牙路由");
                return;
            }
            AudioDeviceInfo current=audio.getCommunicationDevice();
            AudioDeviceInfo target=call?null:target(current);
            boolean classic = target == null ? !multipleHeadsets : target.getType()==AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
            if (call || !classic) voice.reset();
            else if (!requested || voice.ownsSession()) {
                HeadsetVoiceSession.State state;
                try { state = voice.evaluate(target); }
                catch (RuntimeException unavailable) { voice.reset(); state = HeadsetVoiceSession.State.UNAVAILABLE; }
                switch (state) {
                    case BUSY:
                        readyForShortcut = false;
                        report("其他耳機正在使用語音通道，已讓路");
                        return;
                    case CONNECTING:
                        readyForShortcut = false;
                        report("等待藍牙耳機確認語音通道");
                        later(750);
                        return;
                    case CONNECTED:
                    case EXTERNAL:
                        if (target == null) {
                            readyForShortcut = false;
                            report("耳機語音已連線，等待 Android 提供耳機通訊裝置");
                            return; // Device and route callbacks will re-evaluate.
                        }
                        if (current != null && current.getId() == target.getId()) {
                            readyForShortcut = true;
                            report("耳機語音已連線，Android 通訊裝置已選耳機 · Typeless 收音待確認");
                            return;
                        }
                        // A connected HFP link does not select the Android communication device.
                        // Continue into the same bounded route request used for other headsets.
                        break;
                    case UNAVAILABLE:
                        break;
                }
            }
            int id=target==null?-1:target.getId();
            boolean owns=requested && requestedId==id;
            switch(policy.decide(call,id,owns,current!=null && current.getId()==id,SystemClock.elapsedRealtime())){
            case RELEASE_CALL:
                release();report("系統處於通話或通訊模式，已讓出路由");later(4000);break;
            case RELEASE_ABSENT:
                release();report(multipleHeadsets?"有多個藍牙通訊裝置，等待系統指定使用中的耳機":"等待藍牙耳機通訊連線，連上即自動啟用");break;
            case ALREADY_ROUTED:
                readyForShortcut=true;
                report("系統通訊路由（備用）："+target.getProductName()+"（藍牙） · "+
                    playbackStatus(target));
                break;
            case WAIT:readyForShortcut=false;break;
            case FAILED:
                release();report("系統未確認耳機路由；已停止嘗試。重新連線或停止後再啟用。");break;
            case START:
                release();
                try {
                    try { openStandbyTrack(target); }
                    catch (IllegalStateException | IllegalArgumentException unavailable) { closeStandbyTrack(); }
                    requested=audio.setCommunicationDevice(target);
                    if(requested){requestedId=id;report("等候 Android 確認耳機路由");}
                    else {release();report("Android 拒絕路由，等候重試");}
                }catch(IllegalArgumentException | IllegalStateException e){
                    release();report("路由暫時無法使用："+e.getClass().getSimpleName());
                }
                later(RoutePolicy.SETTLE_MS+100);break;
            }
        }catch(RuntimeException e){stopWithError(e);}
    }
    private void stopWithError(RuntimeException e){
        listening=false;
        Prefs.of(this).edit().putBoolean("enabled",false).putString("status","服務已停止："+e.getClass().getSimpleName()).apply();
        stopSelf();
    }
    private boolean hasExternalMicrophone() {
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    private String playbackStatus(AudioDeviceInfo target) {
        if (standbyTrack == null) return "待命音軌不可用";
        AudioDeviceInfo actual = standbyTrack.getRoutedDevice();
        if (actual != null && actual.getId() == target.getId())
            return "系統回報待命音軌正在耳機播放 · Typeless 收音待確認";
        return trackPreferenceAccepted ? "已指定耳機播放，尚未確認實際播放裝置"
            : "系統未接受音軌指定耳機";
    }
    private void openStandbyTrack(AudioDeviceInfo target) {
        // The framework tracks playback activity by UID. Keep playback active while
        // this app owns the route, without opening any recorder or changing audio mode.
        final int frames = 16000;
        AudioFormat format = new AudioFormat.Builder().setSampleRate(frames)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
        standbyTrack = new AudioTrack.Builder().setAudioFormat(format)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(frames * 2).build();
        java.nio.ByteBuffer silence = java.nio.ByteBuffer.allocateDirect(frames * 2);
        int written = standbyTrack.write(silence, silence.capacity(), AudioTrack.WRITE_BLOCKING);
        if (written != frames * 2 || standbyTrack.setLoopPoints(0, frames, -1) != AudioTrack.SUCCESS)
            throw new IllegalStateException("靜音播放初始化失敗");
        trackPreferenceAccepted = standbyTrack.setPreferredDevice(target);
        standbyTrack.addOnRoutingChangedListener(playbackChanged, main);
        standbyTrack.play();
    }

    private void release(){
        readyForShortcut=false;
        if(requested){try{audio.clearCommunicationDevice();}catch(RuntimeException ignored){}}
        requested=false;requestedId=-1;
        closeStandbyTrack();
    }
    private void closeStandbyTrack() {
        if (standbyTrack != null) {
            standbyTrack.removeOnRoutingChangedListener(playbackChanged);
            standbyTrack.release();
            trackPreferenceAccepted = false;
            standbyTrack = null;
        }
    }
    @Override public void onDestroy(){
        listening=false;main.removeCallbacksAndMessages(null);
        if(receiverOn) unregisterReceiver(events);
        if(devicesOn) audio.unregisterAudioDeviceCallback(devices);
        if(modeOn) audio.removeOnModeChangedListener(modes);
        if(routeOn) audio.removeOnCommunicationDeviceChangedListener(routes);
        if(recordingsOn) audio.unregisterAudioRecordingCallback(recordings);
        if(voice!=null) voice.close();
        release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
