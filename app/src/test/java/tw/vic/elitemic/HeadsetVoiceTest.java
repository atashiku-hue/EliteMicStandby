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
        Prefs.of(app).edit().clear().putBoolean("enabled",true).putBoolean(Prefs.KEEP_VOICE_READY,true).commit();
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
    @Test public void youtubePlaybackYieldsOwnedVoiceAndRouteThenResumesAfterQuiet() {
        var ctl=Robolectric.buildService(DirectVoiceCapableService.class).create();
        try {
            var service=ctl.get();
            var audio=service.getSystemService(AudioManager.class);
            var headset=output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,29);
            shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of(headset));
            service.onStartCommand(null,0,1);
            idle();
            assertTrue(profile.isAudioConnected(jabra));
            var callback=(AudioManager.AudioPlaybackCallback) ReflectionHelpers.getField(service,"mediaChanges");
            shadowOf(audio).setIsMusicActive(true);
            callback.onPlaybackConfigChanged(java.util.List.of());
            idle();
            assertFalse(profile.isAudioConnected(jabra));
            assertNull(audio.getCommunicationDevice());
            assertNull(ReflectionHelpers.getField(service,"standbyTrack"));
            shadowOf(audio).setIsMusicActive(false);
            callback.onPlaybackConfigChanged(java.util.List.of());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertFalse(profile.isAudioConnected(jabra));
            shadowOf(audio).setIsMusicActive(true);
            callback.onPlaybackConfigChanged(java.util.List.of());
            idle();
            assertFalse(profile.isAudioConnected(jabra));
            shadowOf(audio).setIsMusicActive(false);
            callback.onPlaybackConfigChanged(java.util.List.of());
            idle();
            assertTrue(profile.isAudioConnected(jabra));
            assertEquals(headset.getId(), audio.getCommunicationDevice().getId());
        } finally { ctl.destroy(); }
    }
    public static class NativeModeService extends DirectVoiceCapableService {
        boolean recording;
        @Override boolean visibleRecording() { return recording; }
    }
    @Test public void nativeModeKeepsHeadsetFreeAcrossReconnectAndReturnsItAfterCapture() {
        Prefs.of(app).edit().remove(Prefs.KEEP_VOICE_READY).commit();
        var ctl=Robolectric.buildService(NativeModeService.class).create();
        try {
            var service=ctl.get();
            var audio=service.getSystemService(AudioManager.class);
            var headset=output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,29);
            shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of(headset));
            service.onStartCommand(null,0,1);
            idle();
            assertFalse(profile.isAudioConnected(jabra));
            shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of());
            service.onStartCommand(null,0,2);
            idle();
            shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of(headset));
            service.onStartCommand(null,0,3);
            idle();
            assertNull(audio.getCommunicationDevice());
            assertFalse(profile.isAudioConnected(jabra));
            var callback=(AudioManager.AudioRecordingCallback) ReflectionHelpers.getField(service,"recordings");
            service.recording=true;
            callback.onRecordingConfigChanged(java.util.List.of());
            idle();
            assertTrue(profile.isAudioConnected(jabra));
            service.recording=false;
            callback.onRecordingConfigChanged(java.util.List.of());
            idle();
            assertNull(audio.getCommunicationDevice());
            assertFalse(profile.isAudioConnected(jabra));
        } finally {ctl.destroy();}
    }
    @Test public void normalHeadphonesByDefault() { checkIdleDefault(null); }
    @Test public void upgradeFrom054StartsInNormalMode() { checkIdleDefault(false); }
    @Test public void upgradeKeepsNormalModeWhenPreviouslyChecked() { checkIdleDefault(true); }
    private void checkIdleDefault(Boolean oldValue) {
        var prefs=Prefs.of(app);
        prefs.edit().clear().putBoolean("enabled",true).apply();
        if (oldValue != null) prefs.edit().putBoolean("nativeHeadsetWhenIdle",oldValue).apply();
        var ctl=Robolectric.buildService(NativeModeService.class).create();
        try {
            var service=ctl.get();
            var audio=service.getSystemService(AudioManager.class);
            var headset=output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,29);
            shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of(headset));
            service.onStartCommand(null,0,1);
            idle();
            assertFalse("Idle headset was switched into HFP voice",profile.isAudioConnected(jabra));
            assertNull(ReflectionHelpers.getField(service,"standbyTrack"));
            var media=(AudioManager.AudioPlaybackCallback) ReflectionHelpers.getField(service,"mediaChanges");
            for (boolean playing : new boolean[]{true,false}) {
                shadowOf(audio).setIsMusicActive(playing);
                media.onPlaybackConfigChanged(java.util.List.of());
                idle();
                assertFalse("Playing or pausing video must not start voice",profile.isAudioConnected(jabra));
            }
            var recordings=(AudioManager.AudioRecordingCallback) ReflectionHelpers.getField(service,"recordings");
            service.recording=true;
            recordings.onRecordingConfigChanged(java.util.List.of());
            idle();
            assertTrue("Recording should activate the headset", profile.isAudioConnected(jabra));
            service.recording=false;
            recordings.onRecordingConfigChanged(java.util.List.of());
            idle();
            assertFalse("Finishing dictation should release the headset",profile.isAudioConnected(jabra));
            assertNull(audio.getCommunicationDevice());
        } finally { ctl.destroy(); }
    }
    @Test public void prepareBeforeCaptureAndReleaseAfterwards() {
        checkPreparation("record");
    }
    @Test public void unusedPreparationReturnsToNormalHeadphones() {
        checkPreparation("timeout");
    }
    @Test public void mediaCancelsPreparationBeforeReady() {
        checkPreparation("media");
    }
    @Test public void selectedScoPortWithoutActualVoiceTransportNeverBecomesReady() {
        checkPreparation("missingTransport");
    }
    @Test public void inheritedScoSelectionMustNotDeclareReadyWithoutAnInput() {
        Prefs.of(app).edit().remove(Prefs.KEEP_VOICE_READY).apply();
        var ctl = Robolectric.buildService(NativeModeService.class).create();
        try {
            var service=ctl.get();
            var audio=service.getSystemService(AudioManager.class);
            var headset=output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,29);
            shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of(headset));
            service.onStartCommand(null,0,1);
            idle();
            assertTrue(audio.setCommunicationDevice(headset)); // Reported selection predates preparation.

            assertTrue(service.prepareOnce());
            idle();
            assertTrue(profile.isAudioConnected(jabra));
            assertEquals(headset.getId(),audio.getCommunicationDevice().getId());
            assertNull(Prefs.of(app).getString("prepReady",null));
            shadowOf(audio).addInputDevice(output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,37),true);
            idle();
            assertTrue("Service must own an accepted communication route", (Boolean)ReflectionHelpers.getField(service,"requested"));
            assertNotNull(Prefs.of(app).getString("prepReady",null));
            idle();
            assertNotNull(Prefs.of(app).getString("prepReady",null));
        } finally {ctl.destroy();}
    }
    @Test public void threePhrasesSevenSecondsApartKeepPreparedHeadset() { checkPhraseGaps(false); }
    @Test public void mediaImmediatelyReleasesBetweenPhrases() { checkPhraseGaps(true); }
    private void checkPhraseGaps(boolean playMedia) {
        Prefs.of(app).edit().remove(Prefs.KEEP_VOICE_READY).apply();
        var ctl = Robolectric.buildService(NativeModeService.class).create();
        try {
            var service=ctl.get();
            var audio=service.getSystemService(AudioManager.class);
            var headset=output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,29);
            shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of(headset));
            shadowOf(audio).addInputDevice(output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,37),true);
            service.onStartCommand(null,0,1);
            idle();
            assertTrue(service.prepareOnce());
            idle();
            var looper=shadowOf(Looper.getMainLooper());
            var recordings=(AudioManager.AudioRecordingCallback)ReflectionHelpers.getField(service,"recordings");
            for (int i=0;i<3;i++) {
                assertTrue("Voice must already be connected before phrase "+(i+1),profile.isAudioConnected(jabra));
                assertEquals(headset.getId(),audio.getCommunicationDevice().getId());
                service.recording=true;
                recordings.onRecordingConfigChanged(java.util.List.of());
                looper.idleFor(Duration.ofSeconds(6));
                service.recording=false;
                recordings.onRecordingConfigChanged(java.util.List.of());
                looper.idle();
                if (playMedia) {
                    shadowOf(audio).setIsMusicActive(true);
                    var media=(AudioManager.AudioPlaybackCallback)ReflectionHelpers.getField(service,"mediaChanges");
                    media.onPlaybackConfigChanged(java.util.List.of());
                    looper.idle();
                    break;
                }
                looper.idleFor(Duration.ofSeconds(i<2 ? 7 : 13));
            }
            assertFalse(profile.isAudioConnected(jabra));
            assertNull(audio.getCommunicationDevice());
        } finally {ctl.destroy();}
    }
    private void checkPreparation(String scenario) {
        Prefs.of(app).edit().remove(Prefs.KEEP_VOICE_READY).apply();
        var ctl=Robolectric.buildService(NativeModeService.class).create();
        try {
            var service=ctl.get();
            var audio=service.getSystemService(AudioManager.class);
            var headset=output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,29);
            shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of(headset));
            service.onStartCommand(null,0,1);
            idle();
            assertFalse(profile.isAudioConnected(jabra));
            shadowOf(audio).addInputDevice(output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,37),true);

            if ("missingTransport".equals(scenario)) shadowOf(profile).setVoiceRecognitionSupported(false);
            assertTrue(service.prepareOnce());
            if ("media".equals(scenario)) shadowOf(audio).setIsMusicActive(true);
            idle();
            assertEquals(!("media".equals(scenario) || "missingTransport".equals(scenario)),Prefs.of(app).contains("prepReady"));
            if ("record".equals(scenario)) {
                service.recording=true;
                var callback=(AudioManager.AudioRecordingCallback)ReflectionHelpers.getField(service,"recordings");
                callback.onRecordingConfigChanged(java.util.List.of());
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(22));
                assertTrue(profile.isAudioConnected(jabra));
                service.recording=false;
                callback.onRecordingConfigChanged(java.util.List.of());
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(13));
            } else shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(22));
            idle();
            assertFalse(profile.isAudioConnected(jabra));
            assertNull(audio.getCommunicationDevice());
        } finally { ctl.destroy(); }
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
    @Test public void headsetVoiceAloneDoesNotConfirmInput() {
        var ctl=Robolectric.buildService(DirectVoiceCapableService.class).create();
        try {
            ctl.get().onStartCommand(null,0,1);
            idle();
            assertTrue(profile.isAudioConnected(jabra));
            assertNull(Prefs.of(app).getString("prepReady",null));
        } finally {ctl.destroy();}
    }
    private static void idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3200));
    }
    @Test public void automaticPreparationSurvivesPortChurnBeforeInputIsReady() {
        Prefs.of(app).edit().remove(Prefs.KEEP_VOICE_READY).commit();
        var ctl=Robolectric.buildService(NativeModeService.class).create();
        try {
            var service=ctl.get();
            var audio=service.getSystemService(AudioManager.class);
            var first=output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,29);
            var next=output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,47);
            shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of(first));
            service.onStartCommand(null,0,1);
            app.sendBroadcast(new android.content.Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE,jabra)
                .putExtra(BluetoothProfile.EXTRA_STATE,BluetoothProfile.STATE_CONNECTED));
            var looper=shadowOf(Looper.getMainLooper());
            looper.idleFor(Duration.ofMillis(215));
            var prefs=Prefs.of(app);
            String start=prefs.getString("prepStart",null);
            assertNotNull(start);
            assertNull(prefs.getString("prepReady",null));
            shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of());
            ReflectionHelpers.callInstanceMethod(service,"evaluate");
            assertNull("Port churn must not finish the preparation",prefs.getString("prepEnd",null));
            looper.idleFor(Duration.ofMillis(350));
            shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of(next));
            shadowOf(audio).addInputDevice(output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,55),true);
            idle();
            assertEquals(start,prefs.getString("prepStart",null));
            assertNotNull(prefs.getString("prepReady",null));
            assertEquals(next.getId(),audio.getCommunicationDevice().getId());
            looper.idleFor(Duration.ofSeconds(22));
            assertTrue(prefs.getString("prepEnd","").contains("就緒後等待首句逾時"));
            assertFalse(profile.isAudioConnected(jabra));
        } finally { ctl.destroy(); }
    }

    @Test public void backgroundRadioCycleWithoutReopeningActivity() {
        Prefs.of(app).edit().remove(Prefs.KEEP_VOICE_READY).commit();
        var ctl=Robolectric.buildService(NativeModeService.class).create();
        try {
            var service=ctl.get();
            var audio=service.getSystemService(AudioManager.class);
            var headset=output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,29);
            var mic=output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,37);
            shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of(headset));
            shadowOf(audio).addInputDevice(mic,true);
            service.onStartCommand(null,0,1);
            idle();
            assertTrue(service.prepareOnce());
            idle();
            assertTrue(profile.isAudioConnected(jabra));

            app.sendBroadcast(new android.content.Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.STATE_TURNING_OFF));
            profile.stopVoiceRecognition(jabra);
            shadowOf(profile).removeDevice(jabra);
            shadowOf(audio).removeInputDevice(mic,true);
            shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of());
            app.sendBroadcast(new android.content.Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE,jabra)
                .putExtra(BluetoothProfile.EXTRA_STATE,BluetoothProfile.STATE_DISCONNECTED));
            app.sendBroadcast(new android.content.Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.STATE_OFF));
            idle();
            assertEquals(-1,(int)ReflectionHelpers.getField(service,"preparedDevice"));
            app.sendBroadcast(new android.content.Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.STATE_ON));
            shadowOf(profile).addConnectedDevice(jabra);
            app.sendBroadcast(new android.content.Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE,jabra)
                .putExtra(BluetoothProfile.EXTRA_STATE,BluetoothProfile.STATE_CONNECTED));
            idle();
            assertFalse("Cannot preselect an absent port",profile.isAudioConnected(jabra));
            shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of(headset));
            shadowOf(audio).addInputDevice(mic,true);
            idle();
            assertTrue("Warm voice route before the first recorder",profile.isAudioConnected(jabra));
            assertEquals(headset.getId(),audio.getCommunicationDevice().getId());
            assertEquals("藍牙重連自動準備",Prefs.of(app).getString("prepTrigger",""));
            assertNotNull(Prefs.of(app).getString("prepReady",null));
            var callback=(AudioManager.AudioRecordingCallback)ReflectionHelpers.getField(service,"recordings");
            service.recording=true;
            assertTrue("First recorder sees an already connected voice channel",profile.isAudioConnected(jabra));
            callback.onRecordingConfigChanged(java.util.List.of());
            idle();
            assertTrue("Background service can react after recorder starts",profile.isAudioConnected(jabra));
            service.recording=false;
            callback.onRecordingConfigChanged(java.util.List.of());
            idle();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(13));
            assertFalse("Idle must return after the phrase",profile.isAudioConnected(jabra));
            app.sendBroadcast(new android.content.Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE,jabra)
                .putExtra(BluetoothProfile.EXTRA_STATE,BluetoothProfile.STATE_CONNECTED));
            idle();
            assertFalse("Duplicate connection event must not rearm voice",profile.isAudioConnected(jabra));

        } finally { ctl.destroy(); }
    }
}
