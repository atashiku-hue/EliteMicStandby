package tw.vic.elitemic;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.*;
import android.os.Looper;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, shadows=SystemAudioTraceTest.Recording.class)
public class SystemAudioTraceTest {
    @Implements(AudioRecordingConfiguration.class)
    public static class Recording {
        static AudioDeviceInfo input;
        static boolean silenced;
        @Implementation protected AudioDeviceInfo getAudioDevice() { return input; }
        @Implementation protected boolean isClientSilenced() { return silenced; }
    }
    private Context app;
    private AudioManager audio;
    private SharedPreferences prefs;
    @Before public void reset() {
        app = RuntimeEnvironment.getApplication();
        audio = app.getSystemService(AudioManager.class);
        prefs = Prefs.of(app);
        prefs.edit().clear().commit();
        Recording.input = null;
        Recording.silenced = false;
    }
    private static AudioDeviceInfo port(int type) {
        return AudioDeviceInfoBuilder.newBuilder().setType(type).build();
    }
    @Test public void callbackEndingClearsCurrentButKeepsLastVisibleEvidenceAcrossStop() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Prefs.REQUIRED);
        prefs.edit().putBoolean("enabled",true).apply();
        var ctl=Robolectric.buildService(StandbyService.class).create();
        try {
            ctl.get().onStartCommand(null,0,1);
            shadowOf(Looper.getMainLooper()).idle();
            var serviceAudio=ctl.get().getSystemService(AudioManager.class);
            Recording.input=port(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
            Recording.silenced=true;
            var mock=shadowOf(serviceAudio).createActiveRecordingConfiguration(37,
                    MediaRecorder.AudioSource.MIC,"example.private.app");
            shadowOf(serviceAudio).setActiveRecordingConfigurations(List.of(mock),true);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(prefs.getString("observed","").contains("傳統藍牙 SCO（系統已靜音）"));
            shadowOf(serviceAudio).setActiveRecordingConfigurations(List.of(),true);
            shadowOf(Looper.getMainLooper()).idle();
        } finally {ctl.destroy();}
        assertFalse(prefs.contains("observed"));
        assertTrue(prefs.getString("lastVisibleRecording","").contains("錄音 傳統藍牙 SCO（系統已靜音）"));
        assertTrue(prefs.getString("systemAudioHistory","").split("\n")[0].contains("沒有可見錄音"));
        assertFalse(SystemAudioTrace.report(prefs).contains("example.private.app"));
    }
    @Test public void preparationRecordsActualPhoneThenHeadsetWithoutClaimingAppIdentity() {
        SystemAudioTrace trace = new SystemAudioTrace(audio,null,prefs);
        var config=shadowOf(audio).createActiveRecordingConfiguration(37,MediaRecorder.AudioSource.MIC,"example.private.app");
        trace.beginPreparation("按準備");
        trace.confirmPreparation();
        // The SDK 34 Robolectric builder maps external types through the output table.
        Recording.input=port(AudioDeviceInfo.TYPE_BUILTIN_MIC);
        Object devicePort=org.robolectric.util.ReflectionHelpers.getField(Recording.input,"mPort");
        int internalMic=org.robolectric.util.ReflectionHelpers.getStaticField(
                org.robolectric.util.ReflectionHelpers.loadClass(getClass().getClassLoader(),"android.media.AudioSystem"),
                "DEVICE_IN_BUILTIN_MIC");
        org.robolectric.util.ReflectionHelpers.setField(devicePort,"mType",internalMic);
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC,Recording.input.getType());
        trace.recordingChanged(List.of(config));
        trace.recordingChanged(List.of()); // A second phrase can start on a different input.
        Recording.input=port(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        trace.recordingChanged(List.of(config));
        trace.recordingChanged(List.of());
        assertTrue(prefs.getAll().toString(),prefs.getString("prepFirst","").contains("手機內建麥克風"));
        assertTrue(prefs.getString("prepLatest","").contains("傳統藍牙 SCO"));
        trace.finishPreparation("測試結束");
        assertTrue(prefs.getString("prepEnd","").contains("結束"));
        assertTrue(prefs.getString("prepPhrases","").contains("第 2 段"));
        assertFalse(SystemAudioTrace.report(prefs).contains("example.private.app"));
    }
    @Test public void snapshotsDeduplicateAndStayBoundedWithoutLosingLastRecording() {
        SystemAudioTrace trace=new SystemAudioTrace(audio,null,prefs);
        Recording.input=port(AudioDeviceInfo.TYPE_USB_HEADSET);
        var config=shadowOf(audio).createActiveRecordingConfiguration(37,MediaRecorder.AudioSource.MIC,"unknown");
        trace.recordingChanged(List.of(config));
        trace.recordingChanged(List.of());
        for (int i=0;i<20;i++) {
            audio.setMicrophoneMute(i%2==0);
            trace.sample();
        }
        String value=prefs.getString("systemAudioHistory","");
        assertEquals(SystemAudioTrace.LIMIT,value.split("\n").length);
        trace.sample();
        assertEquals(value,prefs.getString("systemAudioHistory",""));
        assertTrue(prefs.getString("lastVisibleRecording","").contains("USB 耳麥"));
    }
    @Test public void classicCallAndMediaProfilesAreReportedIndependently() {
        var adapter = app.getSystemService(android.bluetooth.BluetoothManager.class).getAdapter();
        shadowOf(adapter).setProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET,
                android.bluetooth.BluetoothProfile.STATE_DISCONNECTED);
        shadowOf(adapter).setProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP,
                android.bluetooth.BluetoothProfile.STATE_CONNECTED);
        new SystemAudioTrace(audio,adapter,prefs).sample();
        assertTrue(SystemAudioTrace.report(prefs).contains("HFP 未連｜A2DP 連線"));
    }
    @Test public void savedReportIsReadableAndCopyableAfterStandbyStops() {
        prefs.edit().putBoolean("enabled",false)
                .putString("lastVisibleRecording","傳統藍牙 SCO（系統已靜音）").commit();
        try (var ctl=Robolectric.buildActivity(MainActivity.class).setup()) {
            var activity=ctl.get();
            java.util.ArrayList<android.view.View> hits=new java.util.ArrayList<>();
            activity.getWindow().getDecorView().findViewsWithText(hits,"查看系統收音紀錄",
                    android.view.View.FIND_VIEWS_WITH_TEXT);
            assertEquals(1,hits.size());
            hits.get(0).performClick();
            var dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            var clip=activity.getSystemService(android.content.ClipboardManager.class).getPrimaryClip();
            assertNotNull(clip);
            assertTrue(clip.getItemAt(0).getText().toString().contains("傳統藍牙 SCO（系統已靜音）"));
        }
    }
    @Test public void headsetProtocolsAndBuiltInHaveDistinctLabels() throws Exception {
        assertEquals("傳統藍牙 SCO",SystemAudioTrace.type(port(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)));
        assertEquals("藍牙 LE 耳麥",SystemAudioTrace.type(port(AudioDeviceInfo.TYPE_BLE_HEADSET)));
        // The API 34 Robolectric builder converts external types to output-port types.
        // Use the framework's real input-port value for a microphone-only device.
        AudioDeviceInfo mic = port(AudioDeviceInfo.TYPE_BUILTIN_MIC);
        Object micPort = org.robolectric.util.ReflectionHelpers.getField(mic,"mPort");
        int internal = org.robolectric.util.ReflectionHelpers.getStaticField(
                Class.forName("android.media.AudioSystem"),"DEVICE_IN_BUILTIN_MIC");
        org.robolectric.util.ReflectionHelpers.setField(micPort,"mType",internal);
        assertEquals("手機內建麥克風",SystemAudioTrace.type(mic));
        assertEquals("未公開",SystemAudioTrace.type(null));
    }
}
