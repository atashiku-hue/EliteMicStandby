package tw.vic.elitemic;

import android.media.*;
import android.os.Looper;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.ShadowAudioTrack;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, shadows=PlaybackRouteTest.Track.class)
public class PlaybackRouteTest {
    @Implements(AudioTrack.class)
    public static class Track extends ShadowAudioTrack {
        static AudioDeviceInfo preferred;
        static AudioDeviceInfo actual;
        @Implementation protected boolean setPreferredDevice(AudioDeviceInfo port) {
            preferred = port;
            return true;
        }
        @Implementation protected AudioDeviceInfo getRoutedDevice() { return actual; }
    }
    @Test public void selectsLePortAndSeparatesPreferenceFromActualPlayback() {
        var app=RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Prefs.REQUIRED);
        Prefs.of(app).edit().clear().putBoolean("enabled",true).commit();
        var ctl=Robolectric.buildService(StandbyService.class).create();
        try {
            var service=ctl.get();
            var audio=service.getSystemService(AudioManager.class);
            var port=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLE_HEADSET).build();
            Track.preferred=null;
            Track.actual=null;
            shadowOf(audio).setAvailableCommunicationDevices(List.of(port));
            service.onStartCommand(null,0,1);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9));
            assertSame(port,Track.preferred);
            assertTrue(Prefs.of(app).getString("status","").contains("尚未確認實際播放裝置"));
            Track.actual=port;
            service.onStartCommand(null,0,2);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(Prefs.of(app).getString("status","").contains("正在耳機播放"));
        } finally { ctl.destroy(); Track.actual = Track.preferred = null; }
    }
}
