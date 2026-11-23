package tw.vic.elitemic;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Build;
import android.text.format.DateFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class SystemAudioTrace {
    static final int LIMIT = 12;
    private static final String HISTORY = "systemAudioHistory", LAST_RECORDING = "lastVisibleRecording";
    private final AudioManager audio;
    private final BluetoothAdapter bluetooth;
    private final SharedPreferences prefs;
    private String recording = "未知", previous;
    private boolean visible;

    SystemAudioTrace(AudioManager audio, BluetoothAdapter bluetooth, SharedPreferences prefs) {
        this.bluetooth = bluetooth;
        this.audio = audio;
        this.prefs = prefs;
        InputComparison.migrate(prefs);
    }

    void recordingChanged(List<AudioRecordingConfiguration> configs) {
        List<String> descriptions = new ArrayList<>();
        for (AudioRecordingConfiguration config : configs) {
            if (LevelActivity.isMeterSession(config.getClientAudioSessionId())) continue;
            String device = "未公開", mute = "靜音未知";
            try { device = type(config.getAudioDevice()); } catch (RuntimeException ignored) { }
            try { mute = config.isClientSilenced() ? "系統已靜音" : "未標示靜音"; }
            catch (RuntimeException ignored) { }
            String format="";
            try {
                format=" "+format(config.getClientFormat())+" → "+format(config.getFormat())+"（App 要求 → 系統裝置）";
            } catch (RuntimeException unavailable) { /* Some devices hide stream format. */ }
            descriptions.add(device + "（" + mute + "）"+format);
        }
        Collections.sort(descriptions);
        visible = !descriptions.isEmpty();
        recording = visible ? String.join("、", descriptions) : "沒有可見錄音";
        sample();
    }

    void sample() {
        Set<String> ports = new TreeSet<>();
        String available;
        try {
            for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_INPUTS))
                ports.add(type(device));
            available = ports.isEmpty() ? "未列出" : String.join("、", ports);
        } catch (RuntimeException unavailable) { available = "未知"; }
        String route = "未知", mode = "未知", mute = "未知";
        try { route = type(audio.getCommunicationDevice()); } catch (RuntimeException ignored) { }
        try { mode = modeName(audio.getMode()); } catch (RuntimeException ignored) { }
        try { mute = audio.isMicrophoneMute() ? "開" : "關"; } catch (RuntimeException ignored) { }
        String state = "HFP " + profileState(BluetoothProfile.HEADSET) + "｜A2DP " + profileState(BluetoothProfile.A2DP)
                + "｜模式 " + mode + "｜通訊 " + route + "｜系統麥克風靜音 " + mute
                + "｜列出的輸入 " + available + "｜錄音 " + recording;
        if (state.equals(previous)) return;
        previous = state;
        String entry = DateFormat.format("MM-dd HH:mm:ss", System.currentTimeMillis()) + "　" + state;
        String old = prefs.getString(HISTORY, "");
        String[] lines = old.isEmpty() ? new String[0] : old.split("\n");
        StringBuilder log = new StringBuilder(entry);
        for (int i=0; i<Math.min(lines.length, LIMIT-1); i++) log.append('\n').append(lines[i]);
        SharedPreferences.Editor edit = prefs.edit().putString(HISTORY,log.toString());
        if (visible) {
            InputComparison.remember(edit,entry);
            edit.putString(LAST_RECORDING, entry).putString("observed", recording);
        } else {
            edit.remove("observed");
        }
        edit.apply();
    }

    private static String format(android.media.AudioFormat f) {
        if(f==null || f.getSampleRate()<=0) return "未知";
        return f.getSampleRate()+" Hz/"+f.getChannelCount()+" 聲道";
    }
    private String profileState(int profile) {
        if (bluetooth == null) return "未知";
        try {
            switch (bluetooth.getProfileConnectionState(profile)) {
                case BluetoothAdapter.STATE_CONNECTED: return "連線";
                case BluetoothAdapter.STATE_DISCONNECTED: return "未連";
                case BluetoothAdapter.STATE_CONNECTING: return "連線中";
                case BluetoothAdapter.STATE_DISCONNECTING: return "斷線中";
                default: return "未知";
            }
        } catch (SecurityException | IllegalStateException unavailable) { return "未知"; }
    }
    static String report(SharedPreferences prefs) {
        return "耳機聽寫待命 " + BuildConfig.VERSION_NAME + " · Android " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "） · "
                + Build.MODEL + "\nHFP 為傳統藍牙通話、A2DP 為媒體，均為手機整體狀態。錄音歸屬與音量未知。\n\n最近一次可見錄音\n"
                + prefs.getString(LAST_RECORDING,"尚未觀察到") + "\n\n" + InputComparison.report(prefs) + "\n\n系統變化（新到舊）\n"
                + prefs.getString(HISTORY,"尚未觀察到");
    }

    static String type(AudioDeviceInfo device) {
        if (device == null) return "未公開";
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "傳統藍牙 SCO";
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "藍牙 LE 耳麥";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "藍牙 A2DP";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "3.5 mm 耳麥";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB 耳麥";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB 音訊裝置";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "手機內建麥克風";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "手機喇叭";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "手機聽筒";
            default: return "類型 " + device.getType();
        }
    }
    private static String modeName(int mode) {
        switch (mode) {
            case AudioManager.MODE_NORMAL: return "一般";
            case AudioManager.MODE_RINGTONE: return "響鈴";
            case AudioManager.MODE_IN_CALL: return "電話通話";
            case AudioManager.MODE_IN_COMMUNICATION: return "App 通訊";
            default: return String.valueOf(mode);
        }
    }
}
