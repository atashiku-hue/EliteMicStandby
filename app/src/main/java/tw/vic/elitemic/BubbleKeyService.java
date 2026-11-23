package tw.vic.elitemic;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.PowerManager;
import android.telecom.TelecomManager;
import android.view.KeyEvent;
import android.view.accessibility.*;
import android.widget.Toast;
import java.util.ArrayDeque;
import java.util.Locale;

public class BubbleKeyService extends AccessibilityService {
    static final String TYPELESS="com.typeless.mobile";
    private static final long HOLD_MS=600;
    private int capturedWindow=-1;
    static final String TAP_PREF="tapVolumeDown";
    private long downTime;
    private boolean tapMode, volumeAdjusted;

    boolean usesTap(){return Prefs.of(this).getBoolean(TAP_PREF,false);}
    void lowerVolume(){
        getSystemService(AudioManager.class).adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER,
            AudioManager.USE_DEFAULT_STREAM_TYPE,AudioManager.FLAG_SHOW_UI);
    }
    // No recording of events or content. Inspect candidate windows only on explicit volume-down input.
    @Override public void onAccessibilityEvent(AccessibilityEvent ignored){}
    @Override public void onInterrupt(){capturedWindow=-1;}

    boolean canTrigger(){
        if(!StandbyService.readyForShortcut || getSystemService(KeyguardManager.class).isKeyguardLocked() ||
            !getSystemService(PowerManager.class).isInteractive() ||
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED) return false;
        try{return !getSystemService(TelecomManager.class).isInCall();}
        catch(RuntimeException denied){return false;}
    }
    static boolean voiceControl(String id,String desc) {
        String d=desc.trim().toLowerCase(Locale.ROOT);
        String i=id.toLowerCase(Locale.ROOT);
        return i.endsWith("/dictation_bubble") || i.endsWith("/voice_bubble") ||
            i.endsWith("/mic_bubble") || i.endsWith("/stop_dictation") ||
            d.matches("dictate|dictation|typeless bubble|start dictation|stop dictation|finish dictation|tap to speak|tap to finish|開始聽寫|停止聽寫|結束聽寫|开始听写|停止听写|结束听写|語音輸入|语音输入");
    }
    private AccessibilityNodeInfo bubble(){
        AccessibilityNodeInfo candidate=null;
        float density=getResources().getDisplayMetrics().density;
        for(AccessibilityWindowInfo w:getWindows()){
            if(w.getType()!=AccessibilityWindowInfo.TYPE_SYSTEM && w.getType()!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            Rect window=new Rect();w.getBoundsInScreen(window);
            AccessibilityNodeInfo root=w.getRoot();
            if(root==null || !TYPELESS.contentEquals(String.valueOf(root.getPackageName()))) continue;
            boolean small=window.width()>0 && window.height()>0 &&
                window.width()<=120*density && window.height()<=120*density;
            ArrayDeque<AccessibilityNodeInfo> nodes=new ArrayDeque<>();nodes.add(root);
            int visited=0;
            while(!nodes.isEmpty()){
                if(++visited>80) return null;
                AccessibilityNodeInfo n=nodes.removeFirst();
                if(!TYPELESS.contentEquals(String.valueOf(n.getPackageName()))) continue;
                if(n.isVisibleToUser() && n.isEnabled() && n.isClickable() && !n.isEditable()){
                    String desc=n.getContentDescription()==null?"":n.getContentDescription().toString();
                    String id=n.getViewIdResourceName()==null?"":n.getViewIdResourceName();
                    // An unlabeled, single, small overlay button is common for drawn bubbles.
                    if(voiceControl(id,desc) || (small && desc.isEmpty() && id.isEmpty() && n.getText()==null)){
                        if(candidate!=null) return null;
                        candidate=n;
                    }else if(small) return null;
                }
                for(int k=0;k<n.getChildCount();k++){AccessibilityNodeInfo child=n.getChild(k);if(child!=null) nodes.addLast(child);}
            }
        }
        return candidate;
    }
    int windowForKey(){
        AccessibilityNodeInfo n=bubble();return n==null?-1:n.getWindowId();
    }
    boolean clickSameWindow(int id){
        AccessibilityNodeInfo n=bubble();
        return n!=null && n.getWindowId()==id && n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    @Override protected boolean onKeyEvent(KeyEvent event){
        if(event.getKeyCode()!=KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if(event.getAction()==KeyEvent.ACTION_DOWN){
            if(event.getRepeatCount()>0){
                if(capturedWindow<0) return false;
                if(tapMode && event.getEventTime()-downTime>=HOLD_MS && canTrigger() && !event.isCanceled()){
                    lowerVolume();
                    volumeAdjusted=true;
                }
                return true;
            }
            capturedWindow=-1;
            if(!canTrigger()) return false;
            capturedWindow=windowForKey();
            if(capturedWindow<0) return false;
            downTime=event.getEventTime();
            tapMode=usesTap();
            volumeAdjusted=false;
            return true;
        }
        if(event.getAction()!=KeyEvent.ACTION_UP || capturedWindow<0) return false;
        int windowId=capturedWindow;capturedWindow=-1;
        if(event.isCanceled() || !canTrigger()) return true;
        boolean shortPress=event.getEventTime()-downTime<HOLD_MS;
        if(shortPress!=tapMode){
            if(!volumeAdjusted) lowerVolume();
            return true;
        }
        if(!clickSameWindow(windowId))
            Toast.makeText(this,"無法確認 Typeless 按鈕，請直接點懸浮球",Toast.LENGTH_SHORT).show();
        return true;
    }
}
