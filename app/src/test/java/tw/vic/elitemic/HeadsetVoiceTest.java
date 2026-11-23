package tw.vic.elitemic;

import android.bluetooth.*;
import android.content.Context;
import android.media.*;
import android.os.Looper;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class HeadsetVoiceTest {
    private Context app;
    private BluetoothAdapter adapter;
    private BluetoothDevice jabra;
    private BluetoothHeadset profile;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Prefs.REQUIRED);
        Prefs.of(app).edit().clear().putBoolean("enabled",true).commit();
        adapter = app.getSystemService(BluetoothManager.class).getAdapter();
        jabra = adapter.getRemoteDevice("12:34:56:78:90:AB");
        profile = Shadow.newInstanceOf(BluetoothHeadset.class);
        shadowOf(profile).addConnectedDevice(jabra);
        shadowOf(profile).setVoiceRecognitionSupported(true);
        shadowOf(adapter).setProfileProxy(BluetoothProfile.HEADSET,profile);
    }
    private HeadsetVoiceSession session() {
        HeadsetVoiceSession s = new HeadsetVoiceSession(adapter, () -> {}, true);
        s.onServiceConnected(BluetoothProfile.HEADSET,profile);
        return s;
    }
    @Test public void activeExternalAudioIsNeverStopped() {
        assertTrue(profile.startVoiceRecognition(jabra));
        HeadsetVoiceSession s=session();
        assertEquals(HeadsetVoiceSession.State.EXTERNAL,s.evaluate(null));
        s.reset();
        assertTrue(profile.isAudioConnected(jabra));
        s.close();
    }
    @Test public void twoHeadsetsWithoutMatchingRouteDoNotStartAudio() {
        shadowOf(profile).addConnectedDevice(adapter.getRemoteDevice("12:34:56:78:90:AC"));
        HeadsetVoiceSession s=session();
        assertEquals(HeadsetVoiceSession.State.UNAVAILABLE,s.evaluate(null));
        assertFalse(profile.isAudioConnected(jabra));
        s.close();
    }
    @Test public void secondActiveHeadsetDoesNotGetPreempted() {
        BluetoothDevice other = adapter.getRemoteDevice("12:34:56:78:90:AC");
        shadowOf(profile).addConnectedDevice(other);
        assertTrue(profile.startVoiceRecognition(other));
        HeadsetVoiceSession s=session();
        assertEquals(HeadsetVoiceSession.State.BUSY,s.evaluate(null));
        assertTrue(profile.isAudioConnected(other));
        assertFalse(profile.isAudioConnected(jabra));
        s.close();
    }
    @Test public void acceptedButUnconfirmedRequestTimesOutWithoutRepeatedStarts() {
        HeadsetVoiceSession s=session();
        assertEquals(HeadsetVoiceSession.State.CONNECTING,s.evaluate(null));
        assertTrue(profile.stopVoiceRecognition(jabra)); // Simulate transport failing before confirmation.
        assertEquals(HeadsetVoiceSession.State.CONNECTING,s.evaluate(null));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(6100));
        assertEquals(HeadsetVoiceSession.State.UNAVAILABLE,s.evaluate(null));
        assertEquals(HeadsetVoiceSession.State.UNAVAILABLE,s.evaluate(null));
        assertFalse(profile.isAudioConnected(jabra));
        s.close();
    }
    @Test public void confirmedAudioLossCanReopenOnceWithoutDisconnectingProfile() {
        var s=session();
        assertEquals(HeadsetVoiceSession.State.CONNECTING,s.evaluate(null));
        assertEquals(HeadsetVoiceSession.State.CONNECTED,s.evaluate(null));
        assertTrue(profile.stopVoiceRecognition(jabra)); // SCO stops; HFP device remains connected.
        assertEquals(HeadsetVoiceSession.State.CONNECTING,s.evaluate(null));
        assertEquals(HeadsetVoiceSession.State.CONNECTED,s.evaluate(null));
        assertTrue(profile.stopVoiceRecognition(jabra));
        assertEquals(HeadsetVoiceSession.State.UNAVAILABLE,s.evaluate(null));
        assertEquals(HeadsetVoiceSession.State.UNAVAILABLE,s.evaluate(null));
        s.close();
    }

    @Test public void sameHeadsetReconnectAfterObservedDisconnectCanStartAgain() {
        var s=session();
        assertEquals(HeadsetVoiceSession.State.CONNECTING,s.evaluate(null));
        assertEquals(HeadsetVoiceSession.State.CONNECTED,s.evaluate(null));
        assertTrue(profile.stopVoiceRecognition(jabra));
        shadowOf(profile).removeDevice(jabra);
        assertEquals(HeadsetVoiceSession.State.UNAVAILABLE,s.evaluate(null));
        shadowOf(profile).addConnectedDevice(jabra);
        assertEquals(HeadsetVoiceSession.State.CONNECTING,s.evaluate(null));
        assertEquals(HeadsetVoiceSession.State.CONNECTED,s.evaluate(null));
        s.close();
    }
    @Test public void rapidProfileReconnectClearsOldAttemptWithoutStoppingExternalAudio() {
        var s=session();
        assertEquals(HeadsetVoiceSession.State.CONNECTING,s.evaluate(null));
        assertEquals(HeadsetVoiceSession.State.CONNECTED,s.evaluate(null));
        assertFalse(s.disconnected(adapter.getRemoteDevice("12:34:56:78:90:AC")));
        assertTrue(s.disconnected(jabra));
        assertEquals(HeadsetVoiceSession.State.EXTERNAL,s.evaluate(null));
        s.close();
        assertTrue(profile.isAudioConnected(jabra));
    }
    @Test public void stableRecoveredSessionCanRecoverOnALaterWearCycle() {
        var s=session();
        for(int i=0;i<3;i++){
            assertEquals(HeadsetVoiceSession.State.CONNECTING,s.evaluate(null));
            assertEquals(HeadsetVoiceSession.State.CONNECTED,s.evaluate(null));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
            assertTrue(profile.stopVoiceRecognition(jabra));
        }
        s.close();
    }
    @Test public void serviceRecoversVoiceEvenWhenItAlreadyRequestedAndroidRoute() {
        var ctl=Robolectric.buildService(DirectVoiceCapableService.class).create();
        try {
            var service=ctl.get();
            var audio=service.getSystemService(AudioManager.class);
            var phone=output(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,11);
            var headset=output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,29);
            shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of(phone,headset));
            assertTrue(audio.setCommunicationDevice(phone));
            service.onStartCommand(null,0,1);
            idle();
            assertTrue((Boolean)ReflectionHelpers.getField(service,"requested"));
            assertTrue(profile.stopVoiceRecognition(jabra));
            service.onStartCommand(null,0,2);
            idle();
            assertTrue(profile.isAudioConnected(jabra));
            assertEquals(headset.getId(),audio.getCommunicationDevice().getId());
        } finally {ctl.destroy();}
    }
    @Test public void unsupportedHeadsetUsesFallback() {
        shadowOf(profile).setVoiceRecognitionSupported(false);
        HeadsetVoiceSession s=session();
        assertEquals(HeadsetVoiceSession.State.UNAVAILABLE,s.evaluate(null));
        assertFalse(profile.isAudioConnected(jabra));
        s.close();
    }
    public static class DirectVoiceCapableService extends StandbyService {
        @Override boolean directVoiceSupported() { return true; }
    }
    @Test public void pre16NeverInitiatesHeadsetCommandEvenWhenHeadsetSupportsIt() {
        var s=new HeadsetVoiceSession(adapter, () -> {}, false);
        s.onServiceConnected(BluetoothProfile.HEADSET,profile);
        assertEquals(HeadsetVoiceSession.State.UNAVAILABLE,s.evaluate(null));
        assertFalse(profile.isAudioConnected(jabra));
        s.close();
    }
    @Test public void usbAndWiredAndCallYieldOwnedVoiceThenResume() {
        var ctl=Robolectric.buildService(DirectVoiceCapableService.class).create();
        try {
            StandbyService service=ctl.get();
            AudioManager audio=service.getSystemService(AudioManager.class);
            service.onStartCommand(null,0,1);
            idle();
            assertTrue(Prefs.of(app).getString("status",""),profile.isAudioConnected(jabra));
            assertNull(ReflectionHelpers.getField(service,"standbyTrack"));
            for (int type : new int[]{AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADSET}) {
                AudioDeviceInfo input=AudioDeviceInfoBuilder.newBuilder().setType(type).build();
                shadowOf(audio).addInputDevice(input,true);
                idle();
                assertFalse(profile.isAudioConnected(jabra));
                shadowOf(audio).removeInputDevice(input,true);
                idle();
                assertTrue(profile.isAudioConnected(jabra));
            }
            audio.setMode(AudioManager.MODE_IN_CALL);
            idle();
            assertFalse(profile.isAudioConnected(jabra));
            audio.setMode(AudioManager.MODE_NORMAL);
            idle();
            assertTrue(profile.isAudioConnected(jabra));
        } finally {ctl.destroy();}
        assertFalse(profile.isAudioConnected(jabra));
    }
    private static AudioDeviceInfo output(int type, int id) {
        AudioDeviceInfo info = AudioDeviceInfoBuilder.newBuilder().setType(type).build();
        Object port = ReflectionHelpers.getField(info, "mPort");
        Object handle = ReflectionHelpers.getField(port, "mHandle");
        ReflectionHelpers.setField(handle, "mId", id);
        assertEquals(id, info.getId());
        return info;
    }
    private void checkPhoneRouteGetsSelected(boolean ownVoice) {
        var ctl = ownVoice ? Robolectric.buildService(DirectVoiceCapableService.class).create()
                : Robolectric.buildService(StandbyService.class).create();
        try {
            var service = ctl.get();
            var audio = service.getSystemService(AudioManager.class);
            var speaker = output(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, 11);
            var headset = output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 29);
            shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of(speaker, headset));
            assertTrue(audio.setCommunicationDevice(speaker));
            if (!ownVoice) assertTrue(profile.startVoiceRecognition(jabra));
            service.onStartCommand(null,0,1);
            idle();
            assertTrue(profile.isAudioConnected(jabra));
            assertEquals("Headset voice connected while Android still points at the phone",
                    headset.getId(), audio.getCommunicationDevice().getId());
        } finally { ctl.destroy(); }
        assertEquals(!ownVoice, profile.isAudioConnected(jabra));
    }
    @Test public void externallyConnectedVoiceAlsoSelectsAndroidHeadsetRoute() { checkPhoneRouteGetsSelected(false); }
    @Test public void ownVoiceAlsoSelectsAndroidHeadsetRoute() { checkPhoneRouteGetsSelected(true); }
    @Test public void headsetVoiceAloneDoesNotDeclareShortcutReady() {
        var ctl=Robolectric.buildService(DirectVoiceCapableService.class).create();
        try {
            ctl.get().onStartCommand(null,0,1);
            idle();
            assertTrue(profile.isAudioConnected(jabra));
            assertFalse("No Android communication port is available", StandbyService.readyForShortcut);
        } finally {ctl.destroy();}
    }
    private static void idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3200));
    }
}
