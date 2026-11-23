package tw.vic.elitemic;

import android.media.*;
import android.os.Looper;
import android.view.View;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class LevelCaptureTest {
    private static void start(LevelActivity a){
        ArrayList<View> views=new ArrayList<>();
        a.getWindow().getDecorView().findViewsWithText(views,"開始六秒量測",View.FIND_VIEWS_WITH_TEXT);
        assertEquals(1,views.size()); views.get(0).performClick();
    }
    private static String awaitResult(LevelActivity a) throws Exception {
        var prefs=Prefs.of(a);
        for(int tries=0;tries<500 && !prefs.contains("lastLevelAttempt");tries++){
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        return prefs.getString("lastLevelAttempt","測試未完成");
    }
    @Test public void communicationModePreventsOpeningRecorder() throws Exception {
        var app=RuntimeEnvironment.getApplication();
        Prefs.of(app).edit().clear().commit();
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO);
        try(var ctl=Robolectric.buildActivity(LevelActivity.class).setup()){
            var audio=ctl.get().getSystemService(AudioManager.class);
            audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
            start(ctl.get());
            assertTrue(awaitResult(ctl.get()).contains("量測已讓路"));
        }
    }
    @Test public void existingRecordingPreventsOpeningAnotherRecorder() throws Exception {
        var app=RuntimeEnvironment.getApplication();
        Prefs.of(app).edit().clear().commit();
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO);
        try(var ctl=Robolectric.buildActivity(LevelActivity.class).setup()){
            var audio=shadowOf(ctl.get().getSystemService(AudioManager.class));
            var config=audio.createActiveRecordingConfiguration(90,MediaRecorder.AudioSource.MIC,"another.app");
            audio.setActiveRecordingConfigurations(List.of(config),false);
            start(ctl.get());
            assertTrue(awaitResult(ctl.get()).contains("有其他可見錄音"));
            assertFalse(Prefs.of(app).contains("level.USB 耳麥"));
        }
    }
}
