package tw.vic.elitemic;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

final class Prefs {
    // A new opt-in prevents older installations from inheriting always-on voice.
    static final String KEEP_VOICE_READY = "keepVoiceReady";
    static boolean keepVoiceReady(Context context) { return of(context).getBoolean(KEEP_VOICE_READY, false); }
    static final String[] REQUIRED = {Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.READ_PHONE_STATE};
    static SharedPreferences of(Context c) { return c.getSharedPreferences("standby", Context.MODE_PRIVATE); }
    static boolean permitted(Context c) {
        for (String p : REQUIRED)
            if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }
    private Prefs() {}
}
