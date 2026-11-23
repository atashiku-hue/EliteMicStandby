package tw.vic.elitemic;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.media.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.*;
import tw.vic.defaultinput.Measurement;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class InputComparisonTest {
    private String row(String time,String profiles,String route,String available,String capture) {
        return "11-23 "+time+"　"+profiles+"｜模式 一般｜通訊 "+route+"｜系統麥克風靜音 關｜列出的輸入 "+available+"｜錄音 "+capture;
    }
    private String wired="3.5 mm 耳麥", sco="傳統藍牙 SCO", usb="USB 耳麥";
    private String off="HFP 未連｜A2DP 未連", on="HFP 連線｜A2DP 連線", mic="、手機內建麥克風";
    @Test public void upgradesOldTraceAndKeepsTwoInputsDespiteLaterIdleAndReconnect() {
        var prefs=Prefs.of(RuntimeEnvironment.getApplication());
        prefs.edit().clear().putString("systemAudioHistory",String.join("\n",
            row("14:54:47","HFP 連線中｜A2DP 連線中",wired,wired+mic,"沒有可見錄音"),
            row("14:54:19",off,wired,wired+mic,"沒有可見錄音"),
            row("14:54:10",off,wired,wired+mic,wired+"（未標示靜音）"),
            row("14:52:02",on,sco,sco+mic,sco+"（未標示靜音）"),
            row("14:51:28",on,sco,sco+mic,sco+"（未標示靜音）"))).commit();
        InputComparison.migrate(prefs);
        assertTrue(prefs.getString("routeGroup."+sco,"").contains("14:52:02"));
        assertTrue(prefs.getString("routeGroup."+wired,"").contains("14:54:10"));
        assertFalse(InputComparison.report(prefs).contains("14:54:47"));
    }
    @Test public void availableScoDoesNotGetCreditForCaptureOnUlanzi() {
        var matches=new LinkedHashMap<String,String>();
        InputComparison.observe(row("15:00",on,sco,usb+"、"+sco+mic,usb+"（未標示靜音）"),matches);
        assertTrue(matches.containsKey(usb)); assertFalse(matches.containsKey(sco));
        assertTrue(matches.containsKey("多個外接輸入同時列出"));
        assertTrue(matches.containsKey("藍牙已連且列出有線／USB 輸入"));
    }
    @Test public void mixedConnectionCanBeEvidencedEvenIfScoInputIsNotListed() {
        var matches=new LinkedHashMap<String,String>();
        InputComparison.observe(row("15:00",on,usb,usb+mic,usb+"（未標示靜音）"),matches);
        assertTrue(matches.containsKey("藍牙已連且列出有線／USB 輸入"));
        assertFalse(matches.containsKey("多個外接輸入同時列出"));
    }
    @Test public void distinguishAllZeroFromVariableSignalAndDiscardAttributionIfRouteChanges() {
        var zero=new Measurement(16000); var tone=new Measurement(16000); var switched=new Measurement(16000);
        short[] quiet=new short[800], loud=new short[800];
        for(int i=0;i<loud.length;i++) loud[i]=(short)((i%2==0)?2400:-2400);
        for(int i=0;i<Measurement.TOTAL;i++) {
            zero.frame(quiet,5,sco,false); tone.frame(i<Measurement.QUIET?quiet:loud,6,wired,false);
            switched.frame(loud,i<100?6:5,i<100?wired:sco,false);
        }
        assertEquals("所有音訊樣本均為零",zero.sampleSummary());
        assertEquals("有非零音訊樣本",tone.sampleSummary());
        assertTrue(tone.result(-1).contains("觀察到訊號上升")); assertEquals(wired,tone.stableType());
        assertNull(switched.stableType());
    }
    @Test public void standbyDoesNotRequireRecordPermissionAndMeterOpensIdle() {
        var app=RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Prefs.REQUIRED);
        assertTrue(Prefs.permitted(app));
        assertNotEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO));
        try(var c=Robolectric.buildActivity(LevelActivity.class).setup()){
            var hits=new ArrayList<android.view.View>();
            c.get().getWindow().getDecorView().findViewsWithText(hits,"開始六秒量測",android.view.View.FIND_VIEWS_WITH_TEXT);
            assertEquals(1,hits.size());assertTrue(hits.get(0).isEnabled());
        }
    }
}
