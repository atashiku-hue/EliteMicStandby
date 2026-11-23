package tw.vic.elitemic;

import android.view.KeyEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class BubbleKeyTest {
    static class Fixture extends BubbleKeyService {
        boolean allowed=true, singleTap;
        int volumeSteps;
        @Override boolean usesTap(){return singleTap;}
        @Override void lowerVolume(){volumeSteps++;}
        int window=17,clicks;
        @Override boolean canTrigger(){return allowed;}
        @Override int windowForKey(){return window;}
        @Override boolean clickSameWindow(int id){if(window!=id) return false;clicks++;return true;}
        boolean key(int action,long time){return onKeyEvent(new KeyEvent(100,time,action,KeyEvent.KEYCODE_VOLUME_DOWN,0));}
    }
    @Test public void heldVolumeDownActivatesOnceOnRelease(){
        Fixture f=new Fixture();
        assertTrue(f.key(KeyEvent.ACTION_DOWN,100));
        assertTrue(f.key(KeyEvent.ACTION_UP,750));
        assertEquals(1,f.clicks);
        assertFalse(f.key(KeyEvent.ACTION_UP,751));
    }
    @Test public void callArrivingWhileHeldCannotActivate(){
        Fixture f=new Fixture();assertTrue(f.key(KeyEvent.ACTION_DOWN,100));
        f.allowed=false; assertTrue(f.key(KeyEvent.ACTION_UP,750));assertEquals(0,f.clicks);
    }
    @Test public void absentBubblePassesThrough(){
        Fixture f=new Fixture();f.window=-1;assertFalse(f.key(KeyEvent.ACTION_DOWN,100));
    }
    @Test public void disabledPassesThrough(){
        Fixture f=new Fixture();f.allowed=false;assertFalse(f.key(KeyEvent.ACTION_DOWN,100));
    }
    @Test public void singlePressClicksOnlyOnceOnRelease(){
        Fixture f=new Fixture(); f.singleTap=true;
        assertTrue(f.key(KeyEvent.ACTION_DOWN,100)); assertEquals(0,f.clicks);
        assertTrue(f.key(KeyEvent.ACTION_UP,220));
        assertFalse(f.key(KeyEvent.ACTION_UP,230));
        assertEquals(1,f.clicks); assertEquals(0,f.volumeSteps);
    }
    @Test public void heldKeyInTapModeAdjustsVolumeOncePerRepeat(){
        Fixture f=new Fixture(); f.singleTap=true;
        f.key(KeyEvent.ACTION_DOWN,100);
        for(int i=1;i<=2;i++) assertTrue(f.onKeyEvent(new KeyEvent(100,700+100*i,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_DOWN,i)));
        assertTrue(f.key(KeyEvent.ACTION_UP,950));
        assertEquals(2,f.volumeSteps); assertEquals(0,f.clicks);
    }
    @Test public void releaseAtThresholdWithoutRepeatsLowersVolume(){
        Fixture f=new Fixture(); f.singleTap=true;
        f.key(KeyEvent.ACTION_DOWN,100); f.key(KeyEvent.ACTION_UP,700);
        assertEquals(1,f.volumeSteps); assertEquals(0,f.clicks);
    }
    @Test public void legacyShortPressStillLowersVolume(){
        Fixture f=new Fixture();
        f.key(KeyEvent.ACTION_DOWN,100); f.key(KeyEvent.ACTION_UP,200);
        assertEquals(1,f.volumeSteps); assertEquals(0,f.clicks);
    }
    @Test public void callBeforeShortReleaseSkipsClick(){
        Fixture f=new Fixture(); f.singleTap=true;
        f.key(KeyEvent.ACTION_DOWN,100); f.allowed=false;
        assertTrue(f.key(KeyEvent.ACTION_UP,260)); assertEquals(0,f.clicks);
    }
    @Test public void onlyVoiceLabelsRecognized(){
        assertTrue(BubbleKeyService.voiceControl("","開始聽寫"));
        assertTrue(BubbleKeyService.voiceControl("","Tap to finish"));
        assertFalse(BubbleKeyService.voiceControl("","Delete"));
    }
}
