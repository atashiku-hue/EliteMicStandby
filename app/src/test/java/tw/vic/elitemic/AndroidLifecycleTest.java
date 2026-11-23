package tw.vic.elitemic;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class AndroidLifecycleTest {
    Context app;
    SharedPreferences prefs;
    @Before public void reset(){
        app=RuntimeEnvironment.getApplication();
        prefs=Prefs.of(app);
        prefs.edit().clear().commit();
    }
    @Test public void launcherOpensWithoutRuntimePermissions(){
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()){
            assertNotNull(controller.get().getWindow().getDecorView());
            assertFalse(prefs.getBoolean("enabled",false));
        }
    }
    @Test public void stoppedServiceNeverStartsAudio(){
        var ctl=Robolectric.buildService(StandbyService.class).create();
        try { assertEquals(Service.START_NOT_STICKY,ctl.get().onStartCommand(null,0,1)); }
        finally {ctl.destroy();}
    }
    @Test public void restartWithRevokedPermissionFailsClosed(){
        prefs.edit().putBoolean("enabled",true).commit();
        var ctl=Robolectric.buildService(StandbyService.class).create();
        try {
            assertEquals(Service.START_NOT_STICKY,ctl.get().onStartCommand(null,0,1));
            assertFalse(prefs.getBoolean("enabled",true));
        } finally { ctl.destroy(); }
    }
    @Test public void enabledDisconnectedServiceWaitsAndStopActionPersists(){
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Prefs.REQUIRED);
        prefs.edit().putBoolean("enabled",true).putString("address","12:34:56:78:9A:BC").commit();
        var ctl=Robolectric.buildService(StandbyService.class).create();
        try {
            assertEquals(Service.START_STICKY,ctl.get().onStartCommand(null,0,1));
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3));
            assertEquals("等待藍牙耳機通訊連線，連上即自動啟用",prefs.getString("status",""));
            assertEquals(Service.START_NOT_STICKY,ctl.get().onStartCommand(new Intent("stop"),0,2));
            assertFalse(prefs.getBoolean("enabled",true));
        } finally { ctl.destroy(); }
    }
    @Test public void connectedHeadsetRequestsRouteWithoutEnteringCallMode(){
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Prefs.REQUIRED);
        prefs.edit().putBoolean("enabled",true).commit();
        var ctl=Robolectric.buildService(StandbyService.class).create();
        var audio=ctl.get().getSystemService(android.media.AudioManager.class);
        var port=org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder()
            .setType(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build();
        shadowOf(audio).setAvailableCommunicationDevices(java.util.List.of(port));
        try {
            ctl.get().onStartCommand(null,0,1);
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3));
            assertSame(prefs.getString("status",""),port,audio.getCommunicationDevice());
            android.media.AudioTrack track = org.robolectric.util.ReflectionHelpers.getField(ctl.get(),"standbyTrack");
            assertNotNull("standby track should be open",track);
            assertFalse(ctl.get().directVoiceSupported());
            assertEquals(android.media.AudioTrack.PLAYSTATE_PLAYING,track.getPlayState());
            var usb=org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder()
                .setType(android.media.AudioDeviceInfo.TYPE_USB_HEADSET).build();
            shadowOf(audio).addInputDevice(usb,true);
            shadowOf(Looper.getMainLooper()).idle();
            assertNull(audio.getCommunicationDevice());
            assertTrue(prefs.getString("status","").contains("有線麥克風優先"));
            assertEquals(android.media.AudioTrack.STATE_UNINITIALIZED,track.getState());
            shadowOf(audio).removeInputDevice(usb,true);
            shadowOf(Looper.getMainLooper()).idle();
            assertSame(prefs.getString("status",""),port,audio.getCommunicationDevice());
            assertEquals(android.media.AudioManager.MODE_NORMAL,audio.getMode());
            audio.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION);
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4));
            assertNull(audio.getCommunicationDevice());
            assertEquals(android.media.AudioManager.MODE_IN_COMMUNICATION,audio.getMode());
            audio.setMode(android.media.AudioManager.MODE_NORMAL);
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4));
            assertSame(prefs.getString("status",""),port,audio.getCommunicationDevice());
        } finally { ctl.destroy(); }
    }
    @Test public void firstLaunchWithGrantsStartsWithoutHeadsetPicker(){
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Prefs.REQUIRED);
        try(var ctl=Robolectric.buildActivity(MainActivity.class).setup()){
            assertTrue(prefs.getBoolean("enabled",false));
            assertNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
        }
    }
    @Test public void disabledBootDoesNotLaunchService(){
        new Boot().onReceive(app,new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }
}
