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
    static final String PREPARE = "prepareOnce";
    private static final long PREPARE_MS = 20000;
    private static final long BETWEEN_PHRASES_MS = 12000;
    private int preparedDevice = -1;
    private boolean preparationConfirmed, preparedRecording, betweenPhrases;
    private BluetoothDevice automaticHeadset;
    private final Runnable expirePreparation = () -> {
        cancelPreparation(betweenPhrases ? "句間等待結束" : preparationConfirmed ? "就緒後等待首句逾時" : "等待藍牙就緒逾時");
        queue();
    };

    private void cancelPreparation(String reason) {
        clearPreparation();
        if (trace != null) trace.finishPreparation(reason);
    }
    private void clearPreparation() {
        main.removeCallbacks(expirePreparation);
        preparedDevice = -1;
        automaticHeadset = null;
        preparedRecording = betweenPhrases = false;
        preparationConfirmed = false;
    }
    private BluetoothDevice connectedHeadset;
    private long reconnectDeadline;

    // Consume a connection edge once. Android can publish its audio ports later.
    private void checkReconnect(boolean call) {
        if (reconnectDeadline == 0) return;
        if (SystemClock.elapsedRealtime() >= reconnectDeadline || call || hasExternalMicrophone()
                || playingMedia() || visibleRecording() || preparedDevice >= 0 || Prefs.keepVoiceReady(this)) {
            reconnectDeadline = 0;
            return;
        }
        AudioDeviceInfo port = target(audio.getCommunicationDevice());
        if (port == null || port.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || !voice.matchesConnectedHeadset(connectedHeadset, port)) {
            later(350);
            return;
        }
        reconnectDeadline = 0;
        beginPreparation(port, "藍牙重連自動準備");
        automaticHeadset = connectedHeadset;
    }

    boolean prepareOnce() {
        if (!listening || callBusy() || hasExternalMicrophone() || playingMedia() || visibleRecording()) return false;
        AudioDeviceInfo target = target(audio.getCommunicationDevice());
        if (target == null) return false;
        reconnectDeadline = 0;
        beginPreparation(target, "按準備");
        return true;
    }
    private void beginPreparation(AudioDeviceInfo target, String trigger) {
        automaticHeadset = null;
        if (preparedDevice != target.getId()) cancelPreparation("新的準備取代前一次");
        preparedDevice = target.getId();
        lastRecordingSeen = -1;
        preparedRecording = betweenPhrases = false;
        preparationConfirmed = false;
        trace.beginPreparation(trigger);
        main.removeCallbacks(expirePreparation);
        main.postDelayed(expirePreparation, PREPARE_MS);
        report("正在為本次聽寫連接耳麥，請等就緒提示");
        queue();
    }
    private boolean hasMatchingInput(AudioDeviceInfo target) {
        String wanted = target.getAddress();
        int candidates = 0;
        boolean addressHidden = false;
        for (AudioDeviceInfo input : audio.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (input.getType() != target.getType()) continue;
            candidates++;
            String actual = input.getAddress();
            if (knownAddress(wanted) && knownAddress(actual) && wanted.equalsIgnoreCase(actual)) return true;
            if (!knownAddress(wanted) || !knownAddress(actual)) addressHidden = true;
        }
        if (candidates != 1 || !addressHidden) return false;
        int outputs = 0;
        for (AudioDeviceInfo port : audio.getAvailableCommunicationDevices())
            if (port.getType() == target.getType()) outputs++;
        return outputs == 1;
    }
    private static boolean knownAddress(String address) {
        return address != null && !address.isEmpty() && !"02:00:00:00:00:00".equals(address)
                && !"00:00:00:00:00:00".equals(address);
    }
    private void prepared(AudioDeviceInfo target) {
        if (preparedDevice != target.getId()) return;
        if (target.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && !voice.audioConnected(target)) {
            report("等待耳機實際接通語音通道");
            later(350);
            return;
        }
        if (!hasMatchingInput(target)) {
            report("通話聲道已連上，等待 Android 列出相符的藍牙麥克風");
            later(350);
            return;
        }
        if (!preparationConfirmed) {
            preparationConfirmed = true;
            trace.confirmPreparation();
            main.removeCallbacks(expirePreparation);
            main.postDelayed(expirePreparation, PREPARE_MS);
        }
        if (betweenPhrases) report("可直接點浮球繼續聽寫，停頓 12 秒後恢復一般耳機模式");
        else if (preparedRecording) report("本次準備中已觀察到錄音，實際來源可查系統收音紀錄");
        else report("藍牙輸入已列出，可以點浮球；實際錄音來源待確認");
    }
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
            String action = event.getAction();
            int radioState = event.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1);
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)
                    && (radioState == BluetoothAdapter.STATE_OFF || radioState == BluetoothAdapter.STATE_TURNING_OFF)) {
                connectedHeadset = null;
                reconnectDeadline = 0;
                cancelPreparation("手機藍牙正在關閉");
            }
            if (BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = event.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state = event.getIntExtra(BluetoothProfile.EXTRA_STATE,-1);
                if (device != null && state == BluetoothProfile.STATE_CONNECTED && !device.equals(connectedHeadset)) {
                    connectedHeadset = device;
                    reconnectDeadline = SystemClock.elapsedRealtime() + PREPARE_MS;
                } else if (state == BluetoothProfile.STATE_DISCONNECTED && (device == null || device.equals(connectedHeadset))) {
                    connectedHeadset = null;
                    reconnectDeadline = 0;
                }
            }
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(event.getAction()) && voice != null
                    && event.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) bindVoice();
            if (BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(event.getAction()) &&
                    event.getIntExtra(BluetoothProfile.EXTRA_STATE,-1) == BluetoothProfile.STATE_DISCONNECTED
                    && (automaticHeadset == null || event.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) == null
                            || automaticHeadset.equals(event.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE))))
                cancelPreparation("耳機通話連線中斷");
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
    // Playback callbacks also fire for the silent voice standby track. Only media
    // and games should displace dictation. Do not request audio focus or inspect player identities.
    private boolean playbackRegistered, yieldingToMedia;
    private long mediaQuietSince = -1;
    private static final long MEDIA_RESUME_DELAY_MS = 2500;
    private final AudioManager.AudioPlaybackCallback mediaChanges = new AudioManager.AudioPlaybackCallback() {
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) { queue(); }
    };
    private boolean playingMedia() {
        if (audio.isMusicActive()) return true;
        for (AudioPlaybackConfiguration player : audio.getActivePlaybackConfigurations()) {
            int usage = player.getAudioAttributes().getUsage();
            if (usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME) return true;
        }
        return false;
    }
    private boolean shouldYieldToMedia() {
        if (playingMedia()) {
            yieldingToMedia = true;
            mediaQuietSince = -1;
            later(2000);
            return true;
        }
        if (!yieldingToMedia) return false;
        long now = SystemClock.elapsedRealtime();
        if (mediaQuietSince < 0) mediaQuietSince = now;
        long remaining = MEDIA_RESUME_DELAY_MS - (now - mediaQuietSince);
        if (remaining > 0) {
            later(remaining);
            return true;
        }
        yieldingToMedia = false;
        mediaQuietSince = -1;
        return false;
    }
    private long lastRecordingSeen = -1;
    private static final long RECORDING_TAIL_MS = 1500;
    boolean visibleRecording() { return !audio.getActiveRecordingConfigurations().isEmpty(); }

    private boolean nativeIdle() {
        if (preparedDevice >= 0) {
            boolean recording = visibleRecording();
            if (!preparationConfirmed && recording) {
                // Recording beat readiness.
                cancelPreparation("錄音早於藍牙就緒");
            } else {
                if (recording != preparedRecording) {
                    preparedRecording = recording;
                    betweenPhrases = !recording;
                    main.removeCallbacks(expirePreparation);
                    if (betweenPhrases) main.postDelayed(expirePreparation, BETWEEN_PHRASES_MS);
                }
                return false;
            }
        }
        if (Prefs.keepVoiceReady(this)) {
            lastRecordingSeen = -1;
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (visibleRecording()) {
            lastRecordingSeen = now;
            return false;
        }
        if (lastRecordingSeen >= 0) {
            long remaining = RECORDING_TAIL_MS - (now - lastRecordingSeen);
            if (remaining > 0) {
                main.removeCallbacks(afterRecording);
                main.postDelayed(afterRecording, remaining);
                return false;
            }
            lastRecordingSeen = -1;
        }
        return true;
    }
    private final Runnable afterRecording = this::queue;
    private final AudioManager.OnModeChangedListener modes=m->queue();
    private final AudioManager.OnCommunicationDeviceChangedListener routes=d->queue();
    private final AudioManager.AudioRecordingCallback recordings=new AudioManager.AudioRecordingCallback(){
        @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs){
            if(listening) {
                trace.recordingChanged(configs);
                if (!Prefs.keepVoiceReady(StandbyService.this)) queue();
            }
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
            .addAction(new Notification.Action.Builder(null,"準備本次聽寫",PendingIntent.getService(this,3,
                new Intent(this,StandbyService.class).setAction(PREPARE),PendingIntent.FLAG_IMMUTABLE)).build())
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
            if(intent!=null && PREPARE.equals(intent.getAction()) && !prepareOnce())
                android.widget.Toast.makeText(this,"請先暫停媒體、結束錄音並連上藍牙耳機",android.widget.Toast.LENGTH_SHORT).show();
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
        audio.registerAudioPlaybackCallback(mediaChanges,main);playbackRegistered=true;
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
            checkReconnect(call);
            if (preparedDevice >= 0) {
                AudioDeviceInfo candidate = target(audio.getCommunicationDevice());
                if (call) cancelPreparation("通話或通訊模式優先");
                else if (hasExternalMicrophone()) cancelPreparation("USB 或有線麥克風優先");
                else if (playingMedia()) cancelPreparation("系統仍偵測到媒體播放");
                else if (candidate == null || candidate.getId() != preparedDevice) {
                    // During HFP negotiation the same headset may disappear or return under another audio port.
                    // Keep the original preparation deadline. Explicit disconnection still cancels immediately.
                    if (automaticHeadset != null && !preparationConfirmed && !visibleRecording()) {
                        if (candidate == null) {
                            if (requested) release();
                            policy.reset();
                            report("重連中，等待 Android 重新列出耳機通訊裝置");
                            later(350);
                            return;
                        }
                        if (candidate.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                                && voice.matchesConnectedHeadset(automaticHeadset,candidate)) {
                            release();
                            policy.reset();
                            preparedDevice = candidate.getId();
                        } else cancelPreparation("通訊裝置已更換或無法確認同一副耳機");
                    } else cancelPreparation("通訊裝置消失或已更換");
                }
            }
            if (!call && hasExternalMicrophone()) {
                release();
                voice.reset();
                policy.reset();
                report("USB 或有線麥克風優先，已讓出藍牙路由");
                return;
            }
            if (!call && nativeIdle()) {
                release();
                voice.reset();
                policy.reset();
                report("日常模式：已讓出語音通道，可使用原生通透與媒體；等待系統出現錄音");
                return;
            }
            if (!call && shouldYieldToMedia()) {
                release();
                voice.reset();
                policy.reset();
                report("媒體播放優先，已讓出耳機語音通道；播放暫停後恢復聽寫待命");
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
                        report("其他耳機正在使用語音通道，已讓路");
                        return;
                    case CONNECTING:
                        report("等待藍牙耳機確認語音通道");
                        later(750);
                        return;
                    case CONNECTED:
                    case EXTERNAL:
                        if (target == null) {
                            report("耳機語音已連線，等待 Android 提供耳機通訊裝置");
                            return; // Device and route callbacks will re-evaluate.
                        }
                        // A system-selected SCO output need not belong to this service.
                        // Pin the route explicitly for a prepared dictation, even if it already looks selected.
                        if (current != null && current.getId() == target.getId()
                                && (preparedDevice < 0 || (requested && requestedId == target.getId()))) {
                            if (preparedDevice >= 0) prepared(target);
                            else report("耳機語音已連線，Android 通訊裝置已選耳機 · Typeless 收音待確認");
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
                if (preparedDevice >= 0) { prepared(target); break; }
                report("系統通訊路由（備用）："+target.getProductName()+"（藍牙） · "+
                    playbackStatus(target));
                break;
            case WAIT:break;
            case FAILED:
                release();report("系統未確認耳機路由；已停止嘗試。重新連線或停止後再啟用。");break;
            case START:
                release();
                try {
                    try { openStandbyTrack(target); }
                    catch (IllegalStateException | IllegalArgumentException unavailable) { closeStandbyTrack(); }
                    requested=audio.setCommunicationDevice(target);
                    if(requested){
                        requestedId=id;
                        report("等候 Android 確認耳機路由");
                        if (preparedDevice >= 0) queue();
                    }
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
        cancelPreparation("待命服務已停止");
        listening=false;main.removeCallbacksAndMessages(null);
        if(receiverOn) unregisterReceiver(events);
        if(playbackRegistered) audio.unregisterAudioPlaybackCallback(mediaChanges);
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
